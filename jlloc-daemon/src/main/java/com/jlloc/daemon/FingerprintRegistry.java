package com.jlloc.daemon;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.regex.Pattern;

/**
 * Loads the set of known process fingerprints from
 * resources/fingerprints.json at startup.
 *
 * This exists specifically so that "what kinds of JVM processes does
 * jlloc recognize" is DATA, not Java code. Supporting a new framework
 * means adding an entry to that JSON file and nothing else.
 *
 * Supports a "version" field at the top of the JSON file specifically
 * so a future schema change (new required field, renamed field, etc.)
 * can be detected and handled deliberately - either by migrating old
 * files or failing with a clear message - instead of silently
 * misparsing an old-format file written before the schema changed.
 */
public class FingerprintRegistry {

    /**
     * Bump this when the JSON schema changes in a way that isn't
     * backward compatible. loadDefault() will refuse to load a file
     * whose version is newer than this, rather than silently
     * misinterpreting fields it doesn't understand.
     */
    static final int SUPPORTED_SCHEMA_VERSION = 1;

    private final List<FingerprintRule> rules;

    private FingerprintRegistry(List<FingerprintRule> rules) {
        this.rules = rules;
    }

    public static FingerprintRegistry loadDefault() {
        try (InputStream in = FingerprintRegistry.class.getResourceAsStream("/fingerprints.json")) {
            if (in == null) {
                throw new IllegalStateException("fingerprints.json not found on classpath");
            }
            return load(in);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to load fingerprints.json", e);
        }
    }

    static FingerprintRegistry load(InputStream in) throws IOException {
        ObjectMapper mapper = new ObjectMapper();
        JsonNode root = mapper.readTree(in);

        int version = root.has("version") ? root.get("version").asInt() : 0;
        if (version > SUPPORTED_SCHEMA_VERSION) {
            throw new IllegalStateException(
                    "fingerprints.json version " + version + " is newer than this build supports ("
                            + SUPPORTED_SCHEMA_VERSION + "). Upgrade jlloc, or pin an older fingerprints.json.");
        }

        ArrayNode fingerprints = (ArrayNode) root.get("fingerprints");
        List<FingerprintRule> rules = new ArrayList<>();
        for (JsonNode entry : fingerprints) {
            rules.add(FingerprintRule.fromJson(entry));
        }
        return new FingerprintRegistry(rules);
    }

    /**
     * Returns every rule that matches the given text, using each
     * rule's own configured match strategy (contains/equals/prefix/
     * suffix/regex). Returns multiple matches rather than just the
     * first, deliberately - the caller decides priority/tie-breaking,
     * this method just reports the truth.
     */
    public List<FingerprintRule> matchByName(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }

        List<FingerprintRule> matches = new ArrayList<>();
        for (FingerprintRule rule : rules) {
            if (rule.matchesName(text)) {
                matches.add(rule);
            }
        }
        return matches;
    }

    /**
     * Looks for a rule whose systemPropertyMarkers includes a property
     * that's actually present (non-null) in the given properties.
     */
    public FingerprintRule matchBySystemProperty(Properties props) {
        for (FingerprintRule rule : rules) {
            for (String marker : rule.systemPropertyMarkers()) {
                if (props.getProperty(marker) != null) {
                    return rule;
                }
            }
        }
        return null;
    }

    public record FingerprintRule(
            String id,
            String category,
            MatchType matchType,
            List<String> nameContains,
            List<String> systemPropertyMarkers,
            int priorityWeight
    ) {
        static FingerprintRule fromJson(JsonNode node) {
            MatchType matchType = node.has("match")
                    ? MatchType.fromString(node.get("match").asText())
                    : MatchType.CONTAINS;

            return new FingerprintRule(
                    node.get("id").asText(),
                    node.get("category").asText(),
                    matchType,
                    toStringList(node.get("nameContains")),
                    toStringList(node.get("systemPropertyMarkers")),
                    node.has("priorityWeight") ? node.get("priorityWeight").asInt() : 10
            );
        }

        boolean matchesName(String text) {
            String lowerText = text.toLowerCase();
            for (String marker : nameContains) {
                if (matchType.test(text, lowerText, marker.toLowerCase(), marker)) {
                    return true;
                }
            }
            return false;
        }

        private static List<String> toStringList(JsonNode arrayNode) {
            List<String> result = new ArrayList<>();
            if (arrayNode != null) {
                arrayNode.forEach(n -> result.add(n.asText()));
            }
            return result;
        }
    }

    /**
     * How a rule's nameContains markers get tested against a process's
     * display name. CONTAINS is the common case (most class/jar names
     * just need a substring match); the others exist for markers that
     * need to be exact or pattern-based to avoid false positives -
     * e.g. a suffix check is safer than "contains" for matching a file
     * extension, and regex covers anything more specific than that.
     */
    public enum MatchType {
        CONTAINS {
            @Override
            boolean test(String originalText, String lowerText, String lowerMarker, String originalMarker) {
                return lowerText.contains(lowerMarker);
            }
        },
        EQUALS {
            @Override
            boolean test(String originalText, String lowerText, String lowerMarker, String originalMarker) {
                return lowerText.equals(lowerMarker);
            }
        },
        PREFIX {
            @Override
            boolean test(String originalText, String lowerText, String lowerMarker, String originalMarker) {
                return lowerText.startsWith(lowerMarker);
            }
        },
        SUFFIX {
            @Override
            boolean test(String originalText, String lowerText, String lowerMarker, String originalMarker) {
                return lowerText.endsWith(lowerMarker);
            }
        },
        REGEX {
            @Override
            boolean test(String originalText, String lowerText, String lowerMarker, String originalMarker) {
                // Regex markers are matched case-sensitively against the
                // ORIGINAL text/pattern, not the lowercased versions.
                // Lowercasing a regex pattern can silently change its
                // meaning (e.g. character classes), so this is the one
                // match type that opts out of the case-insensitive
                // convention the others use.
                return Pattern.compile(originalMarker).matcher(originalText).find();
            }
        };

        abstract boolean test(String originalText, String lowerText, String lowerMarker, String originalMarker);

        static MatchType fromString(String value) {
            try {
                return valueOf(value.toUpperCase());
            } catch (Exception e) {
                return CONTAINS;
            }
        }
    }
}
