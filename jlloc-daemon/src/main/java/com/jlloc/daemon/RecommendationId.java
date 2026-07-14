package com.jlloc.daemon;

/**
 * The finite set of recommendations jlloc can make.
 *
 * This exists as a typed enum rather than free-form strings for three
 * reasons:
 *
 * 1. Exhaustiveness, the compiler tells you when you've forgotten to
 *    handle a recommendation in the CLI formatter or automation engine.
 *    Adding a new RecommendationId without handling it is a compile error.
 *
 * 2. Separation of identity from text, RecommendationEngine owns the
 *    advice text. DiagnosisEngine emits an ID. The CLI formats it.
 *    A future VSCode extension or web UI can display the same ID
 *    differently without touching diagnosis logic.
 *
 * 3. Automation gating, AutomationEngine (future) only acts on specific
 *    IDs it trusts. RESTART_WITH_MORE_HEAP can be automated. INSPECT_CACHE
 *    cannot. The ID makes that distinction explicit and testable.
 *
 * The list is intentionally small. The Reddit thread showed that even
 * across hundreds of JVM engineers, the actual actions they take are
 * a short list. These cover the real cases.
 */
public enum RecommendationId {

    /** Everything looks fine. No action needed. */
    NOTHING_REQUIRED,

    /**
     * Take a heap dump now for offline analysis.
     * Triggered by: LEAK diagnosis.
     * Tool: jlloc dump <service>, then Eclipse MAT or HeapHero.
     */
    TAKE_HEAP_DUMP,

    /**
     * Increase -Xmx. Service is under legitimate load and heap is
     * consistently near the limit but GC is keeping up.
     * Triggered by: LOAD diagnosis at WARNING severity.
     */
    INCREASE_XMX,

    /**
     * Decrease -Xmx to leave room for off-heap allocations.
     * Triggered by: HOST_MEMORY_PRESSURE where heap is oversized relative
     * to the container limit, starving Metaspace/direct buffers.
     * This is the counter-intuitive recommendation "your heap is too big,
     * not too small" that jlloc is uniquely positioned to make because
     * it can see both heap AND total RSS.
     */
    DECREASE_XMX,

    /**
     * Increase the container/pod memory limit, not -Xmx.
     * Triggered by: HOST_MEMORY_PRESSURE where RSS is near the cgroup
     * limit but the off-heap components (Metaspace, direct buffers) are
     * the problem, not heap.
     * This is the recommendation the Kubernetes engineer asked for
     * "increase container memory not -Xmx."
     */
    INCREASE_CONTAINER_MEMORY,

    /**
     * Reduce the number of JVMs running on this host.
     * Triggered by: HOST_MEMORY_PRESSURE on local dev machines where
     * cumulative JVM footprint (the "many JVMs together" problem) exceeds
     * available RAM.
     */
    REDUCE_POD_DENSITY,

    /**
     * Scale horizontally add more pods/instances, don't resize this one.
     * Triggered by: LOAD diagnosis where the service is correctly sized
     * but traffic volume genuinely requires more capacity.
     */
    SCALE_HORIZONTALLY,

    /**
     * Enable JVM Native Memory Tracking for deeper visibility.
     * Triggered by: UNKNOWN diagnosis where RSS is growing but heap,
     * Metaspace, and direct buffer signals don't explain it.
     * Command: -XX:NativeMemoryTracking=summary, then jcmd <pid> VM.native_memory
     */
    ENABLE_NMT,

    /**
     * Review GC configuration. Current collector may be suboptimal
     * for this workload's allocation pattern.
     * Triggered by: elevated GC pressure with no clear heap trend.
     */
    REVIEW_GC_CONFIGURATION,

    /**
     * Profile allocation sites to find what's allocating so heavily.
     * Triggered by: LOAD with very high allocation rate — the load is
     * real but the allocation pattern may be improvable.
     */
    PROFILE_ALLOCATIONS,

    /**
     * Inspect unbounded caches. A rising post-GC floor with low
     * allocation rate often means a cache is growing without eviction.
     */
    INSPECT_CACHE,

    /**
     * jlloc cannot explain the current memory behavior with available
     * signals. More data collection is needed.
     * Triggered by: UNKNOWN diagnosis.
     */
    COLLECT_MORE_SIGNALS,

    /**
     * Swap thrashing detected. Take the pod out of service rotation
     * immediately before Kubernetes kills it.
     * Triggered by: HOST_MEMORY_PRESSURE / CRITICAL with swap rate signals.
     * This is the swap-death prevention recommendation.
     */
    EMERGENCY_REDUCE_FOOTPRINT;

    /**
     * A generic, signal-free one-liner for contexts that only have the
     * ID and not the full Recommendation (e.g. DaemonMain's console
     * output, which doesn't have MemorySignal on hand). For the real,
     * number-specific advice, use RecommendationEngine.recommend().
     */
    public String shortDescription() {
        return switch (this) {
            case NOTHING_REQUIRED          -> "No action needed.";
            case TAKE_HEAP_DUMP            -> "Take a heap dump.";
            case INCREASE_XMX              -> "Consider increasing -Xmx.";
            case DECREASE_XMX              -> "Consider decreasing -Xmx to leave room for off-heap.";
            case INCREASE_CONTAINER_MEMORY -> "Increase container memory limit.";
            case REDUCE_POD_DENSITY        -> "Reduce number of JVMs running on this host.";
            case SCALE_HORIZONTALLY        -> "Scale horizontally instead of increasing heap.";
            case ENABLE_NMT                -> "Enable Native Memory Tracking for deeper visibility.";
            case REVIEW_GC_CONFIGURATION   -> "Review GC configuration.";
            case PROFILE_ALLOCATIONS       -> "Profile allocation sites.";
            case INSPECT_CACHE             -> "Inspect for an unbounded cache.";
            case COLLECT_MORE_SIGNALS      -> "Insufficient signal to explain this — collecting more data.";
            case EMERGENCY_REDUCE_FOOTPRINT-> "EMERGENCY: reduce JVM memory footprint now.";
        };
    }
}