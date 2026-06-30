package com.jlloc.daemon;

/**
 * Persistent, per-app historical memory record, stored to
 * ~/.jlloc/profiles/<appName>.json between runs.
 *
 * Populated by the ProfileStore in Phase 6. Until then,
 * ProcessRepository holds a null here and BudgetEngine uses
 * hardcoded defaults.
 *
 * Once populated, BudgetEngine reads this at startup to give
 * known-heavy apps a head start on their allocation rather than
 * everyone starting from the same default and self-correcting.
 *
 * avgHeapBytes:       rolling average heap usage observed across
 *                     all sessions for this app name
 * peakHeapBytes:      the highest heap usage ever observed
 * avgStartupSeconds:  how long this app typically takes to stabilize
 *                     after launch (useful for not diagnosing a leak
 *                     during normal startup warmup)
 * observedSessions:   how many times we've seen this app start,
 *                     for confidence weighting (1 session is a
 *                     weak signal, 50 sessions is strong)
 */
public record MemoryProfile(
        String appName,
        long avgHeapBytes,
        long peakHeapBytes,
        int avgStartupSeconds,
        int observedSessions
) {
    /**
     * A conservative default used when no real profile exists yet
     * for this app name. 256MB is intentionally modest — BudgetEngine
     * will expand from this if heap pressure is observed, and the
     * point of MemoryProfile is eventually to replace this guess
     * with a real observed number.
     */
    public static MemoryProfile defaultFor(String appName) {
        return new MemoryProfile(appName, 256L * 1024 * 1024, 256L * 1024 * 1024, 30, 0);
    }

    public boolean isFromRealObservation() {
        return observedSessions > 0;
    }
}