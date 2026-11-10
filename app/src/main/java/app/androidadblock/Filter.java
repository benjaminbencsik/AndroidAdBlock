package app.androidadblock;

import android.content.Context;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Collections;
import java.util.Set;

/** The live blocking decision: user rules first, then the downloaded blocklist. */
final class Filter {
    private Filter() {}

    static final String ALLOW = "allow";
    static final String DENY = "deny";

    private static volatile Blocklist list = new Blocklist();
    private static volatile Set<String> allow = Collections.emptySet();
    private static volatile Set<String> deny = Collections.emptySet();

    static boolean isBlocked(String name) {
        if (Blocklist.matches(allow, name)) return false;
        if (Blocklist.matches(deny, name)) return true;
        return list.contains(name);
    }

    static int size() {
        return list.domains.size();
    }

    static File file(Context c) {
        return new File(c.getFilesDir(), "blocklist.txt");
    }

    static void loadRules(Context c) {
        allow = Prefs.domains(c, ALLOW);
        deny = Prefs.domains(c, DENY);
    }

    /** Loads the downloaded blocklist, or the small bundled one if nothing was downloaded yet. */
    static synchronized void load(Context c) {
        loadRules(c);
        Blocklist b = new Blocklist();
        File f = file(c);
        try {
            InputStream in = f.exists() ? new FileInputStream(f) : c.getAssets().open("builtin.txt");
            try {
                b.read(in);
            } finally {
                in.close();
            }
        } catch (IOException e) {
            // Keep whatever was read; an empty list simply blocks nothing.
        }
        list = b;
    }
}
