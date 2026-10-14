package jp.paijp.wioflash;

import android.app.Activity;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * Writes a .bin to Wio LTE (STM32F4) in DFU mode over USB OTG.
 * Put the board in DFU mode: hold BOOT, press RST, release BOOT.
 */
public class MainActivity extends Activity implements DfuSe.Log {
	private static final String ACTION_PERM = "jp.paijp.wioflash.USB_PERMISSION";
	private static final int REQ_FILE = 1;

	private final Handler ui = new Handler(Looper.getMainLooper());
	private UsbManager usb;
	private EditText addrEdit;
	private CheckBox verifyBox, startBox;
	private TextView fileText, logText;
	private ProgressBar bar;
	private ScrollView scroll;
	private Button flashBtn;
	private Uri fileUri;
	private boolean busy;
	private Updater updater;

	private final BroadcastReceiver permReceiver = new BroadcastReceiver() {
		@Override
		public void onReceive(Context c, Intent i) {
			UsbDevice d = i.getParcelableExtra(UsbManager.EXTRA_DEVICE);
			if (i.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false) && d != null)
				startFlash(d);
			else
				log("USB permission denied");
		}
	};

	@Override
	protected void onCreate(Bundle b) {
		super.onCreate(b);
		usb = (UsbManager) getSystemService(USB_SERVICE);

		LinearLayout root = new LinearLayout(this);
		root.setOrientation(LinearLayout.VERTICAL);
		int pad = (int) (12 * getResources().getDisplayMetrics().density);
		root.setPadding(pad, pad, pad, pad);

		TextView help = new TextView(this);
		help.setText("Wio LTE: BOOTを押しながらRSTを押して離し、DFUモードにしてからOTGで接続します。\n"
				+ "ブートローダー: 0x08000000 / アプリ: 0x08010000");
		root.addView(help);

		Button pick = new Button(this);
		pick.setText(".bin を選択");
		pick.setOnClickListener(v -> {
			Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
			i.addCategory(Intent.CATEGORY_OPENABLE);
			i.setType("*/*");
			startActivityForResult(i, REQ_FILE);
		});
		root.addView(pick);

		fileText = new TextView(this);
		fileText.setText("(未選択)");
		root.addView(fileText);

		LinearLayout row = new LinearLayout(this);
		row.setOrientation(LinearLayout.HORIZONTAL);
		addrEdit = new EditText(this);
		addrEdit.setInputType(InputType.TYPE_CLASS_TEXT);
		addrEdit.setText("08000000");
		addrEdit.setLayoutParams(new LinearLayout.LayoutParams(0,
				ViewGroup.LayoutParams.WRAP_CONTENT, 1));
		row.addView(addrEdit);
		Button boot = new Button(this);
		boot.setText("Boot");
		boot.setOnClickListener(v -> addrEdit.setText("08000000"));
		row.addView(boot);
		Button app = new Button(this);
		app.setText("App");
		app.setOnClickListener(v -> addrEdit.setText("08010000"));
		row.addView(app);
		root.addView(row);

		verifyBox = new CheckBox(this);
		verifyBox.setText("ベリファイ");
		verifyBox.setChecked(true);
		root.addView(verifyBox);
		startBox = new CheckBox(this);
		startBox.setText("書き込み後に起動 (0x08000000)");
		startBox.setChecked(true);
		root.addView(startBox);

		flashBtn = new Button(this);
		flashBtn.setText("書き込み");
		flashBtn.setOnClickListener(v -> requestFlash());
		root.addView(flashBtn);

		bar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
		root.addView(bar);

		scroll = new ScrollView(this);
		logText = new TextView(this);
		logText.setTextIsSelectable(true);
		logText.setTypeface(android.graphics.Typeface.MONOSPACE);
		scroll.addView(logText);
		root.addView(scroll, new LinearLayout.LayoutParams(
				ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
		setContentView(root);

		IntentFilter f = new IntentFilter(ACTION_PERM);
		if (Build.VERSION.SDK_INT >= 33)
			registerReceiver(permReceiver, f, Context.RECEIVER_NOT_EXPORTED);
		else
			registerReceiver(permReceiver, f);

		updater = new Updater(this, this);
		updater.register();
		updater.check();
	}

	@Override
	protected void onDestroy() {
		unregisterReceiver(permReceiver);
		updater.unregister();
		super.onDestroy();
	}

	@Override
	protected void onActivityResult(int req, int res, Intent data) {
		super.onActivityResult(req, res, data);
		if (req == REQ_FILE && res == RESULT_OK && data != null) {
			fileUri = data.getData();
			fileText.setText(fileUri.getLastPathSegment());
		}
	}

	private UsbDevice findDevice() {
		for (UsbDevice d : usb.getDeviceList().values())
			if (d.getVendorId() == DfuSe.VID && d.getProductId() == DfuSe.PID)
				return d;
		return null;
	}

	private void requestFlash() {
		if (busy)
			return;
		if (fileUri == null) {
			log("ファイルを選択してください");
			return;
		}
		UsbDevice d = findDevice();
		if (d == null) {
			log("DFUデバイス (0483:df11) が見つかりません");
			return;
		}
		if (usb.hasPermission(d)) {
			startFlash(d);
			return;
		}
		Intent i = new Intent(ACTION_PERM).setPackage(getPackageName());
		int fl = Build.VERSION.SDK_INT >= 31 ? PendingIntent.FLAG_MUTABLE : 0;
		usb.requestPermission(d, PendingIntent.getBroadcast(this, 0, i, fl));
	}

	private void startFlash(UsbDevice d) {
		final long addr;
		try {
			addr = Long.parseLong(addrEdit.getText().toString().trim().replaceFirst("^0[xX]", ""), 16);
		} catch (NumberFormatException e) {
			log("アドレスが不正です");
			return;
		}
		final boolean verify = verifyBox.isChecked();
		final boolean start = startBox.isChecked();
		busy = true;
		flashBtn.setEnabled(false);
		new Thread(() -> {
			DfuSe dfu = null;
			try {
				byte[] img = readAll(fileUri);
				log(String.format("%d bytes -> %08x", img.length, addr));
				dfu = new DfuSe(usb, d, this);
				dfu.write(addr, img, verify);
				if (start)
					dfu.leave(0x08000000L);
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

	private byte[] readAll(Uri u) throws IOException {
		try (InputStream in = getContentResolver().openInputStream(u)) {
			if (in == null)
				throw new IOException("cannot open file");
			ByteArrayOutputStream out = new ByteArrayOutputStream();
			byte[] b = new byte[8192];
			int n;
			while ((n = in.read(b)) > 0)
				out.write(b, 0, n);
			return out.toByteArray();
		}
	}

	@Override
	public void log(String s) {
		ui.post(() -> {
			logText.append(s + "\n");
			scroll.post(() -> scroll.fullScroll(ScrollView.FOCUS_DOWN));
		});
	}

	@Override
	public void progress(int done, int total) {
		ui.post(() -> {
			bar.setMax(total);
			bar.setProgress(done);
		});
	}
}
