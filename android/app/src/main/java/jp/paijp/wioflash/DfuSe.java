package jp.paijp.wioflash;

import android.hardware.usb.UsbConstants;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbInterface;
import android.hardware.usb.UsbManager;

import java.io.IOException;
import java.util.Arrays;

/**
 * STM32 system bootloader (USB DFU with ST DfuSe extensions), enough
 * to erase, program, verify and start internal flash of STM32F4.
 */
public class DfuSe {
	public static final int VID = 0x0483;
	public static final int PID = 0xdf11;

	public interface Log {
		void log(String s);
		void progress(int done, int total);
	}

	private static final int DFU_DNLOAD = 1;
	private static final int DFU_UPLOAD = 2;
	private static final int DFU_GETSTATUS = 3;
	private static final int DFU_CLRSTATUS = 4;
	private static final int DFU_ABORT = 6;

	private static final int STATE_IDLE = 2;
	private static final int STATE_DNLOAD_IDLE = 5;
	private static final int STATE_MANIFEST = 7;
	private static final int STATE_UPLOAD_IDLE = 9;
	private static final int STATE_ERROR = 10;

	private static final int XFER = 2048;
	private static final int TIMEOUT = 5000;

	private final UsbDeviceConnection conn;
	private final UsbInterface intf;
	private final Log log;

	public DfuSe(UsbManager mgr, UsbDevice dev, Log log) throws IOException {
		this.log = log;
		UsbInterface found = null;
		for (int i = 0; i < dev.getInterfaceCount(); i++) {
			UsbInterface it = dev.getInterface(i);
			/* DFU: class 0xfe subclass 1; alt 0 is internal flash */
			if (it.getInterfaceClass() == UsbConstants.USB_CLASS_APP_SPEC
					&& it.getInterfaceSubclass() == 1 && it.getAlternateSetting() == 0) {
				found = it;
				break;
			}
		}
		if (found == null)
			throw new IOException("DFU interface not found");
		intf = found;
		conn = mgr.openDevice(dev);
		if (conn == null)
			throw new IOException("openDevice failed (permission?)");
		if (!conn.claimInterface(intf, true))
			throw new IOException("claimInterface failed");
		conn.setInterface(intf);
	}

	public void close() {
		conn.releaseInterface(intf);
		conn.close();
	}

	private int[] getStatus() throws IOException {
		byte[] b = new byte[6];
		int r = conn.controlTransfer(0xa1, DFU_GETSTATUS, 0, intf.getId(), b, 6, TIMEOUT);
		if (r != 6)
			throw new IOException("GETSTATUS failed");
		int poll = (b[1] & 0xff) | (b[2] & 0xff) << 8 | (b[3] & 0xff) << 16;
		return new int[] { b[0] & 0xff, poll, b[4] & 0xff };
	}

	private void ctrlOut(int req, int value, byte[] data) throws IOException {
		int len = data == null ? 0 : data.length;
		int r = conn.controlTransfer(0x21, req, value, intf.getId(), data, len, TIMEOUT);
		if (r != len)
			throw new IOException("control out " + req + " failed");
	}

	/** Bring the device to dfuIDLE. */
	private void toIdle() throws IOException {
		for (int i = 0; i < 5; i++) {
			int[] s = getStatus();
			if (s[2] == STATE_IDLE)
				return;
			if (s[2] == STATE_ERROR)
				ctrlOut(DFU_CLRSTATUS, 0, null);
			else
				ctrlOut(DFU_ABORT, 0, null);
		}
		throw new IOException("cannot reach dfuIDLE");
	}

	/** DNLOAD then poll GETSTATUS until the operation completes. */
	private void download(int block, byte[] data) throws IOException {
		ctrlOut(DFU_DNLOAD, block, data);
		for (int i = 0; i < 100; i++) {
			int[] s = getStatus();
			if (s[0] != 0)
				throw new IOException("DFU status error " + s[0] + " state " + s[2]);
			if (s[2] == STATE_DNLOAD_IDLE || s[2] == STATE_IDLE)
				return;
			if (s[2] == STATE_MANIFEST)
				return;
			sleep(Math.max(s[1], 1));
		}
		throw new IOException("DFU busy timeout");
	}

	private void command(int cmd, long addr) throws IOException {
		byte[] b = new byte[] { (byte) cmd, (byte) addr, (byte) (addr >> 8),
				(byte) (addr >> 16), (byte) (addr >> 24) };
		download(0, b);
	}

	/** STM32F4 1MB single-bank sector start addresses. */
	static long sectorAddr(int s) {
		if (s < 4)
			return 0x08000000L + s * 0x4000L;
		if (s == 4)
			return 0x08010000L;
		return 0x08000000L + (s - 4) * 0x20000L;
	}

	static int sectorOf(long a) {
		long off = a - 0x08000000L;
		if (off < 0x10000L)
			return (int) (off / 0x4000L);
		if (off < 0x20000L)
			return 4;
		return (int) (4 + off / 0x20000L);
	}

	public void write(long addr, byte[] img, boolean verify) throws IOException {
		if (addr < 0x08000000L || addr + img.length > 0x08100000L)
			throw new IOException("image outside flash");
		toIdle();

		int first = sectorOf(addr), last = sectorOf(addr + img.length - 1);
		if (sectorAddr(first) != addr)
			log.log(String.format("warning: %08x is not a sector start; sector %d is erased whole",
					addr, first));
		for (int s = first; s <= last; s++) {
			log.log(String.format("erase sector %d (%08x)", s, sectorAddr(s)));
			command(0x41, sectorAddr(s));
		}

		log.log(String.format("write %d bytes at %08x", img.length, addr));
		for (int off = 0; off < img.length; off += XFER) {
			int n = Math.min(XFER, img.length - off);
			command(0x21, addr + off);
			download(2, Arrays.copyOfRange(img, off, off + n));
			log.progress(off + n, img.length);
		}

		if (verify) {
			log.log("verify");
			toIdle();
			command(0x21, addr);
			toIdle();
			byte[] buf = new byte[XFER];
			for (int off = 0, blk = 2; off < img.length; off += XFER, blk++) {
				int n = Math.min(XFER, img.length - off);
				int r = conn.controlTransfer(0xa1, DFU_UPLOAD, blk, intf.getId(), buf, XFER, TIMEOUT);
				if (r < n)
					throw new IOException("UPLOAD failed at " + off);
				for (int i = 0; i < n; i++)
					if (buf[i] != img[off + i])
						throw new IOException(String.format("verify mismatch at %08x", addr + off + i));
				log.progress(off + n, img.length);
			}
			toIdle();
		}
		log.log("done");
	}

	/** Leave DFU and start the code at addr (vector table there). */
	public void leave(long addr) throws IOException {
		toIdle();
		command(0x21, addr);
		ctrlOut(DFU_DNLOAD, 0, null);
		try {
			getStatus();	/* triggers manifestation; device resets */
		} catch (IOException e) {
			/* expected: the device may detach before answering */
		}
		log.log(String.format("started at %08x", addr));
	}

	private static void sleep(int ms) {
		try {
			Thread.sleep(ms);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}
}
