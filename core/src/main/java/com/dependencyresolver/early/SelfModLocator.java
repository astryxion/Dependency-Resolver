package com.dependencyresolver.early;

import com.dependencyresolver.bootstrap.ModListInstaller;
import net.minecraftforge.fml.loading.moddiscovery.AbstractJarFileModLocator;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Forge skips the service jar during the normal mods-folder scan. This locator
 * supplies the nested mod jar so the mods button still has one Dependency Resolver entry.
 */
public final class SelfModLocator extends AbstractJarFileModLocator {
    @Override
    public Stream<Path> scanCandidates() {
        Path listing = ModListInstaller.listingJar();
        if (listing == null || !Files.isRegularFile(listing)) {
            return Stream.empty();
        }
        return Stream.of(listing);
    }

    @Override
    public String name() {
        return "dependencyresolver";
    }

    @Override
    public void initArguments(Map<String, ?> arguments) {
    }
}
