package com.jlloc.daemon;

/**
 * The daemon's real entry point. Wires together everything built: JvmProcessWatcher detects PIDs, ProcessRepository owns the
 * merged picture of each one, ProcessFingerprinter classifies them,
 * and JmxConnector.probeCapabilities() records what each PID actually
 * supports rather than assuming every JVM is fully reachable.
 */
public class DaemonMain {

    public static void main(String[] args) throws InterruptedException {
        System.out.println("[jlloc-daemon] starting up...");

        ProcessRepository repository = new ProcessRepository();
        ProcessFingerprinter fingerprinter = new ProcessFingerprinter();
        JvmProcessWatcher watcher = new JvmProcessWatcher();

        watcher.onJvmStarted(jvm -> {
            repository.register(jvm);
            ProcessFingerprinter.Classification classification = fingerprinter.classifyDeep(jvm);

            // Don't track jlloc's own processes
            if ("jlloc-self".equals(classification.category())) {
                repository.remove(jvm.pid());
                return;
            }

            repository.updateClassification(jvm.pid(), classification);
            JvmCapabilities capabilities = JmxConnector.probeCapabilities(jvm.pid());
            repository.updateCapabilities(jvm.pid(), capabilities);
            printRecord(repository.get(jvm.pid()).orElseThrow(), "+");
        });

        watcher.onJvmStopped(jvm -> {
            System.out.printf("[-] PID %-8d stopped%n", jvm.pid());
            repository.remove(jvm.pid());
        });

        watcher.start();
        System.out.println("[jlloc-daemon] watching for JVMs (Ctrl+C to exit)");
        Thread.currentThread().join();
    }

    private static void printRecord(ProcessRepository.ProcessRecord record, String marker) {
        String category = record.classification() != null ? record.classification().category() : "?";
        String appName = record.classification() != null ? record.classification().appName() : "?";
        int weight = record.classification() != null ? record.classification().priorityWeight() : 0;

        String capabilityNote = "";
        if (record.capabilities() != null && record.capabilities().unavailableReason() != null) {
            capabilityNote = " [limited: " + record.capabilities().unavailableReason() + "]";
        }

        System.out.printf("[%s] PID %-8d %-16s %-20s weight=%-4d%s%n", marker, record.pid(), category, appName, weight, capabilityNote);
    }
}