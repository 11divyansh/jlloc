package com.jlloc.daemon;

/**
 * The complete extracted feature set for one JVM process at one point
 * in time. Three layers, each answering a different question.
 *
 * The swap-death failure mode described in the thread is the clearest
 * example: heap was at 65%, GC was healthy, JVM-internal metrics
 * showed nothing alarming but the pod was dying because off-heap
 * allocations pushed total RSS over the container limit, triggering
 * Linux swap thrashing that made the JVM appear hung. JVM-only
 * monitoring would have reported everything fine right up until the
 * pod was killed.
 *
 * Layer 1 - JVM signals:
 *   What the JVM knows about itself. Heap, GC, Metaspace, direct
 *   buffers, code cache, allocation rate, post-GC floor trend.
 *   Source: JMX MBeans (already implemented in JmxConnector).
 *
 * Layer 2 - OS signals:
 *   What Linux knows about the process. RSS, swap rate, page faults,
 *   I/O wait. These are the signals that reveal swap death before the
 *   JVM itself reports anything wrong.
 *   The key insight: don't monitor swap USED (that's
 *   a static number), monitor swap IN/OUT RATE (si/so in vmstat).
 *   Sustained non-zero si/so + elevated majflt + high iowait =
 *   thrashing. That correlation is the actual signal.
 *   Source: /proc/<pid>/status (VmSwap), /proc/vmstat (pswpin/pswpout),
 *   /proc/<pid>/stat (majflt), /proc/stat (iowait).
 *
 * Layer 3 - Container signals:
 *   What the container runtime knows about limits. cgroup memory limit
 *   vs current RSS, memory.pressure events, OOM kill score. These are
 *   the signals that reveal "heap is fine but the pod is about to be
 *   killed because total JVM memory exceeds the container limit."
 *   Source: /sys/fs/cgroup/memory/ (Linux cgroup v1/v2).
 *   Only populated when running inside a container.
 *
 * SENTINEL VALUE:
 *   UNAVAILABLE = -1.0 for doubles, -1 for longs.
 *   Extractors set this when a signal isn't readable (e.g. not on
 *   Linux, not in a container, insufficient permissions). Scorers
 *   must check and skip, not treat as real values.
 */
public record MemorySignal(

        // Heap usage as fraction of max (0.0–1.0)
        double heapUsedRatio,

        // Fraction of wall-clock time spent in GC (0.0–1.0)
        double gcTimeRatio,

        // GC collections per second in observation window
        double gcFrequencyPerSecond,

        // Slope of post-GC floor sequence in bytes/second
        // positive = floor rising (leak signal)
        // near-zero = stable (load or healthy)
        double postGcFloorSlopePerSecond,

        // Bytes allocated per second (derived from heap delta between GCs)
        double allocationRateBytesPerSecond,

        // Metaspace used bytes (-1 if unavailable)
        long metaspaceUsedBytes,

        // Metaspace committed bytes (what the JVM has actually reserved)
        long metaspaceCommittedBytes,

        // Direct buffer pool used bytes (Netty, NIO ByteBuffer.allocateDirect)
        // This is the most common off-heap source in Spring/ES/Kafka stacks
        long directBufferUsedBytes,

        // Direct buffer pool capacity bytes
        long directBufferCapacityBytes,

        // JIT code cache used bytes
        long codeCacheUsedBytes,

        // RSS (Resident Set Size) — total physical memory this process
        // is currently using. If RSS approaches the container limit,
        // the pod is about to be killed regardless of heap health.
        // Source: /proc/<pid>/status VmRSS
        long rssBytes,

        // SWAP RATE, bytes swapped in per second from disk to RAM.
        // This is the primary swap-death early-warning signal.
        // KEY INSIGHT from the thread: don't monitor swap used (static),
        // monitor swap rate (dynamic). A system with 7GB swap used can
        // be perfectly healthy. A system with 50MB swap used but 500MB/s
        // swap rate is thrashing and about to freeze.
        // Source: /proc/vmstat pswpin, delta between samples
        double swapInRateBytesPerSecond,

        // Bytes swapped out per second (RAM to disk)
        // Source: /proc/vmstat pswpout, delta between samples
        double swapOutRateBytesPerSecond,

        // Major page faults per second for this specific PID.
        // Major fault = page not in RAM, must be loaded from disk.
        // Sustained majflt + sustained swap rate = thrashing.
        // Source: /proc/<pid>/stat field 12 (majflt), delta between samples
        double majorPageFaultsPerSecond,

        // System I/O wait percentage (0.0–100.0)
        // CPU cycles waiting on disk I/O — elevated iowait confirms
        // that swap paging is the bottleneck, not CPU or GC.
        // Source: /proc/stat cpu line, iowait field, delta between samples
        double ioWaitPercent,

        // Container memory limit in bytes (-1 if not in a container or
        // if cgroup is not accessible)
        // Source: /sys/fs/cgroup/memory/memory.limit_in_bytes (v1)
        //         /sys/fs/cgroup/memory.max (v2)
        long containerMemoryLimitBytes,

        // How close RSS is to the container limit (0.0–1.0)
        // This is what the VPA cannot see but jlloc can:
        // containerMemoryPressure = rssBytes / containerMemoryLimitBytes
        // When this approaches 1.0, the pod will be OOM-killed regardless
        // of what the JVM's own heap metrics say.
        double containerMemoryPressure,

        // Number of samples this signal was computed from
        int sampleCount,

        long heapMaxBytes // -Xmx in bytes, from the latest HeapSample

) {
    public static final double UNAVAILABLE = -1.0;

    public static MemorySignal insufficient(int sampleCount) {
        return new MemorySignal(
                UNAVAILABLE, UNAVAILABLE, UNAVAILABLE, UNAVAILABLE, UNAVAILABLE,
                -1L, -1L, -1L, -1L, -1L,
                -1L,
                UNAVAILABLE, UNAVAILABLE, UNAVAILABLE, UNAVAILABLE,
                -1L, UNAVAILABLE,
                sampleCount, -1L
        );
    }

    /**
     * Backward-compatible convenience constructor for tests and older
     * call sites that only care about the core heap/GC trend signals.
     */
    public MemorySignal(
            double heapUsedRatio,
            double gcTimeRatio,
            double gcFrequencyPerSecond,
            double postGcFloorSlopePerSecond,
            double allocationRateBytesPerSecond,
            int sampleCount) {
        this(
                heapUsedRatio,
                gcTimeRatio,
                gcFrequencyPerSecond,
                postGcFloorSlopePerSecond,
                allocationRateBytesPerSecond,
                -1L,
                -1L,
                -1L,
                -1L,
                -1L,
                -1L,
                UNAVAILABLE,
                UNAVAILABLE,
                UNAVAILABLE,
                UNAVAILABLE,
                -1L,
                UNAVAILABLE,
                sampleCount,
                -1L
        );
    }

    public boolean hasSufficientData(int minSamples) {
        return sampleCount >= minSamples;
    }

    public boolean isGcRatioAvailable()           { return gcTimeRatio >= 0; }
    public boolean isFloorSlopeAvailable()        { return postGcFloorSlopePerSecond > UNAVAILABLE; }
    public boolean isAllocationRateAvailable()    { return allocationRateBytesPerSecond >= 0; }
    public boolean isMetaspaceAvailable()         { return metaspaceUsedBytes >= 0; }
    public boolean isDirectBufferAvailable()      { return directBufferUsedBytes >= 0; }
    public boolean isRssAvailable()               { return rssBytes >= 0; }
    public boolean isSwapRateAvailable()          { return swapInRateBytesPerSecond >= 0; }
    public boolean isMajorFaultAvailable()        { return majorPageFaultsPerSecond >= 0; }
    public boolean isContainerSignalAvailable()   { return containerMemoryLimitBytes > 0; }
    public boolean isHeapMaxAvailable() { return heapMaxBytes > 0; }

    /**
     * Whether swap thrashing is indicated by the rate signals.
     * Uses the diagnostic posture from the Reddit thread:
     * sustained swap-in + swap-out rate + elevated major faults
     * = thrashing, regardless of how much swap space remains.
     */
    public boolean isThrashingIndicated() {
        if (!isSwapRateAvailable() || !isMajorFaultAvailable()) return false;
        return (swapInRateBytesPerSecond > 1_000_000   // >1 MB/s swap in
                || swapOutRateBytesPerSecond > 1_000_000) // >1 MB/s swap out
                && majorPageFaultsPerSecond > 10;          // AND faults are happening
    }
}
