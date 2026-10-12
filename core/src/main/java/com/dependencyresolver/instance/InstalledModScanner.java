package com.dependencyresolver.instance;

import com.dependencyresolver.dependency.InstalledMod;
import com.dependencyresolver.dependency.MavenVersion;
import com.dependencyresolver.log.ResolverLog;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarInputStream;
import java.util.jar.Manifest;

public final class InstalledModScanner {
    private static final int EMBEDDED_LIMIT = 20 * 1024 * 1024;
    private final ModsTomlParser parser = new ModsTomlParser();

    public List<InstalledMod> scan(Path modsDir) {
        if (modsDir == null || !Files.isDirectory(modsDir)) {
            ResolverLog.info("Mods directory does not exist yet: " + modsDir);
            return List.of();
        }
        List<InstalledMod> mods = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(modsDir)) {
            for (Path path : stream) {
                if (!Files.isRegularFile(path)) {
                    continue;
                }
                String name = path.getFileName().toString().toLowerCase(java.util.Locale.ROOT);
                if (!name.endsWith(".jar") || name.endsWith(".jar.disabled")) {
                    continue;
                }
                mods.addAll(readJar(path));
            }
        } catch (IOException exception) {
            ResolverLog.error("Could not scan " + modsDir, exception);
        }
        return List.copyOf(mods);
    }

    public List<InstalledMod> readJar(Path path) {
        List<InstalledMod> mods = new ArrayList<>();
        try (JarFile jar = new JarFile(path.toFile())) {
            String manifestVersion = manifestVersion(jar.getManifest());
            JarEntry entry = jar.getJarEntry("META-INF/mods.toml");
            if (entry != null) {
                String text = new String(jar.getInputStream(entry).readAllBytes(), StandardCharsets.UTF_8);
                mods.addAll(toInstalled(parser.parse(text, manifestVersion), path, false));
            }
            jar.stream()
                    .filter(candidate -> {
                        String name = candidate.getName();
                        return !candidate.isDirectory()
                                && name.startsWith("META-INF/jarjar/")
                                && name.endsWith(".jar");
                    })
                    .forEach(candidate -> mods.addAll(readEmbedded(jar, candidate, path)));
        } catch (IOException exception) {
            ResolverLog.error("Could not read mod file " + path.getFileName(), exception);
        }
        return mods;
    }

    private List<InstalledMod> readEmbedded(JarFile jar, JarEntry entry, Path container) {
        try (InputStream input = jar.getInputStream(entry)) {
            byte[] bytes = input.readNBytes(EMBEDDED_LIMIT + 1);
            if (bytes.length > EMBEDDED_LIMIT) {
                ResolverLog.info("Skipping oversized embedded jar " + entry.getName());
                return List.of();
            }
            try (JarInputStream nested = new JarInputStream(new ByteArrayInputStream(bytes))) {
                String manifestVersion = manifestVersion(nested.getManifest());
                JarEntry nestedEntry;
                while ((nestedEntry = nested.getNextJarEntry()) != null) {
                    if ("META-INF/mods.toml".equals(nestedEntry.getName())) {
                        String text = new String(nested.readAllBytes(), StandardCharsets.UTF_8);
                        return toInstalled(parser.parse(text, manifestVersion), container, true);
                    }
                }
            }
        } catch (IOException exception) {
            ResolverLog.error("Could not read embedded jar " + entry.getName(), exception);
        }
        return List.of();
    }

    private static List<InstalledMod> toInstalled(List<ModsTomlParser.ParsedMod> parsed, Path path, boolean embedded) {
        List<InstalledMod> mods = new ArrayList<>();
        for (ModsTomlParser.ParsedMod mod : parsed) {
            if (mod.modId() == null || mod.modId().isBlank()) {
                continue;
            }
            mods.add(new InstalledMod(
                    mod.modId(),
                    mod.displayName(),
                    MavenVersion.parse(mod.version()),
                    mod.modLoader(),
                    mod.loaderVersion(),
                    path,
                    embedded,
                    mod.dependencies()
            ));
        }
        return mods;
    }

    private static String manifestVersion(Manifest manifest) {
        if (manifest == null) {
            return "";
        }
        String version = manifest.getMainAttributes().getValue("Implementation-Version");
        return version == null ? "" : version;
    }
}
