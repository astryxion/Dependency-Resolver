package com.dependencyresolver.early;

import com.dependencyresolver.bootstrap.BootstrapGate;
import com.dependencyresolver.bootstrap.GateResult;
import com.dependencyresolver.bootstrap.ModListInstaller;
import com.dependencyresolver.log.ResolverLog;
import cpw.mods.modlauncher.api.IEnvironment;
import cpw.mods.modlauncher.api.ITransformationService;
import cpw.mods.modlauncher.api.ITransformer;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;

/**
 * Forge discovers this from the mods folder before it scans mods.toml files.
 * {@link #initialize} finishes for every service before any service begins
 * scanning, so jars installed here are present for Forge's first mod scan.
 */
public final class EarlyDependencyService implements ITransformationService {
    @Override
    public String name() {
        return "dependencyresolver";
    }

    @Override
    public void onLoad(IEnvironment environment, Set<String> otherServices) {
    }

    @Override
    public void initialize(IEnvironment environment) {
        System.setProperty("dependencyresolver.bootstrap.active", "true");
        Path gameDir = environment.getProperty(IEnvironment.Keys.GAMEDIR.get())
                .orElseGet(() -> Path.of(System.getProperty("user.dir", ".")))
                .toAbsolutePath()
                .normalize();
        System.setProperty("dependencyresolver.gameDir", gameDir.toString());
        String target = environment.getProperty(IEnvironment.Keys.LAUNCHTARGET.get()).orElse("forgeclient");
        BootstrapGate gate = BootstrapGate.open(new String[]{"--launchTarget", target});
        ResolverLog.info("Dependency Resolver is running from the mods folder, before Forge scans mods.");
        GateResult result = gate.run();
        if (!result.proceed()) {
            ResolverLog.error(result.message(), null);
            System.err.println(result.message());
            System.exit(result.exitCode() == 0 ? 1 : result.exitCode());
        }
        ModListInstaller.prepare(gameDir.resolve("mods"));
        ResolverLog.info("Mods folder is ready. Forge mod scan starts next.");
    }

    @Override
    @SuppressWarnings("rawtypes")
    public List<ITransformer> transformers() {
        return List.of();
    }
}
