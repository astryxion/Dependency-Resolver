package com.dependencyresolver.download;

import com.dependencyresolver.dependency.DependencyConstraint;
import com.dependencyresolver.dependency.InstalledMod;
import com.dependencyresolver.dependency.MavenVersion;
import com.dependencyresolver.dependency.VersionRange;
import com.dependencyresolver.instance.InstalledModScanner;
import com.dependencyresolver.log.ResolverLog;
import com.dependencyresolver.platform.LoaderKind;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public final class JarInspector {
    private final InstalledModScanner scanner = new InstalledModScanner();

    public List<Inspected> inspectAll(Path path, LoaderKind loader) throws IOException {
        if (!isJar(path)) {
            throw new IOException("The downloaded file is not a JAR.");
        }
        List<InstalledMod> mods = scanner.readJar(path).stream()
                .filter(mod -> !mod.embedded())
                .toList();
        if (mods.isEmpty()) {
            throw new IOException("The downloaded JAR does not contain META-INF/mods.toml.");
        }
        List<Inspected> inspected = new ArrayList<>();
        for (InstalledMod mod : mods) {
            ResolverLog.info("Inspected " + path.getFileName() + " as " + mod.modId() + " " + mod.version().raw()
                    + " loader=" + mod.modLoader() + " " + mod.loaderVersion());
            for (DependencyConstraint dependency : mod.dependencies()) {
                ResolverLog.info("  jar depends on " + dependency.modId() + " " + dependency.rawRange());
            }
            inspected.add(new Inspected(mod.modId(), mod.displayName(), mod.version(), mod.modLoader(), mod.loaderVersion(), mod.dependencies()));
        }
        boolean forge = inspected.stream().anyMatch(mod -> mod.modLoader() == null || mod.modLoader().isBlank() || loader.acceptsModLoader(mod.modLoader()));
        if (!forge) {
            throw new IOException("The downloaded JAR uses loader '" + inspected.get(0).modLoader() + "', not Forge.");
        }
        return inspected;
    }

    public static boolean isJar(Path path) throws IOException {
        if (path == null || !Files.isRegularFile(path) || Files.size(path) < 4) {
            return false;
        }
        try (InputStream input = Files.newInputStream(path)) {
            byte[] magic = input.readNBytes(4);
            return magic.length == 4 && magic[0] == 'P' && magic[1] == 'K';
        }
    }

    public record Inspected(
            String modId,
            String displayName,
            MavenVersion version,
            String modLoader,
            String loaderVersion,
            List<DependencyConstraint> dependencies
    ) {
        private static final MavenVersion FORGE_1201 = MavenVersion.parse("47.4.10");

        public boolean supportsMinecraft(String version) {
            MavenVersion minecraft = MavenVersion.parse(version);
            boolean declaredMinecraft = false;
            for (DependencyConstraint dependency : dependencies) {
                if (!"minecraft".equals(dependency.modId())) {
                    continue;
                }
                declaredMinecraft = true;
                if (dependency.range().isAny() || dependency.range().contains(minecraft)) {
                    return true;
                }
            }
            if (declaredMinecraft) {
                return false;
            }
            if (loaderVersion != null && !loaderVersion.isBlank()) {
                VersionRange loader = VersionRange.parse(loaderVersion);
                if (!loader.isAny() && !loader.contains(FORGE_1201)) {
                    return false;
                }
            }
            boolean declaredForge = false;
            for (DependencyConstraint dependency : dependencies) {
                if (!"forge".equals(dependency.modId())) {
                    continue;
                }
                declaredForge = true;
                if (dependency.range().isAny() || dependency.range().contains(FORGE_1201)) {
                    return true;
                }
            }
            return !declaredForge;
        }
    }
}
