package com.jlloc.daemon;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Reads and writes MemoryProfile records to
 * ~/.jlloc/profiles/<sanitizedAppName>.json.
 *
 * One file per app name, not one file for all profiles, keeps reads
 * cheap (only load what you need, when a matching JVM is detected)
 * and avoids one giant file becoming a write-contention point across
 * many concurrently-running JVMs.
 */
public class ProfileStore {

    private static final Path PROFILE_DIR = Path.of(System.getProperty("user.home"), ".jlloc", "profiles");

    private final ObjectMapper mapper = new ObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    public MemoryProfile load(String appName) {
        Path file = pathFor(appName);
        if (!Files.exists(file)) {return MemoryProfile.defaultFor(appName);}
        try {
            return mapper.readValue(file.toFile(), MemoryProfile.class);
        } catch (IOException e) {
            // Corrupt or unreadable profile, don't crash the daemon
            // over stale/bad data, just start fresh for this app.
            return MemoryProfile.defaultFor(appName);
        }
    }

    public void save(MemoryProfile profile) {
        try {
            Files.createDirectories(PROFILE_DIR);
            Files.writeString(pathFor(profile.appName()), mapper.writerWithDefaultPrettyPrinter().writeValueAsString(profile));
        } catch (IOException e) {
            // a failed profile write shouldn't take down
            // the monitoring loop. Log and move on.
            System.err.println("[jlloc] failed to save profile for " + profile.appName() + ": " + e.getMessage());
        }
    }

    private static Path pathFor(String appName) {
        // Sanitize: appName can come from a jar name or class name and
        // might contain characters invalid in a filename on some OSes.
        String safe = appName.replaceAll("[^a-zA-Z0-9._-]", "_");
        return PROFILE_DIR.resolve(safe + ".json");
    }
}