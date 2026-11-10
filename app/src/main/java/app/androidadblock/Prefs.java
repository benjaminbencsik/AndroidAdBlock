package app.androidadblock;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.Set;
import java.util.TreeSet;

final class Prefs {
    private Prefs() {}

    static final String[] DNS_NAMES = {"Cloudflare (1.1.1.1)", "Google (8.8.8.8)", "Quad9 (9.9.9.9)"};
    static final String[][] DNS_SERVERS = {
            {"1.1.1.1", "1.0.0.1"},
            {"8.8.8.8", "8.8.4.4"},
            {"9.9.9.9", "149.112.112.112"},
    };

    static SharedPreferences get(Context c) {
        return c.getSharedPreferences("androidadblock", Context.MODE_PRIVATE);
    }

    static int dns(Context c) {
        int i = get(c).getInt("dns", 0);
        return i >= 0 && i < DNS_SERVERS.length ? i : 0;
    }

    static Set<String> domains(Context c, String key) {
        Set<String> s = get(c).getStringSet(key, null);
        return s == null ? new TreeSet<String>() : new TreeSet<>(s);
    }

    static void setDomains(Context c, String key, Set<String> v) {
        get(c).edit().putStringSet(key, new TreeSet<>(v)).apply();
    }
}
