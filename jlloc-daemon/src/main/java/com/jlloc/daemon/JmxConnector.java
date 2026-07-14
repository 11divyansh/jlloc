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
 * via JMX. This is the foundational piece of jlloc — every other
 * component (budget engine, CLI status, profile store) depends on
 * being able to ask "what is this JVM's heap doing right now?"
 *
 * How this works:
 *
 *   1. We use the Attach API to "attach" to a target JVM by PID.
 *      This is the same mechanism VisualVM/JConsole use — it does
 *      NOT require the target JVM to have been started with any
 *      special flags. Any JVM can be attached to by default.
 *
 *   2. Attaching gives us a "local connector address", basically
 *      a local socket/pipe the target JVM exposes once we ask it to.
 *
 *   3. We connect a standard JMXConnector to that address.
 *
 *   4. Once connected, we query the well-known MBean
 *      "java.lang:type=Memory" which every JVM exposes by default.
 *      It contains HeapMemoryUsage: used / committed / max.
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
            String connectorAddress = vm.getAgentProperties().getProperty("com.sun.management.jmxremote.localConnectorAddress");
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
     */
    public HeapStats readHeapStats() throws Exception {
        ObjectName memoryMBean = new ObjectName("java.lang:type=Memory");
        CompositeData heapUsage = (CompositeData) mbeanConnection.getAttribute(memoryMBean, "HeapMemoryUsage");

        long used = (Long) heapUsage.get("used");
        long committed = (Long) heapUsage.get("committed");
        long max = (Long) heapUsage.get("max");

        GcStats gcStats = readGcStats();
        return new HeapStats(pid, used, committed, max, gcStats);
    }

    /**
     * Reads off-heap JVM memory pools: Metaspace, Code Cache, and
     * Direct Buffers. These are the signals that explain why a pod
     * gets killed even when heap looks healthy, off-heap allocations
     * (especially from Netty/NIO direct buffers in Spring WebFlux,
     * Elasticsearch, Kafka) contribute to total RSS and can push the
     * process over a container memory limit while heap metrics show
     * everything is fine.
     *
     * All three are standard JMX, no special flags or attach needed.
     */
    public OffHeapStats readOffHeapStats() throws Exception {
        long metaspaceUsed = 0, metaspaceCommitted = 0;
        long codeCacheUsed = 0;

        // Memory pools: Metaspace and Code Cache live here
        List<ObjectName> memPools = List.copyOf(mbeanConnection.queryNames(new ObjectName("java.lang:type=MemoryPool,*"), null));

        for (ObjectName pool : memPools) {
            String name = (String) mbeanConnection.getAttribute(pool, "Name");
            CompositeData usage = (CompositeData) mbeanConnection.getAttribute(pool, "Usage");
            if (usage == null) continue;

            long poolUsed = (Long) usage.get("used");
            long poolCommitted = (Long) usage.get("committed");

            if (name.contains("Metaspace")) {
                metaspaceUsed = poolUsed;
                metaspaceCommitted = poolCommitted;
            } else if (name.contains("Code")) {
                codeCacheUsed = poolUsed;
            }
        }

        // Direct buffer pools: ByteBuffer.allocateDirect(), used heavily
        // by Netty (Spring WebFlux, Elasticsearch transport, Kafka)
        long directUsed = 0, directCapacity = 0;
        List<ObjectName> bufferPools = List.copyOf(mbeanConnection.queryNames(new ObjectName("java.nio:type=BufferPool,*"), null));

        for (ObjectName pool : bufferPools) {
            String name = (String) mbeanConnection.getAttribute(pool, "Name");
            if ("direct".equals(name)) {
                directUsed = (Long) mbeanConnection.getAttribute(pool, "MemoryUsed");
                directCapacity = (Long) mbeanConnection.getAttribute(pool, "TotalCapacity");
            }
        }

        return new OffHeapStats(
                metaspaceUsed, metaspaceCommitted,
                directUsed, directCapacity,
                codeCacheUsed
        );
    }

    public record OffHeapStats(
            long metaspaceUsedBytes,
            long metaspaceCommittedBytes,
            long directBufferUsedBytes,
            long directBufferCapacityBytes,
            long codeCacheUsedBytes
    ) {
        /**
         * Estimated total JVM footprint = heap + Metaspace + Code Cache
         * + direct buffers. This is what contributes to container RSS,
         * not just the heap. When this approaches the container limit,
         * the pod will be killed regardless of heap health.
         */
        public long estimatedTotalFootprintBytes(long heapUsedBytes) {
            return heapUsedBytes + metaspaceUsedBytes
                    + codeCacheUsedBytes + directBufferUsedBytes;
        }
    }

    /**
     * Reads cumulative GC stats. We use these to detect "GC pressure"
     * a JVM spending a high percentage of time in GC is a leading
     * indicator of an OOM about to happen (see GcPressureCalculator,
     * built later, for how this becomes a percentage).
     */
    private GcStats readGcStats() throws Exception {
        // Every GC algorithm (G1, ZGC, Parallel...) registers its own
        // MBean(s) under this domain. We sum across all of them so
        // this works regardless of which collector the target JVM uses.
        List<ObjectName> gcMBeans = List.copyOf(mbeanConnection.queryNames(new ObjectName("java.lang:type=GarbageCollector,*"), null));

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