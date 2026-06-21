package com.jlloc.daemon;

import com.sun.tools.attach.VirtualMachineDescriptor;

import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Watches the machine for JVM processes starting and stopping.
 */
public class JvmProcessWatcher {

    private static final long POLL_INTERVAL_SECONDS = 2;

    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "jlloc-process-watcher");
        t.setDaemon(true);
        return t;
    });

    // PID -> info we've already seen, so we can diff against the next poll
    private final Map<Long, DetectedJvm> knownJvms = new HashMap<>();

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

    /**
     * One discovery cycle: list every attachable JVM right now,
     * compare against what we knew last time, fire callbacks for
     * anything that changed.
     */
    void pollOnce() {
        Set<Long> currentPids = new HashSet<>();

        for (VirtualMachineDescriptor descriptor : JmxConnector.listAttachableJvms()) {
            long pid;
            try {
                pid = Long.parseLong(descriptor.id());
            } catch (NumberFormatException e) {
                // Some platforms report non-numeric VM identifiers for
                // certain embedded/attach scenarios; skipping those rather
                // than crash the whole watch loop over one weird entry.
                continue;
            }

            // Don't report ourselves, the daemon is itself a JVM and
            // would otherwise show up in its own process list.
            if (pid == ProcessHandle.current().pid()) {
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
     * displayName here is whatever VirtualMachine.list() reports,
     * usually the main class or jar name, e.g.
     * "org.springframework.boot.loader.launch.JarLauncher" or
     * "/path/to/order-service.jar".
     */
    public record DetectedJvm(long pid, String displayName, Instant detectedAt) {}

    public static void main(String[] args) throws InterruptedException {
        JvmProcessWatcher watcher = new JvmProcessWatcher();

        watcher.onJvmStarted(jvm -> System.out.printf("[+] JVM started  pid=%-8d %s%n", jvm.pid(), jvm.displayName()));
        watcher.onJvmStopped(jvm -> System.out.printf("[-] JVM stopped  pid=%-8d %s%n", jvm.pid(), jvm.displayName()));

        System.out.println("Watching for JVM start/stop... (Ctrl+C to exit)");
        watcher.start();

        Thread.currentThread().join();
    }
}