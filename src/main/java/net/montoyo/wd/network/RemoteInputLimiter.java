package net.montoyo.wd.network;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Caps how many forwarded input events one player can push to a display owner per second. */
final class RemoteInputLimiter {
    private static final int MAX_EVENTS_PER_SECOND = 120;
    private static final Map<UUID, long[]> WINDOWS = new ConcurrentHashMap<>();

    private RemoteInputLimiter() {}

    static boolean allow(UUID player) {
        long now = System.currentTimeMillis();
        long[] window = WINDOWS.computeIfAbsent(player, ignored -> new long[]{now, 0});
        synchronized (window) {
            if (now - window[0] >= 1000) {
                window[0] = now;
                window[1] = 0;
            }
            return ++window[1] <= MAX_EVENTS_PER_SECOND;
        }
    }
}
