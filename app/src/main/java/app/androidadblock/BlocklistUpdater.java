package app.androidadblock;

import android.content.Context;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStreamWriter;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/** Downloads the public blocklists and merges them into one local file. */
final class BlocklistUpdater {
    private BlocklistUpdater() {}

    static final String[] SOURCES = {
            "https://raw.githubusercontent.com/StevenBlack/hosts/master/hosts",
            "https://pgl.yoyo.org/adservers/serverlist.php?hostformat=hosts&showintro=0&mimetype=plaintext",
    };

    static final long MAX_AGE_MS = 3L * 24 * 60 * 60 * 1000;

    static boolean isStale(Context c) {
        long at = Prefs.get(c).getLong("listUpdated", 0);
        return !Filter.file(c).exists() || System.currentTimeMillis() - at > MAX_AGE_MS;
    }

    /** Returns the number of domains now in the list. Throws if no source could be fetched. */
    static synchronized int update(Context c) throws IOException {
        Blocklist b = new Blocklist();
        IOException last = null;
        int ok = 0;
        for (String src : SOURCES) {
            try {
                fetch(src, b);
                ok++;
            } catch (IOException e) {
                last = e;
            }
        }
        if (ok == 0 || b.domains.isEmpty()) {
            throw last != null ? last : new IOException("Blocklists were empty");
        }
        File tmp = new File(c.getFilesDir(), "blocklist.tmp");
        BufferedWriter w = new BufferedWriter(
                new OutputStreamWriter(new FileOutputStream(tmp), StandardCharsets.UTF_8));
        try {
            for (String d : b.domains) {
                w.write(d);
                w.write('\n');
            }
        } finally {
            w.close();
        }
        if (!tmp.renameTo(Filter.file(c))) throw new IOException("Could not save the blocklist");
        Prefs.get(c).edit().putLong("listUpdated", System.currentTimeMillis()).apply();
        Filter.load(c);
        return b.domains.size();
    }

    private static void fetch(String url, Blocklist into) throws IOException {
        HttpURLConnection con = (HttpURLConnection) new URL(url).openConnection();
        con.setConnectTimeout(15000);
        con.setReadTimeout(30000);
        try {
            if (con.getResponseCode() != 200) throw new IOException("HTTP " + con.getResponseCode());
            InputStream in = con.getInputStream();
            try {
                into.read(in);
            } finally {
                in.close();
            }
        } finally {
            con.disconnect();
        }
    }
}
