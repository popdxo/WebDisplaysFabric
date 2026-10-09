package net.montoyo.wd.client;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** Keeps Hybrid signaling diagnostics to one line per key, since viewers repeat `join` every few seconds. */
final class HybridSignalLog {
    private static final Set<String> SEEN = ConcurrentHashMap.newKeySet();

    private HybridSignalLog() {}

    static boolean once(String key) {
        if (SEEN.size() > 1000) SEEN.clear();
        return SEEN.add(key);
    }
}
