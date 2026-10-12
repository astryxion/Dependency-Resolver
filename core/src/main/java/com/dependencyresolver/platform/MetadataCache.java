package com.dependencyresolver.platform;

import com.dependencyresolver.log.ResolverLog;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Optional;

public final class MetadataCache {
    private final Path directory;
    private final Duration ttl;

    public MetadataCache(Path directory, Duration ttl) {
        this.directory = directory;
        this.ttl = ttl == null ? Duration.ofHours(6) : ttl;
    }

    public Optional<String> get(String key) {
        if (directory == null || key == null) {
            return Optional.empty();
        }
        Path file = directory.resolve(hash(key) + ".cache");
        if (!Files.isRegularFile(file)) {
            return Optional.empty();
        }
        try {
            String text = Files.readString(file, StandardCharsets.UTF_8);
            int split = text.indexOf('\n');
            if (split < 0) {
                return Optional.empty();
            }
            long expiry = Long.parseLong(text.substring(0, split).trim());
            if (Instant.now().toEpochMilli() > expiry) {
                Files.deleteIfExists(file);
                return Optional.empty();
            }
            ResolverLog.debug("Cache hit " + ResolverLog.redact(key));
            return Optional.of(text.substring(split + 1));
        } catch (IOException | NumberFormatException exception) {
            ResolverLog.debug("Ignoring unreadable cache entry: " + exception.getMessage());
            return Optional.empty();
        }
    }

    public void put(String key, String body) {
        if (directory == null || key == null || body == null || body.isBlank()) {
            return;
        }
        String trimmed = body.stripLeading();
        if (!trimmed.startsWith("{") && !trimmed.startsWith("[")) {
            return;
        }
        try {
            Files.createDirectories(directory);
            long expiry = Instant.now().plus(ttl).toEpochMilli();
            Files.writeString(directory.resolve(hash(key) + ".cache"), expiry + "\n" + body, StandardCharsets.UTF_8);
        } catch (IOException exception) {
            ResolverLog.debug("Could not write metadata cache: " + exception.getMessage());
        }
    }

    private static String hash(String key) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(key.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }
}
