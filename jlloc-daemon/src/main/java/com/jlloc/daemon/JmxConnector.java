package com.jlloc.daemon;

import javax.management.MBeanServerConnection;
import javax.management.ObjectName;
import javax.management.openmbean.CompositeData;
import javax.management.remote.JMXConnector;
import javax.management.remote.JMXConnectorFactory;
import javax.management.remote.JMXServiceURL;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.util.List;

import com.sun.tools.attach.VirtualMachine;
import com.sun.tools.attach.VirtualMachineDescriptor;

/**
 * Connects to a target JVM (by PID) and reads its live heap + GC stats
 * via JMX. This is the foundational piece of jlloc - every other
 * component (budget engine, CLI status, profile store) depends on
 * being able to ask "what is this JVM's heap doing right now?"
 */
public class JmxConnector implements AutoCloseable {

    private final long pid;
    private JMXConnector connector;
    private MBeanServerConnection mbeanConnection;

    public JmxConnector(long pid) {
        this.pid = pid;
    }

    /**
     * Attaches to the target JVM and opens a JMX connection.
     * Must be called before any read* methods.
     */
    public void connect() throws Exception {
        VirtualMachine vm = VirtualMachine.attach(String.valueOf(pid));
        try {
            // Ask the target JVM for its local JMX connector address.
            // If the JVM has never had JMX enabled, this property is
            // null on first call, startLocalManagementAgent() forces
            // the target JVM to start one on demand.
            String connectorAddress = vm.getAgentProperties().getProperty("com.sun.management.jmxremote.localConnectorAddress");

            if (connectorAddress == null) {
                connectorAddress = vm.startLocalManagementAgent();
            }

            JMXServiceURL url = new JMXServiceURL(connectorAddress);
            this.connector = JMXConnectorFactory.connect(url);
            this.mbeanConnection = connector.getMBeanServerConnection();
        } finally {
            vm.detach();
        }
    }

    /**
     * Reads current heap usage from the target JVM.
     * Maps directly to what `jcmd <pid> GC.heap_info` would show you,
     * but structured and queryable from our own process.
     */
    public HeapStats readHeapStats() throws Exception {
        ObjectName memoryMBean = new ObjectName("java.lang:type=Memory");

        // HeapMemoryUsage is exposed as a CompositeData, not a simple
        // type, because it bundles four related longs together.
        CompositeData heapUsage = (CompositeData) mbeanConnection.getAttribute(memoryMBean, "HeapMemoryUsage");
        long used = (Long) heapUsage.get("used");
        long committed = (Long) heapUsage.get("committed");
        long max = (Long) heapUsage.get("max");
        GcStats gcStats = readGcStats();
        return new HeapStats(pid, used, committed, max, gcStats);
    }

    /**
     * Reads cumulative GC stats. We use these to detect "GC pressure",
     * a JVM spending a high percentage of time in GC is a leading
     * indicator of an OOM about to happen (see GcPressureCalculator,
     * built later, for how this becomes a percentage).
     */
    private GcStats readGcStats() throws Exception {
        // Every GC algorithm (G1, ZGC, Parallel...) registers its own
        // MBean(s) under this domain. We sum across all of them so
        // this works regardless of which collector the target JVM uses.
        List<ObjectName> gcMBeans = List.copyOf(
                mbeanConnection.queryNames(new ObjectName("java.lang:type=GarbageCollector,*"), null)
        );

        long totalCollectionCount = 0;
        long totalCollectionTimeMs = 0;

        for (ObjectName gcMBean : gcMBeans) {
            Object count = mbeanConnection.getAttribute(gcMBean, "CollectionCount");
            Object time = mbeanConnection.getAttribute(gcMBean, "CollectionTime");

            totalCollectionCount += (Long) count;
            totalCollectionTimeMs += (Long) time;
        }

        return new GcStats(totalCollectionCount, totalCollectionTimeMs);
    }

    @Override
    public void close() throws IOException {
        if (connector != null) {
            connector.close();
        }
    }

    /**
     * Lists every JVM process visible to the current user. This is how the daemon
     * will discover PIDs before connecting to each one individually.
     */
    public static List<VirtualMachineDescriptor> listAttachableJvms() {
        return VirtualMachine.list();
    }

    public record HeapStats(long pid, long usedBytes, long committedBytes, long maxBytes, GcStats gc) {

        public double usedPercentOfMax() {
            if (maxBytes <= 0) {
                return 0.0;
            }
            return (usedBytes * 100.0) / maxBytes;
        }
    }

    public record GcStats(long collectionCount, long collectionTimeMs) {}

    /**
     * Quick manual test — point this at any JVM PID on your machine
     * (run `jps` in a terminal to find one) and you'll see real heap
     * numbers come back. This is the "does it actually work" check
     * before we wire it into the daemon's watch loop.
     */
    public static void main(String[] args) throws Exception {
        if (args.length == 0) {
            System.out.println("Attachable JVMs on this machine:");
            for (VirtualMachineDescriptor vmd : listAttachableJvms()) {
                System.out.printf("  PID %-8s %s%n", vmd.id(), vmd.displayName());
            }
            System.out.println("\nRun again with a PID argument to read its heap stats.");
            return;
        }

        long pid = Long.parseLong(args[0]);
        try (JmxConnector jmx = new JmxConnector(pid)) {
            jmx.connect();
            HeapStats stats = jmx.readHeapStats();

            System.out.printf("PID %d%n", stats.pid());
            System.out.printf("  Heap used:      %,d bytes (%.1f%% of max)%n", stats.usedBytes(), stats.usedPercentOfMax());
            System.out.printf("  Heap committed: %,d bytes%n", stats.committedBytes());
            System.out.printf("  Heap max:       %,d bytes%n", stats.maxBytes());
            System.out.printf("  GC collections: %d (%,d ms total)%n", stats.gc().collectionCount(), stats.gc().collectionTimeMs());
        }
    }
}