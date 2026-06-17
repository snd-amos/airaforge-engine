package com.myengine;

import java.util.*;

// Lightweight per-frame zone profiler. Use with try-with-resources for the
// natural scope match:
//
//     try (var z = Profiler.zone("Shadow pass")) {
//         ...
//     }
//
// Zones are stack-based (push on open, pop on close). Each zone tracks a
// rolling window of recent samples; the UI panel reads from getZones().
// Single-threaded — the engine renders on the main thread, no locking needed.
class Profiler {
    private static final int WINDOW = 120;
    // First N frames are discarded — scene init, JIT, texture uploads and
    // ImGui's first paint each cost 50–800 ms and would otherwise pin the
    // `max` column at a startup outlier the user has to manually reset.
    private static final int WARMUP_FRAMES = 30;
    private static int framesSinceStart = 0;

    static class ZoneStats {
        final long[] samples = new long[WINDOW];
        int sampleCount = 0;
        int writeIdx = 0;
        long maxNs = 0;
        long lastNs = 0;
        int callsThisFrame = 0;
        int lastFrameCalls = 0;
    }

    private static final LinkedHashMap<String, ZoneStats> zones = new LinkedHashMap<>();
    private static final Deque<long[]> activeStack = new ArrayDeque<>(); // [startNs, zoneIndex]
    private static final List<String> activeNames = new ArrayList<>();   // parallel to activeStack
    private static long frameStartNs = 0;
    private static long lastFrameNs = 0;
    private static boolean enabled = true;

    static Zone zone(String name) {
        if (!enabled) return Zone.NOOP;
        activeStack.push(new long[]{ System.nanoTime() });
        activeNames.add(name);
        return new Zone();
    }

    private static void endZone() {
        if (activeStack.isEmpty()) return;
        long[] frame = activeStack.pop();
        String name = activeNames.remove(activeNames.size() - 1);
        long elapsed = System.nanoTime() - frame[0];

        // Discard the warmup window so startup-only spikes never enter the
        // running average or the `max` column.
        if (framesSinceStart <= WARMUP_FRAMES) return;

        ZoneStats s = zones.computeIfAbsent(name, k -> new ZoneStats());
        s.samples[s.writeIdx] = elapsed;
        s.writeIdx = (s.writeIdx + 1) % WINDOW;
        if (s.sampleCount < WINDOW) s.sampleCount++;
        if (elapsed > s.maxNs) s.maxNs = elapsed;
        s.lastNs = elapsed;
        s.callsThisFrame++;
    }

    static void frameStart() {
        if (!enabled) return;
        framesSinceStart++;
        frameStartNs = System.nanoTime();
        for (ZoneStats s : zones.values()) {
            s.lastFrameCalls = s.callsThisFrame;
            s.callsThisFrame = 0;
        }
    }

    static boolean isWarmingUp() { return framesSinceStart <= WARMUP_FRAMES; }
    static int warmupRemaining() { return Math.max(0, WARMUP_FRAMES - framesSinceStart); }

    static void frameEnd() {
        if (!enabled) return;
        lastFrameNs = System.nanoTime() - frameStartNs;
    }

    static Map<String, ZoneStats> getZones() { return zones; }
    static long getLastFrameNs() { return lastFrameNs; }
    static boolean isEnabled() { return enabled; }
    static void setEnabled(boolean v) { enabled = v; }

    static double avgMs(ZoneStats s) {
        if (s.sampleCount == 0) return 0;
        long total = 0;
        for (int i = 0; i < s.sampleCount; i++) total += s.samples[i];
        return total / (double) s.sampleCount / 1_000_000.0;
    }

    static double maxMs(ZoneStats s) { return s.maxNs / 1_000_000.0; }
    static double lastMs(ZoneStats s) { return s.lastNs / 1_000_000.0; }

    static void resetMax() {
        for (ZoneStats s : zones.values()) s.maxNs = 0;
    }

    // AutoCloseable wrapper so callers can use try-with-resources.
    static class Zone implements AutoCloseable {
        static final Zone NOOP = new Zone() {
            @Override public void close() {}
        };
        @Override public void close() { endZone(); }
    }
}
