package jp.paijp.wioflash;

import android.app.Activity;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.OpenableColumns;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Wio LTE tool: writes .bin images over USB DFU and talks to the
 * running sketch over USB serial (SerialUSB).
 *
 * Images arrive by "share" (ACTION_SEND), "open with" (ACTION_VIEW)
 * or the file picker; each is copied into app storage and kept in a
 * recent list shown in the dropdown.  The load address is detected
 * from the image's vector table (bootloader 0x08000000 or
 * application 0x08010000).
 */
public class MainActivity extends Activity implements DfuSe.Log {
	private static final String ACTION_PERM = "jp.paijp.wioflash.USB_PERMISSION";
	private static final int REQ_FILE = 1;
	private static final int MAX_RECENT = 10;
	private static final long BOOT_ADDR = 0x08000000L;
	private static final long APP_ADDR = 0x08010000L;

	private final Handler ui = new Handler(Looper.getMainLooper());
	private UsbManager usb;
	private SharedPreferences prefs;
	private Spinner recentSpin;
	private ArrayAdapter<String> recentAdapter;
	private final List<String> recent = new ArrayList<>();
	private TextView infoText, logText;
	private CheckBox startBox;
	private ProgressBar bar;
	private ScrollView scroll;
	private Button flashBtn, serialBtn;
	private EditText sendEdit;
	private boolean busy;
	private Updater updater;
	private CdcSerial serial;
	private Runnable afterPermission;

	private final BroadcastReceiver usbReceiver = new BroadcastReceiver() {
		@Override
		public void onReceive(Context c, Intent i) {
			UsbDevice d = i.getParcelableExtra(UsbManager.EXTRA_DEVICE);
			if (ACTION_PERM.equals(i.getAction())) {
				Runnable r = afterPermission;
				afterPermission = null;
				if (i.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false) && r != null)
					r.run();
				else
					log("USB permission denied");
			} else if (UsbManager.ACTION_USB_DEVICE_DETACHED.equals(i.getAction())) {
				if (d != null && d.getProductId() == CdcSerial.PID && serial != null)
					closeSerial();
			}
		}
	};

	@Override
	protected void onCreate(Bundle b) {
		super.onCreate(b);
		usb = (UsbManager) getSystemService(USB_SERVICE);
		prefs = getSharedPreferences("wioflash", MODE_PRIVATE);
		buildUi();

		IntentFilter f = new IntentFilter(ACTION_PERM);
		f.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED);
		if (Build.VERSION.SDK_INT >= 33)
			registerReceiver(usbReceiver, f, Context.RECEIVER_NOT_EXPORTED);
		else
			registerReceiver(usbReceiver, f);

		loadRecent();
		handleIntent(getIntent());

		updater = new Updater(this, this);
		updater.register();
		updater.check();
	}

	@Override
	protected void onNewIntent(Intent i) {
		super.onNewIntent(i);
		setIntent(i);
		handleIntent(i);
	}

	@Override
	protected void onDestroy() {
		closeSerial();
		unregisterReceiver(usbReceiver);
		updater.unregister();
		super.onDestroy();
	}

	/* ---------------- UI ---------------- */

	private void buildUi() {
		LinearLayout root = new LinearLayout(this);
		root.setOrientation(LinearLayout.VERTICAL);
		int pad = (int) (12 * getResources().getDisplayMetrics().density);
		root.setPadding(pad, pad, pad, pad);

		LinearLayout row = new LinearLayout(this);
		recentSpin = new Spinner(this);
		recentAdapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, recent);
		recentSpin.setAdapter(recentAdapter);
		recentSpin.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
			@Override
			public void onItemSelected(android.widget.AdapterView<?> p, android.view.View v, int pos, long id) {
				showInfo();
			}

			@Override
			public void onNothingSelected(android.widget.AdapterView<?> p) {
			}
		});
		row.addView(recentSpin, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
		Button pick = new Button(this);
		pick.setText("開く");
		pick.setOnClickListener(v -> {
			Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
			i.addCategory(Intent.CATEGORY_OPENABLE);
			i.setType("*/*");
			startActivityForResult(i, REQ_FILE);
		});
		row.addView(pick);
		root.addView(row);

		infoText = new TextView(this);
		root.addView(infoText);

		startBox = new CheckBox(this);
		startBox.setText("書き込み後に起動");
		startBox.setChecked(prefs.getBoolean("start", true));
		startBox.setOnCheckedChangeListener((v, c) -> prefs.edit().putBoolean("start", c).apply());
		root.addView(startBox);

		flashBtn = new Button(this);
		flashBtn.setText("DFU 書き込み");
		flashBtn.setOnClickListener(v -> flash());
		root.addView(flashBtn);

		bar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
		root.addView(bar);

		LinearLayout srow = new LinearLayout(this);
		serialBtn = new Button(this);
		serialBtn.setText("シリアル接続");
		serialBtn.setOnClickListener(v -> {
			if (serial != null)
				closeSerial();
			else
				openSerial();
		});
		srow.addView(serialBtn);
		Button clear = new Button(this);
		clear.setText("ログ消去");
		clear.setOnClickListener(v -> logText.setText(""));
		srow.addView(clear);
		root.addView(srow);

		LinearLayout trow = new LinearLayout(this);
		sendEdit = new EditText(this);
		sendEdit.setSingleLine(true);
		sendEdit.setHint("シリアルへ送信 (改行付き)");
		sendEdit.setImeOptions(EditorInfo.IME_ACTION_SEND);
		sendEdit.setOnEditorActionListener((v, a, e) -> {
			sendLine();
			return true;
		});
		trow.addView(sendEdit, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
		Button send = new Button(this);
		send.setText("送信");
		send.setOnClickListener(v -> sendLine());
		trow.addView(send);
		root.addView(trow);

		scroll = new ScrollView(this);
		logText = new TextView(this);
		logText.setTextIsSelectable(true);
		logText.setTypeface(android.graphics.Typeface.MONOSPACE);
		scroll.addView(logText);
		root.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
		setContentView(root);
	}

	/* ---------------- files / recent list ---------------- */

	private File recentDir() {
		File d = new File(getFilesDir(), "recent");
		d.mkdirs();
		return d;
	}

	private void loadRecent() {
		recent.clear();
		String s = prefs.getString("recent", "");
		for (String n : s.split("\n"))
			if (!n.isEmpty() && new File(recentDir(), n).isFile())
				recent.add(n);
		recentAdapter.notifyDataSetChanged();
		showInfo();
	}

	private void pushRecent(String name) {
		recent.remove(name);
		recent.add(0, name);
		while (recent.size() > MAX_RECENT) {
			String old = recent.remove(recent.size() - 1);
			new File(recentDir(), old).delete();
		}
		prefs.edit().putString("recent", android.text.TextUtils.join("\n", recent)).apply();
		recentAdapter.notifyDataSetChanged();
		recentSpin.setSelection(0);
		showInfo();
	}

	private void handleIntent(Intent i) {
		if (i == null)
			return;
		Uri u = null;
		if (Intent.ACTION_SEND.equals(i.getAction()))
			u = i.getParcelableExtra(Intent.EXTRA_STREAM);
		else if (Intent.ACTION_VIEW.equals(i.getAction()))
			u = i.getData();
		if (u != null)
			importUri(u);
		else if (UsbManager.ACTION_USB_DEVICE_ATTACHED.equals(i.getAction())) {
			UsbDevice d = i.getParcelableExtra(UsbManager.EXTRA_DEVICE);
			if (d != null && d.getProductId() == CdcSerial.PID && serial == null)
				openSerial();
			else if (d != null && d.getProductId() == DfuSe.PID)
				log("DFUデバイスを検出しました");
		}
	}

	private void importUri(Uri u) {
		try {
			String name = displayName(u);
			byte[] data = readAll(u);
			try (FileOutputStream out = new FileOutputStream(new File(recentDir(), name))) {
				out.write(data);
			}
			log("受信: " + name + " (" + data.length + " bytes)");
			pushRecent(name);
		} catch (IOException e) {
			log("ERROR: " + e.getMessage());
		}
	}

	private String displayName(Uri u) {
		String n = null;
		try (Cursor c = getContentResolver().query(u, new String[] { OpenableColumns.DISPLAY_NAME }, null, null, null)) {
			if (c != null && c.moveToFirst())
				n = c.getString(0);
		} catch (Exception e) {
			/* fall back below */
		}
		if (n == null)
			n = u.getLastPathSegment();
		if (n == null || n.isEmpty())
			n = "image.bin";
		return n.replaceAll("[/\\\\\\n]", "_");
	}

	private byte[] readAll(Uri u) throws IOException {
		try (InputStream in = getContentResolver().openInputStream(u)) {
			if (in == null)
				throw new IOException("cannot open file");
			return readStream(in);
		}
	}

	private static byte[] readStream(InputStream in) throws IOException {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		byte[] b = new byte[8192];
		int n;
		while ((n = in.read(b)) > 0)
			out.write(b, 0, n);
		return out.toByteArray();
	}

	private File selectedFile() {
		int p = recentSpin.getSelectedItemPosition();
		if (p < 0 || p >= recent.size())
			return null;
		return new File(recentDir(), recent.get(p));
	}

	/**
	 * Load address from the vector table: the reset handler (word 1)
	 * tells where the image was linked.
	 */
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

	private void showInfo() {
		File f = selectedFile();
		if (f == null) {
			infoText.setText("ファイル未選択（共有・開く・「開く」ボタンで追加）");
			return;
		}
		try (FileInputStream in = new FileInputStream(f)) {
			byte[] img = readStream(in);
			long a = detectAddress(img);
			String kind = a == BOOT_ADDR ? "ブートローダー" : a == APP_ADDR ? "アプリ" : "不明";
			infoText.setText(String.format("%d bytes / %s%s", img.length, kind,
					a < 0 ? "" : String.format(" (0x%08x)", a)));
		} catch (IOException e) {
			infoText.setText("読み込み失敗: " + e.getMessage());
		}
	}

	@Override
	protected void onActivityResult(int req, int res, Intent data) {
		super.onActivityResult(req, res, data);
		if (req == REQ_FILE && res == RESULT_OK && data != null && data.getData() != null)
			importUri(data.getData());
	}

	/* ---------------- USB ---------------- */

	private UsbDevice findDevice(int pid) {
		for (UsbDevice d : usb.getDeviceList().values())
			if (d.getVendorId() == 0x0483 && d.getProductId() == pid)
				return d;
		return null;
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

	private void flash() {
		if (busy)
			return;
		File f = selectedFile();
		if (f == null) {
			log("ファイルを選択してください");
			return;
		}
		UsbDevice d = findDevice(DfuSe.PID);
		if (d == null) {
			log("DFUデバイス (0483:df11) が見つかりません。BOOTを押しながらRSTを押して離してください");
			return;
		}
		withPermission(d, () -> startFlash(d, f));
	}

	private void startFlash(UsbDevice d, File f) {
		final boolean start = startBox.isChecked();
		closeSerial();
		busy = true;
		flashBtn.setEnabled(false);
		new Thread(() -> {
			DfuSe dfu = null;
			try {
				byte[] img;
				try (FileInputStream in = new FileInputStream(f)) {
					img = readStream(in);
				}
				long addr = detectAddress(img);
				if (addr < 0)
					throw new IOException("書き込みアドレスを判別できません（ベクタテーブル不正）");
				log(String.format("%s: %d bytes -> %08x", f.getName(), img.length, addr));
				dfu = new DfuSe(usb, d, this);
				dfu.write(addr, img, true);
				if (start)
					dfu.leave(BOOT_ADDR);
			} catch (IOException e) {
				log("ERROR: " + e.getMessage());
			} finally {
				if (dfu != null)
					dfu.close();
				ui.post(() -> {
					busy = false;
					flashBtn.setEnabled(true);
				});
			}
		}).start();
	}

	private void openSerial() {
		UsbDevice d = findDevice(CdcSerial.PID);
		if (d == null) {
			log("シリアルデバイス (0483:5740) が見つかりません");
			return;
		}
		withPermission(d, () -> {
			try {
				serial = new CdcSerial(usb, d);
				serialBtn.setText("シリアル切断");
				log("--- シリアル接続 ---");
				serial.start(new CdcSerial.Listener() {
					@Override
					public void onData(byte[] b, int len) {
						String s = new String(Arrays.copyOf(b, len), StandardCharsets.UTF_8);
						ui.post(() -> appendLog(s));
					}

					@Override
					public void onClosed() {
					}
				});
			} catch (IOException e) {
				serial = null;
				log("ERROR: " + e.getMessage());
			}
		});
	}

	private void closeSerial() {
		if (serial == null)
			return;
		serial.close();
		serial = null;
		serialBtn.setText("シリアル接続");
		log("--- シリアル切断 ---");
	}

	private void sendLine() {
		String s = sendEdit.getText().toString();
		if (serial == null) {
			log("シリアル未接続");
			return;
		}
		try {
			serial.write((s + "\r\n").getBytes(StandardCharsets.UTF_8));
			appendLog("> " + s + "\n");
			sendEdit.setText("");
		} catch (IOException e) {
			log("ERROR: " + e.getMessage());
		}
	}

	/* ---------------- log ---------------- */

	private void appendLog(String s) {
		logText.append(s);
		if (logText.length() > 100000)
			logText.setText(logText.getText().subSequence(logText.length() - 50000, logText.length()));
		scroll.post(() -> scroll.fullScroll(ScrollView.FOCUS_DOWN));
	}

	@Override
	public void log(String s) {
		ui.post(() -> appendLog(s + "\n"));
	}

	@Override
	public void progress(int done, int total) {
		ui.post(() -> {
			bar.setMax(total);
			bar.setProgress(done);
		});
	}
}
