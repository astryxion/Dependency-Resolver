package com.dependencyresolver.early;

import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.lang.module.ModuleDescriptor;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

class EarlyServiceModuleTest {
    @Test
    void moduleProvidesTransformationService() throws Exception {
        Path moduleInfo = Path.of("build/classes/java/main/module-info.class");
        assertTrue(Files.isRegularFile(moduleInfo), "module-info.class was not compiled");
        ModuleDescriptor descriptor;
        try (InputStream in = Files.newInputStream(moduleInfo)) {
            descriptor = ModuleDescriptor.read(in);
        }
        assertTrue(descriptor.name().equals("com.dependencyresolver"));
        boolean provided = descriptor.provides().stream()
                .anyMatch(provides -> provides.service().equals("cpw.mods.modlauncher.api.ITransformationService")
                        && provides.providers().contains("com.dependencyresolver.early.EarlyDependencyService"));
        assertTrue(provided, "module-info must provide ITransformationService");
        boolean locator = descriptor.provides().stream()
                .anyMatch(provides -> provides.service().equals("net.minecraftforge.forgespi.locating.IModLocator")
                        && provides.providers().contains("com.dependencyresolver.early.SelfModLocator"));
        assertTrue(locator, "module-info must provide IModLocator");
    }
}
