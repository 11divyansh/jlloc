package com.jlloc.daemon;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;

/**
 * Reads OS-level memory signals for JVM processes from the Linux
 * /proc filesystem.
 *
 * WHY THIS EXISTS:
 * The swap-death failure mode described in the thread is completely
 * invisible to JVM-internal (JMX) monitoring. Heap can be healthy
 * while the pod is thrashing pages to disk and about to be killed.
 * These signals live in /proc, not JMX.
 *
 * TWO KINDS OF SIGNAL, READ TWO DIFFERENT WAYS:
 *
 *   Per-PID signals (RSS, major page faults) come from
 *   /proc/<pid>/status and /proc/<pid>/stat — genuinely different
 *   per process, read once per PID per cycle via read(pid).
 *
 *   System-wide signals (swap rate, I/O wait) come from
 *   /proc/vmstat and /proc/stat — the SAME value for every process
 *   on the machine. These must be read exactly ONCE per poll cycle,
 *   not once per PID.
 *
 *   Earlier version of this class read /proc/vmstat and /proc/stat
 *   inside the per-PID read() call and stored a single shared
 *   "previous sample" for delta computation. With N JVMs polled in
 *   one pollAll() cycle, this meant: PID 1 correctly computed a rate
 *   against ~5s-old data, then immediately overwrote the "previous"
 *   sample with now so PID 2 through PID N computed their swap
 *   rate against a previous sample that was only milliseconds old.
 *   That collapses the elapsedSec denominator toward zero, producing
 *   either wildly inflated or near-zero rates depending on scheduler
 *   timing, noise, not signal, for every PID after the first.
 *
 *   Fix: system-wide reads happen once via readSystemWide(), called
 *   by HeapMonitor at the top of pollAll(), before any per-PID reads.
 *   The result is cached and handed to every read(pid) call in that
 *   same cycle.
 *
 * PLATFORM NOTE:
 * /proc is Linux-specific. On Windows and Mac this extractor returns
 * UNAVAILABLE for all signals, the tool degrades gracefully rather
 * than failing. JVM-internal signals still work everywhere since
 * they come from JMX.
 *
 * Signal sources:
 *   RSS:           /proc/<pid>/status   (VmRSS line, in KB)          — per-PID
 *   Major faults:  /proc/<pid>/stat     (field 12, cumulative)       — per-PID
 *   Swap rate:     /proc/vmstat         (pswpin/pswpout, cumulative) — system-wide
 *   I/O wait:      /proc/stat           (cpu line field 5, cumulative) — system-wide
 */
public class OsMemorySignalExtractor {

    private static final boolean IS_LINUX = System.getProperty("os.name", "").toLowerCase().contains("linux");
    private static final long PAGE_SIZE_BYTES = 4096; // standard Linux page size

    // Per-PID previous sample for major-fault delta calculation
    private final Map<Long, OsPrevSample> previousSamples = new ConcurrentHashMap<>();

    // System-wide previous sample for swap rate and iowait deltas
    private volatile SystemPrevSample prevSystemSample = null;

    // Result of the current cycle's system-wide read. Set once per
    // pollAll() cycle by readSystemWide(), consumed by every
    // subsequent read(pid) call in that same cycle.
    private volatile SystemSignals currentSystemSignals = SystemSignals.unavailable();

    /**
     * Reads system-wide swap rate and I/O wait signals for this poll
     * cycle. Must be called exactly once per pollAll() cycle, BEFORE
     * any per-PID read(pid) calls those consume the cached result
     * from this call rather than re-reading /proc/vmstat themselves.
     */
    public void readSystemWide() {
        if (!IS_LINUX) {
            currentSystemSignals = SystemSignals.unavailable();
            return;
        }
        try {
            currentSystemSignals = doReadSystemWide();
        } catch (Exception e) {
            // /proc read failed degrade to unavailable rather than
            // propagating and killing the whole poll cycle
            currentSystemSignals = SystemSignals.unavailable();
        }
    }

    private SystemSignals doReadSystemWide() throws IOException {
        Instant now = Instant.now();

        // /proc/vmstat: pswpin (pages swapped in) + pswpout
        long pswpin = -1, pswpout = -1;
        Path vmstatPath = Path.of("/proc/vmstat");
        if (Files.exists(vmstatPath)) {
            for (String line : Files.readAllLines(vmstatPath)) {
                if (line.startsWith("pswpin ")) {
                    pswpin = parseLongValue(line);
                } else if (line.startsWith("pswpout ")) {
                    pswpout = parseLongValue(line);
                }
            }
        }

        // /proc/stat: cpu line user nice system idle iowait irq softirq steal
        long iowaitJiffies = -1, totalJiffies = -1;
        Path procStatPath = Path.of("/proc/stat");
        if (Files.exists(procStatPath)) {
            String firstLine = Files.readAllLines(procStatPath).get(0);
            String[] fields = firstLine.split("\\s+");
            if (fields.length >= 8) {
                try {
                    long user    = Long.parseLong(fields[1]);
                    long nice    = Long.parseLong(fields[2]);
                    long system  = Long.parseLong(fields[3]);
                    long idle    = Long.parseLong(fields[4]);
                    long iowait  = Long.parseLong(fields[5]);
                    long irq     = Long.parseLong(fields[6]);
                    long softirq = Long.parseLong(fields[7]);
                    iowaitJiffies = iowait;
                    totalJiffies  = user + nice + system + idle + iowait + irq + softirq;
                } catch (NumberFormatException ignored) {}
            }
        }

        // Compute rates from deltas against the last cycle's sample
        double swapInRate = MemorySignal.UNAVAILABLE;
        double swapOutRate = MemorySignal.UNAVAILABLE;
        double ioWaitPercent = MemorySignal.UNAVAILABLE;

        SystemPrevSample prevSys = this.prevSystemSample;
        if (prevSys != null) {
            double elapsedSec = (now.toEpochMilli() - prevSys.timestamp.toEpochMilli()) / 1000.0;
            // Guard: require a meaningful elapsed window. This class is
            // only ever called once per pollAll() cycle now, so this is
            // a sanity guard rather than the primary fix but it's
            // cheap insurance against a stray double-call.
            if (elapsedSec > 0.5) {
                if (pswpin >= 0 && prevSys.pswpin >= 0) {
                    swapInRate  = (pswpin  - prevSys.pswpin)  * PAGE_SIZE_BYTES / elapsedSec;
                    swapOutRate = (pswpout - prevSys.pswpout) * PAGE_SIZE_BYTES / elapsedSec;
                }
                if (iowaitJiffies >= 0 && totalJiffies >= 0 && prevSys.totalJiffies >= 0 && prevSys.iowaitJiffies >= 0) {
                    long totalDelta  = totalJiffies  - prevSys.totalJiffies;
                    long iowaitDelta = iowaitJiffies - prevSys.iowaitJiffies;
                    if (totalDelta > 0) {
                        ioWaitPercent = iowaitDelta * 100.0 / totalDelta;
                    }
                }
            }
        }

        this.prevSystemSample = new SystemPrevSample(now, pswpin, pswpout, iowaitJiffies, totalJiffies);
        return new SystemSignals(swapInRate, swapOutRate, ioWaitPercent);
    }

    /**
     * Reads current per-PID OS signals (RSS, major fault rate) and
     * combines them with this cycle's cached system-wide signals
     * (swap rate, I/O wait set by the readSystemWide() call at the
     * top of this poll cycle).
     *
     * Returns all UNAVAILABLE if not on Linux or if /proc is unreadable.
     */
    public OsSignals read(long pid) {
        if (!IS_LINUX) {
            return OsSignals.unavailable();
        }
        try {
            return readPerPid(pid);
        } catch (Exception e) {
            // /proc read failed (process exited, permissions, etc.)
            // Return unavailable rather than propagating, one failed
            // OS read shouldn't affect the rest of the monitoring loop
            return OsSignals.unavailable();
        }
    }

    private OsSignals readPerPid(long pid) throws IOException {
        Instant now = Instant.now();

        // /proc/<pid>/status: VmRSS
        long rssBytes = -1;
        Path statusPath = Path.of("/proc", String.valueOf(pid), "status");
        if (Files.exists(statusPath)) {
            for (String line : Files.readAllLines(statusPath)) {
                if (line.startsWith("VmRSS:")) {
                    rssBytes = parseKbLine(line) * 1024;
                    break;
                }
            }
        }

        // /proc/<pid>/stat: field 12 (majflt, cumulative)
        long majflt = -1;
        Path statPath = Path.of("/proc", String.valueOf(pid), "stat");
        if (Files.exists(statPath)) {
            String[] fields = Files.readString(statPath).split("\\s+");
            if (fields.length > 12) {
                try { majflt = Long.parseLong(fields[11]); }
                catch (NumberFormatException ignored) {}
            }
        }

        // Compute major-fault rate from delta against last sample
        double majfltRate = MemorySignal.UNAVAILABLE;
        OsPrevSample prev = previousSamples.get(pid);
        if (prev != null) {
            double elapsedSec = (now.toEpochMilli() - prev.timestamp.toEpochMilli()) / 1000.0;
            if (elapsedSec > 0 && majflt >= 0 && prev.majflt >= 0) {
                majfltRate = (majflt - prev.majflt) / elapsedSec;
            }
        }
        previousSamples.put(pid, new OsPrevSample(now, majflt));

        SystemSignals sys = currentSystemSignals;
        return new OsSignals(
                rssBytes, sys.swapInRateBytesPerSecond(), sys.swapOutRateBytesPerSecond(),
                majfltRate, sys.ioWaitPercent()
        );
    }

    /** Called when a PID stops being monitored, to avoid leaking entries. */
    public void forgetPid(long pid) {
        previousSamples.remove(pid);
    }

    private static long parseKbLine(String line) {
        // "VmRSS:  123456 kB" → 123456
        String[] parts = line.trim().split("\\s+");
        try { return Long.parseLong(parts[1]); }
        catch (Exception e) { return -1; }
    }

    private static long parseLongValue(String line) {
        // "pswpin 12345" → 12345
        String[] parts = line.trim().split("\\s+");
        try { return Long.parseLong(parts[1]); }
        catch (Exception e) { return -1; }
    }

    private record OsPrevSample(Instant timestamp, long majflt) {}
    private record SystemPrevSample(
            Instant timestamp, long pswpin, long pswpout,
            long iowaitJiffies, long totalJiffies) {}

    /** System-wide signals cached for the duration of one poll cycle. */
    private record SystemSignals(
            double swapInRateBytesPerSecond,
            double swapOutRateBytesPerSecond,
            double ioWaitPercent
    ) {
        static SystemSignals unavailable() {
            return new SystemSignals(-1.0, -1.0, -1.0);
        }
    }

    /**
     * OS-level signals for one process at one point in time.
     * RSS and major faults are genuinely per-process; swap rate and
     * I/O wait are the machine-wide values for this poll cycle.
     * All doubles use MemorySignal.UNAVAILABLE = -1.0 when not readable.
     */
    public record OsSignals(
            long rssBytes,
            double swapInRateBytesPerSecond,
            double swapOutRateBytesPerSecond,
            double majorPageFaultsPerSecond,
            double ioWaitPercent
    ) {
        public static OsSignals unavailable() {
            return new OsSignals(-1L, -1.0, -1.0, -1.0, -1.0);
        }

        public boolean isThrashingIndicated() {
            if (swapInRateBytesPerSecond < 0 || majorPageFaultsPerSecond < 0) {
                return false;
            }
            //Look at sustained swap IN + OUT rate
            // correlating with elevated major faults.
            return (swapInRateBytesPerSecond > 1_000_000 || swapOutRateBytesPerSecond > 1_000_000) && majorPageFaultsPerSecond > 10;
        }
    }
}