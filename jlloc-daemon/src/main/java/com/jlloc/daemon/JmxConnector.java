package com.jlloc.daemon;

import javax.management.MBeanServerConnection;
import javax.management.ObjectName;
import javax.management.openmbean.CompositeData;
import javax.management.remote.JMXConnector;
import javax.management.remote.JMXConnectorFactory;
import javax.management.remote.JMXServiceURL;
import java.io.IOException;
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
    /**
     * Probes whether a PID is actually reachable, instead of assuming
     * every JVM supports attach + JMX. Real reasons this can fail:
     * the target was started with -XX:+DisableAttachMechanism, it's
     * owned by another OS user / requires elevated permissions, or
     * it's some embedded/custom runtime that doesn't expose the
     * Attach API the way standard HotSpot does. We discover this
     * once per PID and record it, rather than letting every caller
     * independently try-catch its way around the same failure modes.
     */
    public static JvmCapabilities probeCapabilities(long pid) {
        com.sun.tools.attach.VirtualMachine vm;
        try {
            vm = com.sun.tools.attach.VirtualMachine.attach(String.valueOf(pid));
        } catch (com.sun.tools.attach.AttachNotSupportedException e) {
            return JvmCapabilities.none("attach not supported (e.g. -XX:+DisableAttachMechanism, or a non-HotSpot runtime)");
        } catch (IOException e) {
            return JvmCapabilities.none("attach failed: " + e.getMessage() + " (often a permissions issue, e.g. process owned by another user)");
        }

        try {
            String connectorAddress = vm.getAgentProperties()
                    .getProperty("com.sun.management.jmxremote.localConnectorAddress");
            if (connectorAddress == null) {
                connectorAddress = vm.startLocalManagementAgent();
            }
            if (connectorAddress == null) {
                return JvmCapabilities.attachOnly("attach succeeded but JMX management agent could not be started");
            }
            return JvmCapabilities.full();
        } catch (Exception e) {
            return JvmCapabilities.attachOnly("attach succeeded but JMX connection failed: " + e.getMessage());
        } finally {
            try {
                vm.detach();
            } catch (IOException ignored) {
                // best-effort cleanup of the probe attach, not worth
                // surfacing a failure here on top of whatever else
                // happened above
            }
        }
    }

    public void connect() throws Exception {
        VirtualMachine vm = VirtualMachine.attach(String.valueOf(pid));
        try {
            // Ask the target JVM for its local JMX connector address.
            // If the JVM has never had JMX enabled, this property is
            // null on first call — startLocalManagementAgent() forces
            // the target JVM to start one on demand.
            String connectorAddress =
                    vm.getAgentProperties().getProperty("com.sun.management.jmxremote.localConnectorAddress");

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
     * Reads cumulative GC stats. We use these to detect "GC pressure" —
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
     * Lists every JVM process visible to the current user, using the
     * same mechanism `jps` uses internally. This is how the daemon
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

    public record GcStats(long collectionCount, long collectionTimeMs) {
    }
}