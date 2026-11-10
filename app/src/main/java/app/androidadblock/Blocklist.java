package app.androidadblock;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;

/** A set of blocked domains. A name is blocked if it or any parent domain is in the set. */
final class Blocklist {
    final HashSet<String> domains = new HashSet<>();

    boolean contains(String name) {
        return matches(domains, name);
    }

    static boolean matches(Set<String> set, String name) {
        if (set.isEmpty()) return false;
        String n = name;
        while (true) {
            if (set.contains(n)) return true;
            int dot = n.indexOf('.');
            if (dot < 0) return false;
            n = n.substring(dot + 1);
            if (n.indexOf('.') < 0) return false;        // never match a bare TLD
        }
    }

    /** Reads hosts-file, plain-domain or adblock ("||domain^") formatted lines. */
    void read(InputStream in) throws IOException {
        BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
        String line;
        while ((line = r.readLine()) != null) {
            String d = parseLine(line);
            if (d != null) domains.add(d);
        }
    }

    static String parseLine(String line) {
        int hash = line.indexOf('#');
        if (hash >= 0) line = line.substring(0, hash);
        line = line.trim();
        if (line.isEmpty() || line.charAt(0) == '!' || line.charAt(0) == '[') return null;
        if (line.startsWith("||")) {
            if (!line.endsWith("^")) return null;
            line = line.substring(2, line.length() - 1);
        } else {
            String[] parts = line.split("\\s+");
            if (parts.length == 1) {
                line = parts[0];
            } else if (parts.length == 2 && (parts[0].equals("0.0.0.0") || parts[0].equals("127.0.0.1")
                    || parts[0].equals("::") || parts[0].equals("::1"))) {
                line = parts[1];
            } else {
                return null;
            }
        }
        return cleanDomain(line);
    }

    /** Lower-cases and validates a domain; returns null if it is not a plausible one. */
    static String cleanDomain(String s) {
        s = s.trim().toLowerCase();
        if (s.endsWith(".")) s = s.substring(0, s.length() - 1);
        if (s.length() < 4 || s.length() > 253 || s.indexOf('.') < 0) return null;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            boolean ok = (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '.' || c == '-' || c == '_';
            if (!ok) return null;
        }
        if (s.equals("localhost.localdomain") || s.equals("0.0.0.0")) return null;
        return s;
    }
}
