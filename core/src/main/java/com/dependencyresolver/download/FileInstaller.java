package com.dependencyresolver.download;

import com.dependencyresolver.dependency.InstalledMod;
import com.dependencyresolver.dependency.VersionRange;
import com.dependencyresolver.instance.InstalledModScanner;
import com.dependencyresolver.log.ResolverLog;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

public final class FileInstaller {
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");
    private final InstalledModScanner scanner;

    public FileInstaller(InstalledModScanner scanner) {
        this.scanner = scanner;
    }

    public List<Path> moveIncompatible(String modId, VersionRange required, Path modsDir) throws IOException {
        Files.createDirectories(modsDir);
        Path normalizedMods = modsDir.toAbsolutePath().normalize();
        List<Path> moved = new ArrayList<>();
        for (InstalledMod mod : scanner.scan(normalizedMods)) {
            if (mod.embedded() || !mod.modId().equals(modId) || required.contains(mod.version())) {
                continue;
            }
            moved.add(moveAside(mod.path(), normalizedMods));
        }
        return moved;
    }

    public Path install(Path source, String fileName, String modId, VersionRange required, Path modsDir) throws IOException {
        if (!JarInspector.isJar(source)) {
            throw new IOException("The downloaded file could not be installed because it is not a JAR.");
        }
        Files.createDirectories(modsDir);
        Path normalizedMods = modsDir.toAbsolutePath().normalize();
        List<InstalledMod> existing = scanner.scan(normalizedMods).stream()
                .filter(mod -> mod.modId().equals(modId) && !mod.embedded())
                .toList();
        boolean compatible = existing.stream().anyMatch(mod -> required.contains(mod.version()));
        List<Path> moved = new ArrayList<>();
        for (InstalledMod mod : existing) {
            if (!required.contains(mod.version())) {
                moved.add(moveAside(mod.path(), normalizedMods));
            }
        }
        if (compatible) {
            Files.deleteIfExists(source);
            Path kept = existing.stream().filter(mod -> required.contains(mod.version())).map(InstalledMod::path).findFirst().orElseThrow();
            ResolverLog.info(modId + " is already installed at " + kept.getFileName() + ". Incompatible copies moved aside: " + moved.size());
            return kept;
        }
        Path destination = unique(normalizedMods.resolve(DownloadManager.sanitize(fileName)));
        if (!destination.toAbsolutePath().normalize().startsWith(normalizedMods)) {
            throw new IOException("Refusing to install outside the mods directory.");
        }
        Files.copy(source, destination);
        Files.deleteIfExists(source);
        ResolverLog.info("Installed " + modId + " to " + destination.getFileName());
        return destination;
    }

    private static Path moveAside(Path source, Path modsDir) throws IOException {
        Path disabled = modsDir.resolve("dependencyresolver-disabled");
        Files.createDirectories(disabled);
        Path target = disabled.resolve(source.getFileName().toString());
        if (Files.exists(target)) {
            target = disabled.resolve(STAMP.format(LocalDateTime.now()) + "-" + source.getFileName());
        }
        Files.move(source, target);
        String note = "Moved by Dependency Resolver because this file did not satisfy the installed mods.\n"
                + "Original file: " + source.getFileName() + "\n"
                + "The file was not deleted.\n";
        Files.writeString(target.resolveSibling(target.getFileName().toString() + ".txt"), note, StandardCharsets.UTF_8);
        ResolverLog.info("Moved incompatible file " + source.getFileName() + " to " + target);
        return target;
    }

    private static Path unique(Path destination) {
        if (!Files.exists(destination)) {
            return destination;
        }
        String name = destination.getFileName().toString();
        int dot = name.lastIndexOf('.');
        String stem = dot < 0 ? name : name.substring(0, dot);
        String extension = dot < 0 ? "" : name.substring(dot);
        int suffix = 2;
        Path candidate = destination;
        while (Files.exists(candidate)) {
            candidate = destination.resolveSibling(stem + "-" + suffix + extension);
            suffix++;
        }
        return candidate;
    }
}
