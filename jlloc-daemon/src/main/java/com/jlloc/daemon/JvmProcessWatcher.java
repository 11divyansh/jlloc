package com.jlloc.daemon;

import com.sun.tools.attach.VirtualMachineDescriptor;

import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Watches the machine for JVM processes starting and stopping.
 */
public class JvmProcessWatcher {

    private static final long POLL_INTERVAL_SECONDS = 2;
    // Process names that are jlloc's own tooling talking to itself over
    // the socket - short-lived, not a real monitoring target. Without
    // this, every `jlloc status`/`explain` invocation shows up as a
    // spurious start/stop blip in the JVM list.
    private static final Set<String> SELF_PROCESS_MARKERS = Set.of(
            "CliMain",
            ":jlloc-cli:run"
    );
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "jlloc-process-watcher");
        t.setDaemon(true);
        return t;
    });

    // ConcurrentHashMap, not HashMap: this gets read by the CLI thread
    // and the budget engine thread while the watcher thread is still
    // writing to it. A plain HashMap under concurrent read/write is a
    // real correctness bug waiting to happen (lost updates, infinite
    // loops during resize) - cheap to get right now, expensive to
    // debug later once three threads are touching it in production use.
    private final Map<Long, DetectedJvm> knownJvms = new ConcurrentHashMap<>();

    private Consumer<DetectedJvm> onJvmStarted = jvm -> {};
    private Consumer<DetectedJvm> onJvmStopped = jvm -> {};

    /**
     * Registers a callback fired the moment a new JVM is detected.
     * This is where ProcessFingerprinter + JmxConnector get wired in
     * later: "new JVM appeared -> identify it -> start watching its heap".
     */
    public JvmProcessWatcher onJvmStarted(Consumer<DetectedJvm> callback) {
        this.onJvmStarted = callback;
        return this;
    }

    /**
     * Registers a callback fired the moment a previously-seen JVM
     * disappears. This is where the budget engine will be told
     * "this PID's memory is now free, redistribute it".
     */
    public JvmProcessWatcher onJvmStopped(Consumer<DetectedJvm> callback) {
        this.onJvmStopped = callback;
        return this;
    }

    /**
     * Starts the polling loop on a background daemon thread.
     * Safe to call once; the daemon process will keep running this
     * for its entire lifetime.
     */
    public void start() {
        scheduler.scheduleAtFixedRate(this::pollOnce, 0, POLL_INTERVAL_SECONDS, TimeUnit.SECONDS);
    }

    public void stop() {
        scheduler.shutdownNow();
    }

    private static boolean isSelfProcess(String displayName) {
        if (displayName == null) return false;
        return SELF_PROCESS_MARKERS.stream().anyMatch(displayName::contains);
    }
    /**
     * One discovery cycle: list every attachable JVM right now,
     * compare against what we knew last time, fire callbacks for
     * anything that changed.
     *
     * package-private (not private) so a unit test can call this
     * directly without waiting on the real 2-second scheduler.
     */
    void pollOnce() {
        Set<Long> currentPids = new HashSet<>();

        for (VirtualMachineDescriptor descriptor : JmxConnector.listAttachableJvms()) {
            long pid;
            try {
                pid = Long.parseLong(descriptor.id());
            } catch (NumberFormatException e) {
                // Some platforms report non-numeric VM identifiers for
                // certain embedded/attach scenarios; skip those rather
                // than crash the whole watch loop over one weird entry.
                continue;
            }

            // Don't report ourselves — the daemon is itself a JVM and
            // would otherwise show up in its own process list, which
            // is noise nobody wants in `jlloc status`.
            if (pid == ProcessHandle.current().pid()) {
                continue;
            }

            // Skip jlloc's own CLI client processes — same reasoning as
            // excluding our own PID above, just by name instead of PID.
            if (isSelfProcess(descriptor.displayName())) {
                continue;
            }

            currentPids.add(pid);

            if (!knownJvms.containsKey(pid)) {
                DetectedJvm jvm = new DetectedJvm(pid, descriptor.displayName(), Instant.now());
                knownJvms.put(pid, jvm);
                onJvmStarted.accept(jvm);
            }
        }

        // Anything we knew about last cycle but isn't in currentPids
        // anymore has exited since our last poll.
        var iterator = knownJvms.entrySet().iterator();
        while (iterator.hasNext()) {
            var entry = iterator.next();
            if (!currentPids.contains(entry.getKey())) {
                onJvmStopped.accept(entry.getValue());
                iterator.remove();
            }
        }
    }

    /**
     * Minimal identity of a JVM process, before any classification.
     * displayName here is whatever VirtualMachine.list() reports —
     * usually the main class or jar name, e.g.
     * "org.springframework.boot.loader.launch.JarLauncher" or
     * "/path/to/order-service.jar". ProcessFingerprinter turns this
     * raw string into a real classification.
     */
    public record DetectedJvm(long pid, String displayName, Instant detectedAt) { }
}