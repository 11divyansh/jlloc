package com.jlloc.daemon;

import com.sun.tools.attach.VirtualMachine;

import java.util.List;
import java.util.Properties;

/**
 * Turns a raw DetectedJvm (just a PID + whatever command-line/display
 * name string the OS reports) into a Classification, using rules
 * loaded from FingerprintRegistry rather than hardcoded Java logic.
 *
 * This used to classify into a fixed ProcessType enum (SPRING_BOOT,
 * ELASTICSEARCH, ACTIVEMQ, ...).
 */
public class ProcessFingerprinter {

    private static final int DEFAULT_UNKNOWN_WEIGHT = 10;

    private final FingerprintRegistry registry;

    public ProcessFingerprinter() {
        this(FingerprintRegistry.loadDefault());
    }

    public ProcessFingerprinter(FingerprintRegistry registry) {
        this.registry = registry;
    }

    /**
     * Cheap, no-attach-required classification based purely on the
     * display name JvmProcessWatcher already gave us.
     */
    public Classification classify(JvmProcessWatcher.DetectedJvm jvm) {
        String name = jvm.displayName();
        if (name == null || name.isBlank()) {
            return unknown(jvm.pid());
        }

        List<FingerprintRegistry.FingerprintRule> matches = registry.matchByName(name);
        if (matches.isEmpty()) {
            if (name.endsWith(".jar")) {
                return new Classification(jvm.pid(), "unknown", extractJarName(name), DEFAULT_UNKNOWN_WEIGHT);
            }
            return unknown(jvm.pid());
        }

        // Multiple rules can legitimately match the same name (rare,
        // but possible with broad markers) - first match wins, using
        // JSON entry order as the tie-break.
        FingerprintRegistry.FingerprintRule rule = matches.get(0);
        String appName = "ide".equals(rule.category()) || "build-tool".equals(rule.category())
                ? rule.id()
                : extractJarName(name);

        return new Classification(jvm.pid(), rule.category(), appName, rule.priorityWeight());
    }

    /**
     * Deeper, attach-based classification - only worth paying the cost
     * of attaching when the cheap name-based check above returned
     * "unknown".
     */
    public Classification classifyDeep(JvmProcessWatcher.DetectedJvm jvm) {
        Classification shallow = classify(jvm);

        if (!"unknown".equals(shallow.category())) {
            return shallow;
        }

        try {
            VirtualMachine vm = VirtualMachine.attach(String.valueOf(jvm.pid()));
            try {
                Properties props = vm.getSystemProperties();

                FingerprintRegistry.FingerprintRule rule = registry.matchBySystemProperty(props);
                if (rule != null) {
                    String markerValue = firstNonBlankMarkerValue(props, rule);
                    String appName = markerValue != null ? markerValue : rule.id();
                    return new Classification(jvm.pid(), rule.category(), appName, rule.priorityWeight());
                }

                // NOTE: we deliberately do NOT check java.class.path for
                // framework jars here - classpath presence is not proof
                // of usage and produced real false positives in testing
                // (see git history). Removed intentionally, not an
                // oversight.

                String command = props.getProperty("sun.java.command", "");
                if (!command.isBlank()) {
                    return new Classification(jvm.pid(), shallow.category(), firstToken(command), shallow.priorityWeight());
                }
            } finally {
                vm.detach();
            }
        } catch (Exception e) {
            // Attach can legitimately fail (process exited between
            // detection and now, permissions, etc.) - fall back to the
            // cheap classification rather than letting one flaky attach
            // kill the whole pipeline.
        }

        return shallow;
    }

    private static String firstNonBlankMarkerValue(Properties props, FingerprintRegistry.FingerprintRule rule) {
        for (String marker : rule.systemPropertyMarkers()) {
            String value = props.getProperty(marker);
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    private static Classification unknown(long pid) {
        return new Classification(pid, "unknown", "unknown", DEFAULT_UNKNOWN_WEIGHT);
    }

    private static String extractJarName(String pathOrName) {
        String normalized = pathOrName.replace('\\', '/');
        String withoutPath = normalized.contains("/")
                ? normalized.substring(normalized.lastIndexOf('/') + 1)
                : normalized;
        return withoutPath.replace(".jar", "");
    }

    private static String firstToken(String command) {
        int spaceIndex = command.indexOf(' ');
        String first = spaceIndex > 0 ? command.substring(0, spaceIndex) : command;

        if (first.contains("/") || first.contains("\\") || first.endsWith(".jar")) {
            return extractJarName(first);
        }
        return first;
    }

    /**
     * category is an open string (see FingerprintRegistry javadoc).
     * priorityWeight is a plain int the budget engine can use directly
     * in proportional-share arithmetic.
     */
    public record Classification(long pid, String category, String appName, int priorityWeight) {}
}