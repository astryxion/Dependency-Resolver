package com.dependencyresolver.bootstrap;

import com.dependencyresolver.log.ResolverLog;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ModListInstallerTest {
    @Test
    void keepsTheModsFolderToTheInstalledJar() throws Exception {
        ResolverLog.init(Files.createTempDirectory("resolver-log"), false);
        Path root = Files.createTempDirectory("mod-list");
        Path mods = root.resolve("mods");
        Files.createDirectories(mods);
        Path service = mods.resolve("dependencyresolver-1.0.0.jar");
        byte[] listing = "mod-list".getBytes(StandardCharsets.UTF_8);
        try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(service))) {
            output.putNextEntry(new JarEntry(ModListInstaller.ENTRY));
            output.write(listing);
            output.closeEntry();
        }
        Files.writeString(mods.resolve(ModListInstaller.FILE_NAME), "old");
        Files.writeString(mods.resolve(ModListInstaller.FILE_NAME + ".tmp"), "partial");

        ModListInstaller.prepare(mods);

        assertFalse(Files.exists(mods.resolve(ModListInstaller.FILE_NAME)));
        assertFalse(Files.exists(mods.resolve(ModListInstaller.FILE_NAME + ".tmp")));
        Path placed = ModListInstaller.listingJar();
        assertTrue(Files.isRegularFile(placed));
        assertFalse(placed.startsWith(mods));
        assertArrayEquals(listing, Files.readAllBytes(placed));
    }
}
