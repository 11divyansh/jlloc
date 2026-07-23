package com.jlloc.daemon;

import com.sun.tools.attach.VirtualMachine;

import java.util.List;
import java.util.Properties;

/**
 * Turns a raw DetectedJvm into a Classification, using rules loaded
 * from FingerprintRegistry rather than hardcoded Java logic.
 *
 * Priority is a plain int (priorityWeight from the registry), not an
 * enum like LOW/NORMAL/MEDIUM/HIGH. The budget engine needs to do real
 * arithmetic with this - e.g. "give each process a share proportional
 * to its weight" - and a 4-value enum forces an awkward mapping step
 * before any math can happen. A number is just usable directly:
 * weight 80 naturally gets 4x the share of weight 20, no translation
 * layer needed.
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

        String appName;
        if ("ide".equals(rule.category()) || "build-tool".equals(rule.category())) {
            appName = rule.id();
        } else if ("search-engine".equals(rule.category())
                || "message-broker".equals(rule.category())
                || "database".equals(rule.category())) {
            // For well-known infrastructure, use the fingerprint id as
            // the clean name rather than a raw path or class name
            appName = rule.id();
        } else {
            appName = normalizeAppName(name);
        }

        return new Classification(jvm.pid(), rule.category(), appName, rule.priorityWeight());
    }

    /**
     * Normalizes any raw name-like string (a full command line, a
     * fully-qualified class name, a jar path) into the same clean,
     * short form regardless of which code path produced it.
     *
     */
    private static String normalizeAppName(String raw) {
        if (raw == null || raw.isBlank()) return raw;

        // Command lines have args after the first space take the
        // first token before doing anything else
        int spaceIndex = raw.indexOf(' ');
        String first = spaceIndex > 0 ? raw.substring(0, spaceIndex) : raw;

        if (first.contains("/") || first.contains("\\") || first.endsWith(".jar")) {
            return extractJarName(first);
        }
        if (first.contains(".")) {
            return first.substring(first.lastIndexOf('.') + 1);
        }
        return first;
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
                    String appName = markerValue != null ? normalizeAppName(markerValue) : rule.id();
                    return new Classification(jvm.pid(), rule.category(), appName, rule.priorityWeight());
                }


                // sun.java.command fallback, firstToken() already does this work,
                // so just delegate to the shared function for consistency
                String command = props.getProperty("sun.java.command", "");
                if (!command.isBlank()) {
                    String normalized = normalizeAppName(command);
                    // Re-check the registry against the NORMALIZED name.
                    List<FingerprintRegistry.FingerprintRule> matches = registry.matchByName(normalized);
                    if (!matches.isEmpty()) {
                        FingerprintRegistry.FingerprintRule rule2 = matches.get(0);
                        return new Classification(jvm.pid(), rule2.category(), normalized, rule2.priorityWeight());
                    }

                    return new Classification(jvm.pid(), shallow.category(), normalized, shallow.priorityWeight());
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

        // Strip package prefix from fully-qualified class names:
        // com.connectsphere.auth.AuthServiceApplication → AuthServiceApplication
        // examples.BatchDocumentExtraction → BatchDocumentExtraction
        if (first.contains(".")) {
            return first.substring(first.lastIndexOf('.') + 1);
        }
        return first;
    }

    /**
     * category is an open string (see FingerprintRegistry javadoc).
     * priorityWeight is a plain int the budget engine can use directly
     * in proportional-share arithmetic.
     */
    public record Classification(long pid, String category, String appName, int priorityWeight) {
    }
}