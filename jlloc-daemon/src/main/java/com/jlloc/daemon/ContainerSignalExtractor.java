package com.jlloc.daemon;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Reads container-level memory signals from the Linux cgroup filesystem.
 *
 * WHY THIS EXISTS:
 * A JVM's heap can be at 65% while the pod is about to be OOM-killed.
 * This happens when off-heap allocations (Metaspace, direct buffers,
 * native memory, thread stacks) push total RSS over the cgroup memory
 * limit. Kubernetes then kills the pod with no JVM-side error at all —
 * the JVM never even gets a chance to throw OutOfMemoryError, because
 * the kernel kills the process from outside.
 *
 *
 * By reading the cgroup limit and comparing it to the process's RSS
 * (from OsMemorySignalExtractor), jlloc can compute:
 *   containerPressure = RSS / cgroupLimit
 * and alert when this approaches 1.0, regardless of heap%.
 *
 * This is also why the Kubernetes VPA fails for JVM workloads, it can
 * see the container limit, but it cannot correlate that limit with the
 * JVM-internal breakdown of how much is heap vs Metaspace vs direct
 * buffers vs native memory. jlloc, seeing both JMX and cgroup signals
 * together, can.
 *
 * Supports both cgroup v1 and v2. Falls back gracefully when not
 * running inside a container, or when cgroup is not mounted (e.g.
 * local development on Windows/Mac, or bare-metal Linux without
 * containerization).
 */
public class ContainerSignalExtractor {

    // cgroup v1 - older, still common (e.g. Docker Desktop, some
    // managed Kubernetes distros)
    private static final Path CGROUP_V1_MEMORY_LIMIT = Path.of("/sys/fs/cgroup/memory/memory.limit_in_bytes");

    // cgroup v2 — newer unified hierarchy, default on recent Linux
    // distros and Kubernetes clusters
    private static final Path CGROUP_V2_MEMORY_MAX = Path.of("/sys/fs/cgroup/memory.max");

    // cgroup v1 reports this sentinel value when no limit is set
    // effectively Long.MAX_VALUE rounded to a page boundary. Treated
    // the same as "not in a container" since there's no ceiling to
    // measure pressure against.
    private static final long NO_LIMIT = Long.MAX_VALUE;

    /**
     * Reads the current container memory limit, if any.
     * Returns ContainerSignals.notInContainer() if not running in a
     * container, if cgroup is not mounted, or if no limit is set.
     */
    public ContainerSignals read() {
        try {
            long limitBytes = readCgroupLimit();
            if (limitBytes <= 0 || limitBytes == NO_LIMIT) {
                return ContainerSignals.notInContainer();
            }
            return new ContainerSignals(limitBytes, true);
        } catch (Exception e) {
            // cgroup read failed for any reason (not on Linux, file
            // doesn't exist, permissions), degrade to "not in a
            // container" rather than propagating. One unreadable
            // cgroup file shouldn't break the whole poll cycle.
            return ContainerSignals.notInContainer();
        }
    }

    private long readCgroupLimit() throws IOException {
        // Try cgroup v2 first, this is the default on newer Linux
        // kernels and most current Kubernetes clusters
        if (Files.exists(CGROUP_V2_MEMORY_MAX)) {
            String content = Files.readString(CGROUP_V2_MEMORY_MAX).trim();
            if ("max".equals(content)) return NO_LIMIT; // v2's "no limit" sentinel
            return Long.parseLong(content);
        }

        // Fall back to cgroup v1
        if (Files.exists(CGROUP_V1_MEMORY_LIMIT)) {
            return Long.parseLong(Files.readString(CGROUP_V1_MEMORY_LIMIT).trim());
        }

        // Neither path exists — not in a container, or cgroup isn't
        // mounted at the expected location (non-Linux, unusual setup)
        return -1;
    }

    public record ContainerSignals(long memoryLimitBytes, boolean inContainer) {
        public static ContainerSignals notInContainer() {
            return new ContainerSignals(-1L, false);
        }

        /**
         * Computes container memory pressure = RSS / limit.
         * Returns -1.0 (MemorySignal.UNAVAILABLE) if not in a
         * container, if the limit is unreadable, or if RSS itself
         * is unavailable (e.g. OsMemorySignalExtractor couldn't
         * read /proc/<pid>/status).
         *
         * When this approaches 1.0, the pod is about to be
         * OOM-killed by the kernel, regardless of what heapUsedRatio
         * says, since heap is only one piece of total RSS.
         */
        public double computePressure(long rssBytes) {
            if (!inContainer || memoryLimitBytes <= 0 || rssBytes < 0) {
                return -1.0;
            }
            return (double) rssBytes / memoryLimitBytes;
        }
    }
}
