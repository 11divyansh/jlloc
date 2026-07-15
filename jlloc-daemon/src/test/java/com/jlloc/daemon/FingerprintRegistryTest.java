package com.jlloc.daemon;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class FingerprintRegistryTest {

    @Test
    void regexFingerprintMatchesOriginalCaseSensitiveText() {
        FingerprintRegistry registry = FingerprintRegistry.loadDefault();

        boolean matched = registry.matchByName("com.example.MyApplication")
                .stream()
                .anyMatch(rule -> "spring-boot-ide-launched".equals(rule.id()));

        assertTrue(matched, "Regex fingerprints should match against the original display name");
    }
}
