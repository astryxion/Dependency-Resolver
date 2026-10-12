package com.dependencyresolver.bootstrap;

import com.dependencyresolver.log.ResolverLog;

import java.lang.reflect.Method;

public final class ForgeHandoff {
    private ForgeHandoff() {
    }

    public static void launch(String[] args) throws Exception {
        ResolverLog.info("Loading state: " + LoadingState.HANDED_OFF);
        ResolverLog.info("Entering cpw.mods.bootstraplauncher.BootstrapLauncher. Forge has not been initialized yet.");
        Class<?> launcher = Class.forName("cpw.mods.bootstraplauncher.BootstrapLauncher");
        Method main = launcher.getMethod("main", String[].class);
        main.invoke(null, (Object) args);
    }
}
