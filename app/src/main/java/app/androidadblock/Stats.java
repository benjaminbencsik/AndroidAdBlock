package app.androidadblock;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/** Counters and a short recent-lookups log, shared between the service and the UI. */
final class Stats {
    private Stats() {}

    static final class Entry {
        final String name;
        final boolean blocked;
        Entry(String name, boolean blocked) {
            this.name = name;
            this.blocked = blocked;
        }
    }

    static final AtomicLong total = new AtomicLong();
    static final AtomicLong blocked = new AtomicLong();
    static volatile int version;

    private static final int MAX = 60;
    private static final ArrayDeque<Entry> log = new ArrayDeque<>();

    static synchronized void add(String name, boolean wasBlocked) {
        total.incrementAndGet();
        if (wasBlocked) blocked.incrementAndGet();
        Entry first = log.peekFirst();
        if (first != null && first.name.equals(name) && first.blocked == wasBlocked) return;
        log.addFirst(new Entry(name, wasBlocked));
        if (log.size() > MAX) log.removeLast();
        version++;
    }

    static synchronized List<Entry> snapshot() {
        return new ArrayList<>(log);
    }
}
