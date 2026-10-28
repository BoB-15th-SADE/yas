package com.yas.commonlibrary.logging;

import java.util.HashMap;
import java.util.Map;

/**
 * Counts outbound attempts per (method + URI) within the current inbound request thread.
 * The first attempt is 0. Outside an inbound request (no scope opened by {@link RequestIdFilter})
 * every attempt is reported as 0.
 */
public final class OutboundRetryCounter {

    private static final ThreadLocal<Map<String, Integer>> ATTEMPTS = new ThreadLocal<>();

    private OutboundRetryCounter() {
    }

    /** Starts a fresh counting scope for the current thread. */
    public static void reset() {
        ATTEMPTS.set(new HashMap<>());
    }

    /** Ends the counting scope for the current thread. */
    public static void clear() {
        ATTEMPTS.remove();
    }

    static int next(String method, String uri) {
        Map<String, Integer> attempts = ATTEMPTS.get();
        if (attempts == null) {
            return 0;
        }
        return attempts.merge(method + " " + uri, 1, Integer::sum) - 1;
    }
}
