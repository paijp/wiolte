package jp.paijp.wioflash;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageInfo;
import android.content.pm.PackageInstaller;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * Checks rel/version.txt on GitHub at startup and, after the user
 * agrees, downloads rel/wioflash.apk and installs it over this app.
 */
public class Updater {
	private static final String BASE = "https://raw.githubusercontent.com/paijp/wiolte/main/rel/";
	private static final String ACTION_INSTALLED = "jp.paijp.wioflash.INSTALL_STATUS";

	private final Activity act;
	private final DfuSe.Log log;
	private boolean registered;

	private final BroadcastReceiver statusReceiver = new BroadcastReceiver() {
		@Override
		public void onReceive(Context c, Intent i) {
			int st = i.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE);
			if (st == PackageInstaller.STATUS_PENDING_USER_ACTION) {
				Intent confirm = i.getParcelableExtra(Intent.EXTRA_INTENT);
				if (confirm != null) {
					confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
					act.startActivity(confirm);
				}
			} else if (st != PackageInstaller.STATUS_SUCCESS) {
				log.log("update failed: " + i.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE));
			}
		}
	};

	public Updater(Activity act, DfuSe.Log log) {
		this.act = act;
		this.log = log;
	}

	public void register() {
		IntentFilter f = new IntentFilter(ACTION_INSTALLED);
		if (Build.VERSION.SDK_INT >= 33)
			act.registerReceiver(statusReceiver, f, Context.RECEIVER_NOT_EXPORTED);
		else
			act.registerReceiver(statusReceiver, f);
		registered = true;
	}

	public void unregister() {
		if (registered)
			act.unregisterReceiver(statusReceiver);
		registered = false;
	}

	private long currentVersion() {
		try {
			PackageInfo pi = act.getPackageManager().getPackageInfo(act.getPackageName(), 0);
			return Build.VERSION.SDK_INT >= 28 ? pi.getLongVersionCode() : pi.versionCode;
		} catch (Exception e) {
			return Long.MAX_VALUE;
		}
	}

	private static HttpURLConnection open(String url) throws IOException {
		HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
		c.setConnectTimeout(10000);
		c.setReadTimeout(30000);
		c.setUseCaches(false);
		if (c.getResponseCode() != 200)
			throw new IOException("HTTP " + c.getResponseCode() + " " + url);
		return c;
	}

	/** Runs in the background; shows a dialog when a newer build exists. */
	public void check() {
		new Thread(() -> {
			try {
				HttpURLConnection c = open(BASE + "version.txt?t=" + System.currentTimeMillis());
				byte[] b = new byte[32];
				int n;
				try (InputStream in = c.getInputStream()) {
					n = in.read(b);
				}
				long latest = Long.parseLong(new String(b, 0, Math.max(n, 0), "US-ASCII").trim());
				long cur = currentVersion();
				log.log("app version " + cur + ", latest " + latest);
				if (latest > cur)
					act.runOnUiThread(() -> ask(latest));
			} catch (Exception e) {
				log.log("update check failed: " + e.getMessage());
			}
		}).start();
	}

	private void ask(long latest) {
		if (act.isFinishing())
			return;
		new AlertDialog.Builder(act)
				.setTitle("更新があります")
				.setMessage("新しいバージョン (build " + latest + ") をインストールしますか？")
				.setPositiveButton("インストール", (d, w) -> start())
				.setNegativeButton("あとで", null)
				.show();
	}

	private void start() {
		if (Build.VERSION.SDK_INT >= 26 && !act.getPackageManager().canRequestPackageInstalls()) {
			log.log("「このアプリからのインストールを許可」をオンにしてから、アプリを再起動してください");
			act.startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
					Uri.parse("package:" + act.getPackageName())));
			return;
		}
		new Thread(() -> {
			try {
				log.log("downloading update...");
				install(open(BASE + "wioflash.apk?t=" + System.currentTimeMillis()));
			} catch (Exception e) {
				log.log("update failed: " + e.getMessage());
			}
		}).start();
	}

	private void install(HttpURLConnection c) throws IOException {
		PackageInstaller pi = act.getPackageManager().getPackageInstaller();
		PackageInstaller.SessionParams p =
				new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
		p.setAppPackageName(act.getPackageName());
		int id = pi.createSession(p);
		try (PackageInstaller.Session s = pi.openSession(id)) {
			try (InputStream in = c.getInputStream();
					OutputStream out = s.openWrite("wioflash.apk", 0, -1)) {
				byte[] b = new byte[65536];
				int n;
				while ((n = in.read(b)) > 0)
					out.write(b, 0, n);
				s.fsync(out);
			}
			Intent i = new Intent(ACTION_INSTALLED).setPackage(act.getPackageName());
			int fl = Build.VERSION.SDK_INT >= 31 ? PendingIntent.FLAG_MUTABLE : 0;
			s.commit(PendingIntent.getBroadcast(act, 1, i, fl | PendingIntent.FLAG_UPDATE_CURRENT).getIntentSender());
		}
		log.log("installing update...");
	}
}
