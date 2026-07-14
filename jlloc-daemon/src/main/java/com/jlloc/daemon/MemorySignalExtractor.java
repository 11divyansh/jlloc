package com.jlloc.daemon;

import java.util.List;

/**
 * Coordinates all three signal extraction layers and assembles the
 * result into a MemorySignal for DiagnosisEngine.
 *
 * Layer 1: JVM signals via JmxConnector (heap, GC, Metaspace, direct buffers)
 * Layer 2: OS signals via OsMemorySignalExtractor (RSS, swap rate, page faults)
 * Layer 3: Container signals via ContainerSignalExtractor (cgroup limits)
 *
 * This is the only class that knows about all three extraction sources.
 * DiagnosisEngine only ever sees the finished MemorySignal.
 */
public class MemorySignalExtractor {

    private static final int MIN_SAMPLES = 6;
    private static final int RECENT_WINDOW = 60;

    private final HeapGrowthAnalyzer growthAnalyzer = new HeapGrowthAnalyzer();
    private final OsMemorySignalExtractor osExtractor = new OsMemorySignalExtractor();
    private final ContainerSignalExtractor containerExtractor = new ContainerSignalExtractor();

    /**
     * Must be called exactly once per HeapMonitor.pollAll() cycle,
     * BEFORE extract() is called for any individual PID. Refreshes
     * the system-wide OS signals (swap rate, I/O wait) that are
     * shared across every process on the machine, these must be
     * read once per cycle, not once per PID, or their rate
     * computation is corrupted (see OsMemorySignalExtractor javadoc).
     */
    public void beginCycle() {
        osExtractor.readSystemWide();
    }

    public MemorySignal extract(HeapTimeline timeline, JmxConnector jmxConnector,
                                long pid) {
        List<HeapSample> samples = timeline.recent(RECENT_WINDOW);

        if (samples.size() < MIN_SAMPLES) {
            return MemorySignal.insufficient(samples.size());
        }

        HeapSample latest = samples.get(samples.size() - 1);

        // JVM signals
        double heapUsedRatio = latest.maxBytes() > 0
                ? (double) latest.usedBytes() / latest.maxBytes() : 0.0;

        GcPressureCalculator.GcPressure pressure = GcPressureCalculator.calculate(samples);
        double gcTimeRatio = pressure.percentOfWallClock() / 100.0;

        double windowSeconds = java.time.Duration.between(samples.get(0).timestamp(), latest.timestamp()).toMillis() / 1000.0;
        double gcFrequency = windowSeconds > 0 ? pressure.collectionsInWindow() / windowSeconds : 0.0;

        List<HeapGrowthAnalyzer.PostGcFloor> floors = growthAnalyzer.extractFloors(samples);
        double floorSlope = growthAnalyzer.computeFloorSlopeBytesPerSecond(floors);
        double allocationRate = growthAnalyzer.computeAllocationRateBytesPerSecond(samples);

        // Off-heap via JMX
        long metaspaceUsed = -1, metaspaceCommitted = -1;
        long directUsed = -1, directCapacity = -1;
        long codeCacheUsed = -1;
        try {
            JmxConnector.OffHeapStats offHeap = jmxConnector.readOffHeapStats();
            metaspaceUsed      = offHeap.metaspaceUsedBytes();
            metaspaceCommitted = offHeap.metaspaceCommittedBytes();
            directUsed         = offHeap.directBufferUsedBytes();
            directCapacity     = offHeap.directBufferCapacityBytes();
            codeCacheUsed      = offHeap.codeCacheUsedBytes();
        } catch (Exception ignored) {
            // off-heap read failed, continue with UNAVAILABLE values
        }

        // OS signals
        // Per-PID read only, system-wide portion was already refreshed
        // once for this whole cycle by beginCycle() above.
        OsMemorySignalExtractor.OsSignals os = osExtractor.read(pid);

        // Container signals
        ContainerSignalExtractor.ContainerSignals container = containerExtractor.read();
        double containerPressure = container.computePressure(os.rssBytes());

        return new MemorySignal(
                heapUsedRatio,
                gcTimeRatio,
                gcFrequency,
                floorSlope,
                allocationRate,
                metaspaceUsed,
                metaspaceCommitted,
                directUsed,
                directCapacity,
                codeCacheUsed,
                os.rssBytes(),
                os.swapInRateBytesPerSecond(),
                os.swapOutRateBytesPerSecond(),
                os.majorPageFaultsPerSecond(),
                os.ioWaitPercent(),
                container.memoryLimitBytes(),
                containerPressure,
                samples.size(),
                latest.maxBytes()
        );
    }

    /** Forwards to the OS extractor so per-PID rate state doesn't leak. */
    public void forgetPid(long pid) {
        osExtractor.forgetPid(pid);
    }
}