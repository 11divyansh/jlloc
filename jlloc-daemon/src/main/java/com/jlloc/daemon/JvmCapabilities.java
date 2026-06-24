package com.jlloc.daemon;

/**
 * What a specific JVM process actually supports, discovered rather
 * than assumed. Different JVMs genuinely differ here:
 *
 *   - a JVM started with -XX:+DisableAttachMechanism has no attach
 *     capability at all, so neither JMX-over-attach nor anything else
 *     in this tool can reach it
 *   - a JVM owned by another OS user (or an Administrator-owned
 *     process) may be attach-capable in principle but permission
 *     denied in practice for us specifically
 *   - some embedded/custom JVM runtimes don't expose JVMTI the same
 *     way standard HotSpot does
 *
 * Rather than every caller independently try-catching its way around
 * these differences, ProcessRepository discovers capability once per
 * PID and records it here, so the rest of the system can ask "can I
 * even read heap stats for this one?" before trying.
 */
public record JvmCapabilities(
        boolean attachable,
        boolean jmxReachable,
        String unavailableReason
) {
    public static JvmCapabilities full() {
        return new JvmCapabilities(true, true, null);
    }

    public static JvmCapabilities attachOnly(String reason) {
        return new JvmCapabilities(true, false, reason);
    }

    public static JvmCapabilities none(String reason) {
        return new JvmCapabilities(false, false, reason);
    }
}