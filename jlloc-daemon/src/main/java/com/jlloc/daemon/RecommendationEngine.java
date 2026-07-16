package com.jlloc.daemon;

/**
 * Maps a diagnosis to a typed RecommendationId and human-readable advice.
 */
public class RecommendationEngine {

    public record Recommendation(
            RecommendationId id,
            String shortAdvice,
            String fullAdvice,
            String command        // null if no CLI command applies
    ) {}

    /**
     * Produces the appropriate recommendation for a given diagnosis.
     * Takes the full MemorySignal so advice can be specific
     * (e.g. "increase by 512MB" not just "increase heap").
     */
    public Recommendation recommend(DiagnosisResult result, MemorySignal signal,
                                    String appName) {

        RecommendationId id = result.recommendationId();
        if (id == null) {
            // still collecting samples, nothing to recommend yet.
            return new Recommendation(null, null, null, null);
        }
        return switch (id) {
            case NOTHING_REQUIRED -> new Recommendation(
                    id,
                    "No action needed.",
                    "Memory usage is within normal parameters. No immediate action required.",
                    null
            );

            case TAKE_HEAP_DUMP -> recommendTakeHeapDump(result, appName);

            case INCREASE_XMX -> recommendIncreaseXmx(result, signal, appName);

            case DECREASE_XMX -> recommendDecreaseXmx(signal, appName);

            case INCREASE_CONTAINER_MEMORY -> recommendIncreaseContainerMemory(result, signal);

            case REDUCE_POD_DENSITY -> new Recommendation(
                    id,
                    "Reduce number of JVMs on this host.",
                    "Total memory pressure is high. If running multiple JVMs on one machine "
                            + "(local dev or dense deployment), reducing the number of concurrent "
                            + "JVMs is the most effective relief.",
                    null
            );

            case SCALE_HORIZONTALLY -> new Recommendation(
                    id,
                    "High load — consider scaling out.",
                    "Heap is high under genuine load with a high allocation rate. "
                            + "Increasing -Xmx may help short-term but the load itself "
                            + "suggests this service needs more instances, not more heap per instance.",
                    null
            );

            case EMERGENCY_REDUCE_FOOTPRINT -> new Recommendation(
                    id,
                    "Swap thrashing — pod will freeze imminently.",
                    "Sustained swap-in/out rate detected with elevated major page faults. "
                            + "JVM threads are blocking on disk I/O instead of executing. "
                            + "The pod will appear hung to Kubernetes health probes within minutes.\n\n"
                            + "Immediate action: reduce total JVM memory footprint to free physical RAM.\n"
                            + "Do NOT increase -Xmx — more heap makes swap thrashing worse.\n"
                            + "Options:\n"
                            + "  1. jlloc fix " + appName + "  (restarts with reduced heap via CRaC)\n"
                            + "  2. Increase container memory limit\n"
                            + "  3. Reduce the number of JVMs running on this host",
                    "jlloc fix " + appName
            );

            case COLLECT_MORE_SIGNALS -> new Recommendation(
                    id,
                    "Enable NMT for deeper visibility.",
                    "jlloc cannot explain the current memory behavior with available signals. "
                            + "RSS may be growing from JNI, native libraries, or off-heap allocations "
                            + "that JMX cannot see. Enable JVM Native Memory Tracking for details:\n"
                            + "  -XX:NativeMemoryTracking=summary\n"
                            + "Then: jcmd <pid> VM.native_memory",
                    null
            );
            case WARMING_UP -> new Recommendation(
                    id,
                    "Still starting up, wait.",
                    "This process is within its normal startup window. Floor is rising because of "
                            + "classloading and cache warmup, not a leak. No action needed yet "
                            + "re-check after the warmup window closes.",
                    null
            );
            case ENABLE_NMT, REVIEW_GC_CONFIGURATION, PROFILE_ALLOCATIONS, INSPECT_CACHE ->
                    new Recommendation(
                            id,
                            "See jlloc documentation.",
                            "This recommendation type exists but isn't wired into automatic "
                                    + "diagnosis yet.",
                            null
                    );
        };
    }

    private Recommendation recommendTakeHeapDump(DiagnosisResult result, String appName) {
        if (result.severity() == DiagnosisResult.Severity.CRITICAL) {
            return new Recommendation(
                    RecommendationId.TAKE_HEAP_DUMP,
                    "OOM imminent — take heap dump NOW.",
                    "GC is consuming most of CPU time and cannot reclaim memory. "
                            + "OutOfMemoryError is seconds to minutes away. "
                            + "Take a heap dump immediately to capture the state before crash.",
                    "jlloc dump " + appName
            );
        }
        return new Recommendation(
                RecommendationId.TAKE_HEAP_DUMP,
                "Take a heap dump now.",
                "Post-GC floor is rising — objects are being retained across GC cycles. "
                        + "Do NOT increase -Xmx (it delays the crash, doesn't fix the leak). "
                        + "Take a heap dump and inspect retained objects in Eclipse MAT "
                        + "or HeapHero to find the retention path.",
                "jlloc dump " + appName
        );
    }

    private Recommendation recommendIncreaseXmx(DiagnosisResult result, MemorySignal signal, String appName) {
        if (result.severity() == DiagnosisResult.Severity.CRITICAL) {
            return new Recommendation(
                    RecommendationId.INCREASE_XMX,
                    "OOM imminent — restart with more heap.",
                    "Heap is critically full and GC cannot keep up. "
                            + "Restart this service with a larger -Xmx immediately.",
                    "jlloc fix " + appName
            );
        }

        String advice = "GC is keeping up (sawtooth pattern) but heap usage is high under load. "
                + "Consider increasing -Xmx to give more headroom.";

        if (signal.isHeapMaxAvailable() && signal.heapUsedRatio() > 0) {
            long currentMaxMb = signal.heapMaxBytes() / (1024 * 1024);
            // Target: usage settles around ~70% of new max, with 30% margin
            long suggestedMb = Math.round((signal.heapUsedRatio() / 0.70) * currentMaxMb);
            advice += String.format("%n  Suggested: -Xmx%dm  (current: -Xmx%dm, usage at %.0f%% of max)",
                    suggestedMb, currentMaxMb, signal.heapUsedRatio() * 100);
        }

        return new Recommendation(
                RecommendationId.INCREASE_XMX,
                "High load — consider increasing -Xmx.",
                advice,
                "jlloc fix " + appName
        );
    }

    private Recommendation recommendDecreaseXmx(MemorySignal signal, String appName) {
        return new Recommendation(
                RecommendationId.DECREASE_XMX,
                "Decrease -Xmx to leave room for off-heap.",
                String.format(
                        "Total RSS is at %.0f%% of the container limit, "
                                + "but heap is only at %.0f%%. "
                                + "Off-heap allocations (Metaspace, direct buffers, JIT code cache, "
                                + "thread stacks) are consuming the remaining container memory.\n\n"
                                + "Counter-intuitive recommendation: DECREASE -Xmx to give "
                                + "off-heap components more room within the container limit. "
                                + "The JVM reserved too much for heap and starved off-heap.",
                        signal.isContainerSignalAvailable() ? signal.containerMemoryPressure() * 100 : 0.0,
                        signal.heapUsedRatio() * 100),
                "jlloc fix " + appName
        );
    }

    private Recommendation recommendIncreaseContainerMemory(DiagnosisResult result, MemorySignal signal) {
        double pct = signal.isContainerSignalAvailable() ? signal.containerMemoryPressure() * 100 : 0.0;
        if (result.severity() == DiagnosisResult.Severity.CRITICAL) {
            return new Recommendation(
                    RecommendationId.INCREASE_CONTAINER_MEMORY,
                    "Pod will be OOM-killed imminently.",
                    String.format(
                            "Total RSS is at %.0f%% of the container memory limit. "
                                    + "Kubernetes will OOM-kill this pod before the JVM throws OutOfMemoryError.\n\n"
                                    + "The heap may look healthy — this is an off-heap problem.\n"
                                    + "Action: increase the container memory limit, not -Xmx.",
                            pct),
                    null
            );
        }
        return new Recommendation(
                RecommendationId.INCREASE_CONTAINER_MEMORY,
                "Increase container memory limit.",
                String.format(
                        "Total RSS is at %.0f%% of the container memory limit. "
                                + "Increasing -Xmx won't help — the container itself needs more memory "
                                + "to accommodate heap + Metaspace + direct buffers + native allocations.",
                        pct),
                null
        );
    }
}