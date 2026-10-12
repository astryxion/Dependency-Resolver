package com.dependencyresolver.bootstrap;

import com.dependencyresolver.dependency.DependencyResolver;
import com.dependencyresolver.dependency.Side;
import com.dependencyresolver.instance.InstalledModScanner;
import com.dependencyresolver.log.ResolverLog;

import java.nio.file.Path;

/**
 * Re-reads the mods directory after installation. A successful result means the
 * process may enter Forge for the first time. This is not a Minecraft resource reload.
 */
public final class ReloadController {
    private final InstalledModScanner scanner;
    private final DependencyResolver resolver;
    private final Path modsDir;
    private final Side side;

    public ReloadController(InstalledModScanner scanner, DependencyResolver resolver, Path modsDir, Side side) {
        this.scanner = scanner;
        this.resolver = resolver;
        this.modsDir = modsDir;
        this.side = side;
    }

    public boolean dependenciesSatisfied() {
        ResolverLog.info("Reload check: scanning " + modsDir + " before Forge initialization");
        DependencyResolver.Plan plan = resolver.plan(scanner.scan(modsDir), side);
        ResolverLog.info("Reload dependency graph:\n" + plan.graph().toTreeString());
        boolean ready = !plan.needsUser();
        ResolverLog.info(ready
                ? "Reload check passed. Forge initialization can start."
                : "Reload check failed. Forge initialization stays paused.");
        return ready;
    }
}
