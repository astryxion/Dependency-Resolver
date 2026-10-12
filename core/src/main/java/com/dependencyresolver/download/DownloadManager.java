package com.dependencyresolver.download;

import com.dependencyresolver.log.ResolverLog;
import com.dependencyresolver.platform.HttpTransport;
import com.dependencyresolver.platform.RemoteFile;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Locale;
import java.util.Map;

public final class DownloadManager {
    private final HttpTransport http;
    private final Path tempDirectory;

    public DownloadManager(HttpTransport http, Path tempDirectory) {
        this.http = http;
        this.tempDirectory = tempDirectory;
    }

    public Path download(RemoteFile file, HttpTransport.Progress progress) throws IOException {
        if (file.downloadUrl() == null || file.downloadUrl().isBlank()) {
            throw new IOException("Could not download " + file.displayName() + ". The platform did not provide a download URL.");
        }
        String scheme = URI.create(file.downloadUrl()).getScheme();
        if (scheme == null || !(scheme.equalsIgnoreCase("https") || scheme.equalsIgnoreCase("http"))) {
            throw new IOException("Could not download " + file.displayName() + ". The download URL is not an HTTP URL.");
        }
        Files.createDirectories(tempDirectory);
        String name = sanitize(file.fileName());
        Path destination = tempDirectory.resolve(file.modId() + "-" + System.nanoTime() + "-" + name);
        ResolverLog.info("Downloading " + file.displayName() + " from " + ResolverLog.redact(file.downloadUrl()));
        HttpTransport.Request request = new HttpTransport.Request(
                URI.create(file.downloadUrl()),
                Map.of("User-Agent", HttpTransport.userAgent(), "Accept", "application/java-archive, application/octet-stream, */*"),
                Duration.ofMinutes(5),
                destination,
                (read, total) -> {
                    if (progress != null) {
                        progress.bytes(read, total);
                    }
                    if (total > 0) {
                        int percent = (int) Math.min(100, (read * 100) / total);
                        ResolverLog.debug(file.modId() + " download " + percent + "% (" + read + "/" + total + ")");
                    }
                }
        );
        HttpTransport.Response response = http.send(request);
        if (response.status() < 200 || response.status() >= 300) {
            Files.deleteIfExists(destination);
            throw new IOException("Could not download " + file.displayName() + ". The server returned HTTP " + response.status() + ".");
        }
        if (!Files.isRegularFile(destination) || Files.size(destination) < 4) {
            Files.deleteIfExists(destination);
            throw new IOException("Could not download " + file.displayName() + ". The response was empty.");
        }
        if (file.sha1() != null && !file.sha1().isBlank()) {
            String actual = Hashes.sha1(destination);
            if (!actual.equalsIgnoreCase(file.sha1())) {
                Files.deleteIfExists(destination);
                throw new IOException("Could not download " + file.displayName() + ". The file hash did not match the platform metadata.");
            }
            ResolverLog.info("Verified SHA-1 for " + file.fileName());
        }
        ResolverLog.info("Download complete: " + destination.getFileName());
        return destination;
    }

    static String sanitize(String fileName) {
        String name = fileName == null || fileName.isBlank() ? "dependency.jar" : fileName;
        name = name.replace('\\', '/');
        int slash = name.lastIndexOf('/');
        if (slash >= 0) {
            name = name.substring(slash + 1);
        }
        name = name.replaceAll("[^A-Za-z0-9._+-]", "_");
        if (!name.toLowerCase(Locale.ROOT).endsWith(".jar")) {
            name = name + ".jar";
        }
        return name;
    }
}
