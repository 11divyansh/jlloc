package com.jlloc.daemon;

import java.time.Instant;

/**
 * The verdict produced by HeapTrendTracker for one JVM process.
 * Populated once HeapTrendTracker is built (next phase). Until then,
 * ProcessRepository holds a null here and callers check for it.
 *
 * verdict:  what's happening right now
 * reason:   plain-language explanation shown by `jlloc explain`
 * since:    when this verdict first became true (how long has this
 *           been happening? matters for distinguishing a brief spike
 *           from a sustained trend)
 */
public record DiagnosisResult(
        Verdict verdict,
        String reason,
        Instant since
) {
    public enum Verdict {
        /**
         * Heap usage is stable, GC is keeping up, no concerning trend.
         */
        HEALTHY,

        /**
         * Heap is high but GC is successfully reclaiming memory
         * this looks like legitimate load, not a leak. The sawtooth
         * pattern (heap rises, GC drops it back near baseline) is
         * present. May still warrant a larger -Xmx if pressure is
         * sustained, but it's not a leak.
         */
        LOAD_PRESSURE,

        /**
         * The heap floor after each GC cycle is itself rising over
         * time, GC is running but reclaiming less each cycle.
         * Classic leak signature. `jlloc dump <service>` now is
         * the right call before this becomes a full OOM.
         */
        LEAK_SUSPECTED,

        /**
         * GC overhead is above the danger threshold (typically >80%
         * of CPU time spent in GC, recovering almost nothing). OOM
         * is imminent jlloc should alert immediately and offer
         * to either dump or CRaC-restart with more heap right now.
         */
        CRITICAL
    }
}
