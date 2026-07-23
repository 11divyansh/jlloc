package com.jlloc.daemon;

import java.time.Duration;
import java.time.Instant;

/**
 * Accumulates observations for one JVM instance's lifetime, to be
 * merged into its persistent MemoryProfile when the process stops.
 */
public class ProfileSession {

    private final String appName;
    private final Instant startedAt;

    private long peakHeapUsedBytes = 0;
    private long stableHeapSum = 0;
    private int stableHeapSamples = 0;
    private Instant warmupEndedAt = null;

    public ProfileSession(String appName, Instant startedAt) {
        this.appName = appName;
        this.startedAt = startedAt;
    }

    /**
     * Called on every poll cycle for this PID with the latest signal
     * and diagnosis. Records peak always; records into the "stable"
     * average only once warmup has ended.
     */
    public void recordSample(MemorySignal signal, DiagnosisResult.Diagnosis diagnosis, Instant now) {
        if (!signal.hasSufficientData(DiagnosisEngine.MIN_SAMPLES) || !signal.isHeapMaxAvailable()) {
            // Nothing real to learn from yet MemorySignal.insufficient()
            // returns UNAVAILABLE (-1) sentinels for heapUsedRatio and
            // heapMaxBytes; multiplying those together produces a
            // meaningless small number that would otherwise silently
            // pollute the running average.
            return;
        }
        long heapUsedBytes = (long) (signal.heapUsedRatio() * signal.heapMaxBytes());
        peakHeapUsedBytes = Math.max(peakHeapUsedBytes, heapUsedBytes);

        // Treat both WARMUP and UNKNOWN as "not yet stable" — UNKNOWN
        // in the first real samples after MIN_SAMPLES is reached is
        // still noise, not a confirmed stable reading, and shouldn't
        // mark warmup as ended.
        boolean stillWarmingOrUncertain = diagnosis == DiagnosisResult.Diagnosis.WARMUP || diagnosis == DiagnosisResult.Diagnosis.UNKNOWN;
        if (!stillWarmingOrUncertain) {
            if (warmupEndedAt == null) {
                warmupEndedAt = now;
            }
            stableHeapSum += heapUsedBytes;
            stableHeapSamples++;
        }
    }

    /**
     * Merges this session's observations into the given prior profile,
     * producing an updated profile. Uses a running weighted average
     * across observedSessions so one anomalous session (e.g. an
     * unusually large one-off batch job run) doesn't overwrite months
     * of prior history in a single update.
     */
    public MemoryProfile mergeInto(MemoryProfile prior) {
        long sessionAvgHeap = stableHeapSamples > 0
                ? stableHeapSum / stableHeapSamples
                : peakHeapUsedBytes; // no stable samples ever recorded fall back to peak

        int startupSeconds = warmupEndedAt != null
                ? (int) Duration.between(startedAt, warmupEndedAt).toSeconds()
                : prior.avgStartupSeconds(); // never stabilized this session don't corrupt the average

        int priorSessions = prior.observedSessions();
        int newSessions = priorSessions + 1;

        long newAvgHeap = priorSessions == 0 ? sessionAvgHeap : (prior.avgHeapBytes() * priorSessions + sessionAvgHeap) / newSessions;

        int newAvgStartup = priorSessions == 0 ? startupSeconds : (prior.avgStartupSeconds() * priorSessions + startupSeconds) / newSessions;

        long newPeak = Math.max(prior.peakHeapBytes(), peakHeapUsedBytes);
        return new MemoryProfile(appName, newAvgHeap, newPeak, newAvgStartup, newSessions);
    }

    public String appName() { return appName; }
    public boolean hasStableSamples() { return stableHeapSamples > 0; }
}