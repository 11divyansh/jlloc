package com.jlloc.common.protocol;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

/** Local daemon authentication material shared by the CLI and daemon. */
public final class LocalAuth {
    public static final Path TOKEN_FILE = Path.of(System.getProperty("user.home"), ".jlloc", "daemon.token");
    private static final SecureRandom RANDOM = new SecureRandom();

    private LocalAuth() {
    }

    public static String createToken() throws IOException {
        Files.createDirectories(TOKEN_FILE.getParent());
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        Path temporary = TOKEN_FILE.resolveSibling(TOKEN_FILE.getFileName() + ".tmp");
        Files.writeString(temporary, token + System.lineSeparator(), StandardCharsets.US_ASCII);
        try {
            Files.move(temporary, TOKEN_FILE, StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (java.nio.file.AtomicMoveNotSupportedException e) {
            Files.move(temporary, TOKEN_FILE, StandardCopyOption.REPLACE_EXISTING);
        }
        return token;
    }

    public static String readToken() throws IOException {
        return Files.readString(TOKEN_FILE, StandardCharsets.US_ASCII).trim();
    }

    public static boolean equalsToken(String expected, String actual) {
        if (expected == null || actual == null) return false;
        return MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.US_ASCII),
                actual.getBytes(StandardCharsets.US_ASCII));
    }
}
