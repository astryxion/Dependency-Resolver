package com.dependencyresolver.bootstrap;

import com.dependencyresolver.log.ResolverLog;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/**
 * Forge will not list the service jar. The normal mod jar nested inside it is
 * unpacked outside the mods folder, and {@code SelfModLocator} hands that file
 * to Forge's scan. The mods folder keeps only the one installed jar.
 */
public final class ModListInstaller {
    public static final String ENTRY = "META-INF/dependencyresolver-modlist.jar";
    public static final String FILE_NAME = "dependencyresolver-modlist.jar";

    private static Path listingJar;

    private ModListInstaller() {
    }

    public static Path listingJar() {
        return listingJar;
    }

    public static void prepare(Path modsDir) {
        listingJar = null;
        deleteStrays(modsDir);
        Path serviceJar = findServiceJar(modsDir);
        if (serviceJar == null) {
            ResolverLog.info("No jar in the mods folder contained the Dependency Resolver mod-list entry.");
            return;
        }
        try {
            byte[] listing = readEntry(serviceJar);
            if (listing == null) {
                ResolverLog.info(fileName(serviceJar) + " has no mod-list entry, so Dependency Resolver will not appear in the mods button.");
                return;
            }
            Path directory = Path.of(System.getProperty("java.io.tmpdir", "."), "dependencyresolver-modlist");
            Files.createDirectories(directory);
            Path destination = directory.resolve("dependencyresolver.jar");
            Files.write(destination, listing);
            listingJar = destination;
            ResolverLog.info("Dependency Resolver will appear in the mods list from " + fileName(serviceJar) + ".");
        } catch (IOException exception) {
            ResolverLog.error("Could not prepare the Dependency Resolver mod-list entry", exception);
        }
    }

    private static void deleteStrays(Path modsDir) {
        if (modsDir == null || !Files.isDirectory(modsDir)) {
            return;
        }
        for (String name : new String[]{FILE_NAME, FILE_NAME + ".tmp"}) {
            Path stray = modsDir.resolve(name);
            try {
                if (Files.deleteIfExists(stray)) {
                    ResolverLog.info("Removed extra file " + name + " from the mods folder.");
                }
            } catch (IOException exception) {
                ResolverLog.error("Could not remove " + name + " from the mods folder. Quit Minecraft and delete it so only one Dependency Resolver jar remains.", exception);
            }
        }
    }

    private static Path findServiceJar(Path modsDir) {
        if (modsDir == null || !Files.isDirectory(modsDir)) {
            return null;
        }
        try (var files = Files.list(modsDir)) {
            for (Path jar : files.filter(ModListInstaller::looksLikeJar).toList()) {
                if (FILE_NAME.equalsIgnoreCase(fileName(jar))) {
                    continue;
                }
                try {
                    if (readEntry(jar) != null) {
                        ResolverLog.info("Found the mod-list entry in " + fileName(jar));
                        return jar;
                    }
                } catch (IOException exception) {
                    ResolverLog.debug("Skipped " + fileName(jar) + " while looking for the mod-list entry: " + exception.getMessage());
                }
            }
        } catch (IOException exception) {
            ResolverLog.error("Could not search the mods folder for the Dependency Resolver jar", exception);
        }
        return null;
    }

    private static boolean looksLikeJar(Path path) {
        return path != null
                && Files.isRegularFile(path)
                && fileName(path).toLowerCase(java.util.Locale.ROOT).endsWith(".jar");
    }

    private static String fileName(Path path) {
        Path name = path.getFileName();
        return name == null ? "" : name.toString();
    }

    private static byte[] readEntry(Path serviceJar) throws IOException {
        try (JarFile jar = new JarFile(serviceJar.toFile())) {
            JarEntry entry = jar.getJarEntry(ENTRY);
            if (entry == null) {
                return null;
            }
            try (InputStream input = jar.getInputStream(entry)) {
                return input.readAllBytes();
            }
        }
    }
}
