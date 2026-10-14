package jp.paijp.wioflash;

import android.app.Activity;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.Cursor;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.view.KeyEvent;
import android.view.Menu;
import android.view.MenuItem;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.EditText;
import android.widget.PopupMenu;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * program - and the board's output underneath.
 *
 * "program" writes the chosen .bin over USB DFU (the load address comes
 * from the image's vector table), verifies it, starts it, waits for the
 * sketch's USB serial to appear and then shows its output.  The box at
 * the bottom types to the board.
 *
 * There is no reset button: Wio LTE can only be reset from the host while
 * it is in DFU mode, and entering DFU mode needs the BOOT button.
 */
public class MainActivity extends Activity implements DfuSe.Log {
	private static final String ACTION_PERM = "jp.paijp.wioflash.USB_PERMISSION";
	private static final int REQ_OPEN = 1;
	private static final long BOOT_ADDR = 0x08000000L;
	private static final long APP_ADDR = 0x08010000L;
	/* Trim once the log passes LOG_LIMIT, back to LOG_KEEP. */
	private static final int LOG_LIMIT = 240000;
	private static final int LOG_KEEP = 160000;

	private TextView statusView, imageView, logView;
	private ScrollView scroll;
	private ProgressBar bar;
	private EditText input;

	private UsbManager usb;
	private Recents recents;
	private Updater updater;
	private final ExecutorService worker = Executors.newSingleThreadExecutor();
	private volatile CdcSerial serial;
	private volatile boolean busy;
	private Runnable afterPermission;

	private Uri program;
	private String programName = "";

	private final BroadcastReceiver usbReceiver = new BroadcastReceiver() {
		@Override
		public void onReceive(Context c, Intent i) {
			if (ACTION_PERM.equals(i.getAction())) {
				Runnable r = afterPermission;
				afterPermission = null;
				if (i.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false) && r != null)
					r.run();
				else if (r != null)
					line("USB permission denied");
			} else if (UsbManager.ACTION_USB_DEVICE_DETACHED.equals(i.getAction())) {
				UsbDevice d = i.getParcelableExtra(UsbManager.EXTRA_DEVICE);
				if (d != null && d.getProductId() == CdcSerial.PID)
					closeSerial("board detached");
			} else if (UsbManager.ACTION_USB_DEVICE_ATTACHED.equals(i.getAction())) {
				UsbDevice d = i.getParcelableExtra(UsbManager.EXTRA_DEVICE);
				if (d != null && d.getProductId() == CdcSerial.PID && !busy)
					openSerial();
			}
			refreshStatus();
		}
	};

	@Override
	protected void onCreate(Bundle b) {
		super.onCreate(b);
		setContentView(R.layout.activity_main);
		statusView = findViewById(R.id.status);
		imageView = findViewById(R.id.image);
		logView = findViewById(R.id.log);
		scroll = findViewById(R.id.scroll);
		bar = findViewById(R.id.progress);
		input = findViewById(R.id.input);
		usb = (UsbManager) getSystemService(USB_SERVICE);
		recents = new Recents(this);

		findViewById(R.id.program).setOnClickListener(v -> {
			/* Nothing chosen yet: "program" can only mean "which one?" */
			if (program == null)
				showPrograms();
			else
				startProgram();
		});
		findViewById(R.id.choose).setOnClickListener(v -> showPrograms());
		findViewById(R.id.send).setOnClickListener(v -> sendInput(false));
		input.setOnEditorActionListener((v, actionId, ev) -> {
			boolean done = actionId == EditorInfo.IME_ACTION_SEND
					|| actionId == EditorInfo.IME_ACTION_DONE
					|| actionId == EditorInfo.IME_NULL;
			if (done && (ev == null || ev.getAction() == KeyEvent.ACTION_DOWN)) {
				sendInput(true);
				return true;
			}
			return false;
		});

		IntentFilter f = new IntentFilter(ACTION_PERM);
		f.addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED);
		f.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED);
		if (Build.VERSION.SDK_INT >= 33)
			registerReceiver(usbReceiver, f, Context.RECEIVER_NOT_EXPORTED);
		else
			registerReceiver(usbReceiver, f);

		/* A file opened from elsewhere, or the last one used. */
		if (!handleIntent(getIntent())) {
			List<Recents.Entry> l = recents.list();
			if (!l.isEmpty())
				select(l.get(0).uri, l.get(0).name, false);
		}
		refreshStatus();
		if (findDevice(CdcSerial.PID) != null)
			openSerial();

		updater = new Updater(this, this);
		updater.register();
		updater.check();
	}

	@Override
	protected void onNewIntent(Intent i) {
		super.onNewIntent(i);
		setIntent(i);
		handleIntent(i);
		refreshStatus();
	}

	@Override
	protected void onDestroy() {
		closeSerial(null);
		unregisterReceiver(usbReceiver);
		updater.unregister();
		worker.shutdownNow();
		super.onDestroy();
	}

	/* ---------------- choosing a program ---------------- */

	/**
	 * A .bin arriving by share or by open.  The action, type and URI are
	 * reported: which of these a file manager sends is the whole question
	 * when it refuses to offer this app.
	 */
	private boolean handleIntent(Intent i) {
		if (i == null || i.getAction() == null)
			return false;
		String action = i.getAction();
		Uri uri;
		if (Intent.ACTION_SEND.equals(action)) {
			uri = i.getParcelableExtra(Intent.EXTRA_STREAM);
		} else if (Intent.ACTION_SEND_MULTIPLE.equals(action)) {
			/* A selection of one is still a selection; keep the rest in the list. */
			ArrayList<Uri> all = i.getParcelableArrayListExtra(Intent.EXTRA_STREAM);
			if (all == null || all.isEmpty()) {
				uri = null;
			} else {
				for (int k = all.size() - 1; k >= 1; k--) {
					Recents.persist(this, all.get(k));
					recents.remember(all.get(k), displayName(all.get(k)));
				}
				if (all.size() > 1)
					line(all.size() + " files shared: the first is selected, the rest are in the list");
				uri = all.get(0);
			}
		} else if (Intent.ACTION_VIEW.equals(action)) {
			uri = i.getData();
		} else if (UsbManager.ACTION_USB_DEVICE_ATTACHED.equals(action)) {
			UsbDevice d = i.getParcelableExtra(UsbManager.EXTRA_DEVICE);
			if (d != null && d.getProductId() == CdcSerial.PID && !busy)
				openSerial();
			return false;
		} else {
			return false;
		}
		line("opened by " + action + ", type " + (i.getType() == null ? "none" : i.getType())
				+ ", " + (uri == null ? "no uri" : uri));
		if (uri == null) {
			line("that intent carried no file; use the dropdown instead");
			return false;
		}
		Recents.persist(this, uri);
		select(uri, null, true);
		return program != null;
	}

	private void showPrograms() {
		PopupMenu menu = new PopupMenu(this, findViewById(R.id.choose));
		/* Open... first, so the chooser is always one tap away. */
		menu.getMenu().add(Menu.NONE, 0, 0, getString(R.string.open_program));
		/* Then the current program, marked, then the rest. */
		final List<Recents.Entry> ordered = new ArrayList<>();
		if (program != null)
			ordered.add(new Recents.Entry(program, programName));
		for (Recents.Entry e : recents.list())
			if (program == null || !e.uri.equals(program))
				ordered.add(e);
		for (int k = 0; k < ordered.size(); k++) {
			MenuItem it = menu.getMenu().add(Menu.NONE, k + 1, k + 1, ordered.get(k).name);
			if (ordered.get(k).uri.equals(program)) {
				it.setCheckable(true);
				it.setChecked(true);
			}
		}
		menu.setOnMenuItemClickListener(it -> {
			if (it.getItemId() == 0) {
				Intent o = new Intent(Intent.ACTION_OPEN_DOCUMENT);
				o.addCategory(Intent.CATEGORY_OPENABLE);
				o.setType("*/*");
				startActivityForResult(o, REQ_OPEN);
			} else {
				Recents.Entry e = ordered.get(it.getItemId() - 1);
				select(e.uri, e.name, true);
			}
			return true;
		});
		menu.show();
	}

	@Override
	protected void onActivityResult(int req, int res, Intent data) {
		super.onActivityResult(req, res, data);
		if (req == REQ_OPEN && res == RESULT_OK && data != null && data.getData() != null) {
			Recents.persist(this, data.getData());
			select(data.getData(), null, true);
		}
	}

	/**
	 * Read it now rather than at flash time: a URI that has gone stale is
	 * reported while the user is still looking at the list.
	 */
	private void select(Uri uri, String knownName, boolean remember) {
		String name = knownName != null ? knownName : displayName(uri);
		byte[] img;
		try {
			img = readAll(uri);
		} catch (Exception e) {
			recents.forget(uri);
			program = null;
			imageView.setText(name + " is no longer readable; choose it again");
			return;
		}
		program = uri;
		programName = name;
		if (remember)
			recents.remember(uri, name);
		long a = detectAddress(img);
		String kind = a == BOOT_ADDR ? "bootloader" : a == APP_ADDR ? "application" : "unknown image";
		imageView.setText(String.format("%s, %d bytes, %s%s", name, img.length, kind,
				a < 0 ? "" : String.format(" @ 0x%08x", a)));
	}

	private String displayName(Uri uri) {
		try (Cursor c = getContentResolver().query(uri, null, null, null, null)) {
			if (c != null) {
				int k = c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
				if (k >= 0 && c.moveToFirst())
					return c.getString(k);
			}
		} catch (Exception e) {
			/* not every provider answers a query */
		}
		String s = uri.getLastPathSegment();
		return s == null ? "program" : s.substring(s.lastIndexOf('/') + 1);
	}

	private byte[] readAll(Uri u) throws IOException {
		try (InputStream in = getContentResolver().openInputStream(u)) {
			if (in == null)
				throw new IOException("no stream");
			ByteArrayOutputStream out = new ByteArrayOutputStream();
			byte[] b = new byte[8192];
			int n;
			while ((n = in.read(b)) > 0)
				out.write(b, 0, n);
			return out.toByteArray();
		}
	}

	/** Load address from the vector table's reset handler (word 1). */
	static long detectAddress(byte[] img) {
		if (img.length < 8)
			return -1;
		long sp = le32(img, 0), pc = le32(img, 4);
		if (sp < 0x20000000L || sp > 0x20030000L || (pc & 1) == 0)
			return -1;
		if (pc >= BOOT_ADDR && pc < APP_ADDR)
			return BOOT_ADDR;
		if (pc >= APP_ADDR && pc < 0x08080000L)
			return APP_ADDR;
		return -1;
	}

	private static long le32(byte[] b, int o) {
		return (b[o] & 0xffL) | (b[o + 1] & 0xffL) << 8 | (b[o + 2] & 0xffL) << 16 | (b[o + 3] & 0xffL) << 24;
	}

	/* ---------------- the device ---------------- */

	private UsbDevice findDevice(int pid) {
		for (UsbDevice d : usb.getDeviceList().values())
			if (d.getVendorId() == 0x0483 && d.getProductId() == pid)
				return d;
		return null;
	}

	private void refreshStatus() {
		UsbDevice dfu = findDevice(DfuSe.PID), cdc = findDevice(CdcSerial.PID);
		String s;
		if (busy)
			s = "programming…";
		else if (dfu != null)
			s = "Wio LTE in DFU mode — ready to program" + (usb.hasPermission(dfu) ? "" : " (permission needed)");
		else if (cdc != null)
			s = "Wio LTE running" + (serial != null ? " — monitoring USB serial" : "");
		else
			s = getString(R.string.no_device);
		statusView.setText(s);
	}

	private void withPermission(UsbDevice d, Runnable r) {
		if (usb.hasPermission(d)) {
			r.run();
			return;
		}
		afterPermission = r;
		Intent i = new Intent(ACTION_PERM).setPackage(getPackageName());
		int fl = Build.VERSION.SDK_INT >= 31 ? PendingIntent.FLAG_MUTABLE : 0;
		usb.requestPermission(d, PendingIntent.getBroadcast(this, 0, i, fl));
	}

	private void startProgram() {
		if (busy)
			return;
		final byte[] img;
		try {
			img = readAll(program);
		} catch (Exception e) {
			recents.forget(program);
			program = null;
			imageView.setText(programName + " is no longer readable; choose it again");
			return;
		}
		final long addr = detectAddress(img);
		if (addr < 0) {
			line("cannot tell where " + programName + " goes (vector table is not a Wio LTE image)");
			return;
		}
		UsbDevice d = findDevice(DfuSe.PID);
		if (d == null) {
			line(findDevice(CdcSerial.PID) != null
					? "the board is running; hold BOOT, press and release RST, then release BOOT to enter DFU mode"
					: getString(R.string.no_device));
			return;
		}
		withPermission(d, () -> {
			closeSerial(null);
			busy = true;
			refreshStatus();
			line(String.format("program %s (%d bytes) @ 0x%08x", programName, img.length, addr));
			worker.execute(() -> {
				DfuSe dfu = null;
				boolean ok = false;
				try {
					dfu = new DfuSe(usb, d, this);
					dfu.write(addr, img, true);
					dfu.leave(BOOT_ADDR);
					ok = true;
				} catch (IOException e) {
					line("failed: " + e.getMessage());
				} finally {
					if (dfu != null)
						dfu.close();
				}
				if (ok)
					waitForSerial();
				runOnUiThread(() -> {
					busy = false;
					refreshStatus();
				});
			});
		});
	}

	/* After DFU leaves, the sketch enumerates as USB serial; follow it. */
	private void waitForSerial() {
		for (int k = 0; k < 75; k++) {
			if (findDevice(CdcSerial.PID) != null) {
				runOnUiThread(this::openSerial);
				return;
			}
			try {
				Thread.sleep(200);
			} catch (InterruptedException e) {
				return;
			}
		}
		line("no USB serial after 15 s (bootloader only, or the sketch has no SerialUSB)");
	}

	private void openSerial() {
		if (serial != null)
			return;
		UsbDevice d = findDevice(CdcSerial.PID);
		if (d == null)
			return;
		withPermission(d, () -> {
			try {
				CdcSerial s = new CdcSerial(usb, d);
				serial = s;
				line("monitoring USB serial");
				s.start(new CdcSerial.Listener() {
					@Override
					public void onData(byte[] b, int len) {
						append(new String(b, 0, len, StandardCharsets.UTF_8));
					}

					@Override
					public void onClosed() {
					}
				});
			} catch (IOException e) {
				line("serial: " + e.getMessage());
			}
			refreshStatus();
		});
	}

	private void closeSerial(String why) {
		CdcSerial s = serial;
		if (s == null)
			return;
		serial = null;
		s.close();
		if (why != null)
			line(why);
	}

	private void sendInput(boolean newline) {
		String text = input.getText().toString();
		if (text.isEmpty() && !newline)
			return;
		CdcSerial s = serial;
		if (s == null) {
			line("nothing is monitoring; connect the running board first");
			return;
		}
		String payload = newline ? text + "\n" : text;
		try {
			s.write(payload.getBytes(StandardCharsets.UTF_8));
			line("sent: " + text + (newline ? "\\n" : ""));
			input.setText("");
		} catch (IOException e) {
			line("send failed: " + e.getMessage());
		}
	}

	/* ---------------- log ---------------- */

	/** This app's own lines, marked with a leading dot. */
	private void line(String s) {
		append("\n· " + s + "\n");
	}

	@Override
	public void log(String s) {
		line(s);
	}

	@Override
	public void progress(int done, int total) {
		runOnUiThread(() -> {
			bar.setMax(total);
			bar.setProgress(done);
		});
	}

	/*
	 * Add to the log, scroll only if it was already at the bottom, and do
	 * not disturb focus: ScrollView.fullScroll() requests focus and would
	 * take the keyboard away from the input box.
	 */
	private void append(String text) {
		runOnUiThread(() -> {
			boolean atBottom = !scroll.canScrollVertically(1);
			logView.append(text);
			CharSequence t = logView.getText();
			if (t.length() > LOG_LIMIT) {
				int cut = t.length() - LOG_KEEP;
				int nl = t.toString().indexOf('\n', cut);
				if (nl >= cut)
					cut = nl + 1;
				logView.setText(t.subSequence(cut, t.length()));
			}
			if (atBottom)
				scroll.post(() -> scroll.scrollTo(0, logView.getBottom()));
		});
	}
}
