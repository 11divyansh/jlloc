package com.jlloc.daemon;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Optional;

/**
 * Rolling in-memory history of HeapSamples for one JVM process.
 * A 30-minute window at 5-second polling
 * intervals means at most 360 samples per JVM, which is trivially
 * small. Beyond 30 minutes, samples are evicted automatically.
 *
 * The most important thing this class exposes is not the raw sample
 * list - it's the post-GC floor sequence. This is the sequence of
 * "lowest heap usage seen after each GC cycle" over time. This is
 * what distinguishes leak from load:
 *
 *   Load pattern (GC keeping up):
 *     post-GC floors: 800MB, 810MB, 790MB, 820MB, 800MB stable
 *
 *   Leak pattern (GC can't reclaim the leak):
 *     post-GC floors: 800MB, 900MB, 1.0GB, 1.1GB, 1.2GB rising
 *
 * The raw "current heap used" is nearly useless for this distinction
 * because it spikes naturally with load and drops after GC regardless
 * of whether a leak is present. The floor is what matters.
 */
public class HeapTimeline {

    private static final int MAX_SAMPLES = 360;
    private static final Duration MAX_AGE = Duration.ofMinutes(30);
    private final Deque<HeapSample> samples = new ArrayDeque<>(MAX_SAMPLES);

    public synchronized void add(HeapSample sample) {
        samples.addLast(sample);

        // Evict anything older than our window - we don't need it
        Instant cutoff = sample.timestamp().minus(MAX_AGE);
        while (!samples.isEmpty() && samples.peekFirst().timestamp().isBefore(cutoff)) {
            samples.pollFirst();
        }

        // Hard cap as a safety valve in case polling runs faster than
        // expected (e.g. during testing) and the time-based eviction
        // hasn't had a chance to run yet
        while (samples.size() > MAX_SAMPLES) {
            samples.pollFirst();
        }
    }

    /**
     * Returns the most recent N samples in chronological order.
     * Returns fewer than N if the history is shorter than that.
     */
    public synchronized List<HeapSample> recent(int n) {
        List<HeapSample> all = new ArrayList<>(samples);
        int from = Math.max(0, all.size() - n);
        return all.subList(from, all.size());
    }

    public synchronized List<HeapSample> all() {
        return new ArrayList<>(samples);
    }

    public synchronized Optional<HeapSample> latest() {
        return samples.isEmpty() ? Optional.empty() : Optional.of(samples.peekLast());
    }

    public synchronized int size() {
        return samples.size();
    }
}