package jp.paijp.wioflash;

import android.hardware.usb.UsbConstants;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbEndpoint;
import android.hardware.usb.UsbInterface;
import android.hardware.usb.UsbManager;

import java.io.IOException;

/**
 * Minimal USB CDC-ACM client (Wio LTE SerialUSB is 0483:5740).
 */
public class CdcSerial {
	public static final int VID = 0x0483;
	public static final int PID = 0x5740;

	public interface Listener {
		void onData(byte[] b, int len);
		void onClosed();
	}

	private final UsbDeviceConnection conn;
	private final UsbInterface ctrl, data;
	private final UsbEndpoint in, out;
	private volatile boolean running = true;

	public CdcSerial(UsbManager mgr, UsbDevice dev) throws IOException {
		UsbInterface c = null, d = null;
		for (int i = 0; i < dev.getInterfaceCount(); i++) {
			UsbInterface it = dev.getInterface(i);
			if (it.getInterfaceClass() == UsbConstants.USB_CLASS_COMM && c == null)
				c = it;
			if (it.getInterfaceClass() == UsbConstants.USB_CLASS_CDC_DATA && d == null)
				d = it;
		}
		if (d == null)
			throw new IOException("CDC data interface not found");
		UsbEndpoint ei = null, eo = null;
		for (int i = 0; i < d.getEndpointCount(); i++) {
			UsbEndpoint e = d.getEndpoint(i);
			if (e.getType() != UsbConstants.USB_ENDPOINT_XFER_BULK)
				continue;
			if (e.getDirection() == UsbConstants.USB_DIR_IN)
				ei = e;
			else
				eo = e;
		}
		if (ei == null || eo == null)
			throw new IOException("CDC endpoints not found");
		ctrl = c;
		data = d;
		in = ei;
		out = eo;
		conn = mgr.openDevice(dev);
		if (conn == null)
			throw new IOException("openDevice failed (permission?)");
		if (ctrl != null)
			conn.claimInterface(ctrl, true);
		if (!conn.claimInterface(data, true))
			throw new IOException("claimInterface failed");
		int ifn = ctrl != null ? ctrl.getId() : 0;
		/* SET_LINE_CODING 115200 8N1, then SET_CONTROL_LINE_STATE DTR|RTS */
		byte[] lc = { 0x00, (byte) 0xc2, 0x01, 0x00, 0, 0, 8 };
		conn.controlTransfer(0x21, 0x20, 0, ifn, lc, lc.length, 1000);
		conn.controlTransfer(0x21, 0x22, 3, ifn, null, 0, 1000);
	}

	public void start(Listener l) {
		new Thread(() -> {
			byte[] b = new byte[in.getMaxPacketSize() * 8];
			while (running) {
				int n = conn.bulkTransfer(in, b, b.length, 200);
				if (n > 0)
					l.onData(b, n);
				else if (n < 0 && !running)
					break;
			}
			l.onClosed();
		}).start();
	}

	public void write(byte[] b) throws IOException {
		int off = 0;
		while (off < b.length) {
			int n = Math.min(b.length - off, out.getMaxPacketSize());
			byte[] chunk = new byte[n];
			System.arraycopy(b, off, chunk, 0, n);
			if (conn.bulkTransfer(out, chunk, n, 1000) != n)
				throw new IOException("serial write failed");
			off += n;
		}
	}

	public void close() {
		running = false;
		try {
			Thread.sleep(300);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
		if (ctrl != null)
			conn.releaseInterface(ctrl);
		conn.releaseInterface(data);
		conn.close();
	}
}
