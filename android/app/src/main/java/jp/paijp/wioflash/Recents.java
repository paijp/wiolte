package jp.paijp.wioflash;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * The programs opened before, most recent first.
 *
 * The loop this app is for is "change one thing, build, flash, watch" -
 * the same file over and over - so the chooser is only needed the first
 * time.  An entry is only useful if its URI still works after a restart,
 * which needs a persistable permission; some providers hand out one-shot
 * URIs, so an entry can go stale and forget() drops it.
 */
public class Recents {
	public static class Entry {
		public final Uri uri;
		public final String name;

		Entry(Uri uri, String name) {
			this.uri = uri;
			this.name = name;
		}
	}

	private static final String KEY = "programs";
	private static final int LIMIT = 8;
	private final SharedPreferences prefs;

	public Recents(Context c) {
		prefs = c.getSharedPreferences("recents", Context.MODE_PRIVATE);
	}

	public List<Entry> list() {
		List<Entry> out = new ArrayList<>();
		try {
			JSONArray a = new JSONArray(prefs.getString(KEY, "[]"));
			for (int i = 0; i < a.length(); i++) {
				JSONObject o = a.optJSONObject(i);
				if (o == null || o.optString("uri").isEmpty())
					continue;
				out.add(new Entry(Uri.parse(o.optString("uri")), o.optString("name", o.optString("uri"))));
			}
		} catch (Exception e) {
			/* corrupt or older format: start again */
		}
		return out;
	}

	public void remember(Uri uri, String name) {
		List<Entry> l = new ArrayList<>();
		l.add(new Entry(uri, name));
		for (Entry e : list())
			if (!e.uri.equals(uri))
				l.add(e);
		save(l);
	}

	public void forget(Uri uri) {
		List<Entry> l = new ArrayList<>();
		for (Entry e : list())
			if (!e.uri.equals(uri))
				l.add(e);
		save(l);
	}

	private void save(List<Entry> l) {
		JSONArray a = new JSONArray();
		try {
			for (int i = 0; i < l.size() && i < LIMIT; i++)
				a.put(new JSONObject().put("uri", l.get(i).uri.toString()).put("name", l.get(i).name));
		} catch (Exception e) {
			/* JSONObject.put only throws on bad numbers */
		}
		prefs.edit().putString(KEY, a.toString()).apply();
	}

	/** Keep the right to reopen this URI after a restart, if offered. */
	public static void persist(Context c, Uri uri) {
		try {
			c.getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
		} catch (SecurityException e) {
			/* a one-shot URI; it still works now */
		}
	}
}
