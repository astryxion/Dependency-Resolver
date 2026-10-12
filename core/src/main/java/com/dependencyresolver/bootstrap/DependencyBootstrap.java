package com.dependencyresolver.bootstrap;

import com.dependencyresolver.log.ResolverLog;

import java.lang.instrument.Instrumentation;

/**
 * Optional Java agent entry. The installed jar does not need this argument.
 */
public final class DependencyBootstrap {
    private DependencyBootstrap() {
    }

    public static void premain(String agentArgs, Instrumentation instrumentation) {
        System.setProperty("dependencyresolver.bootstrap.active", "true");
        String[] args = ProcessArguments.current(agentArgs);
        GateResult result = BootstrapGate.open(args).run();
        if (!result.proceed()) {
            ResolverLog.error(result.message(), null);
            System.err.println(result.message());
            System.exit(result.exitCode());
        }
        ResolverLog.info("Bootstrap gate released. Forge initialization starts now in this process.");
    }

    public static void main(String[] args) {
        try {
            run(args, ForgeHandoff::launch);
        } catch (LaunchAbortedException exception) {
            System.err.println(exception.getMessage());
            System.exit(exception.exitCode());
        } catch (Exception exception) {
            ResolverLog.error("Forge handoff failed", exception);
            System.err.println("Minecraft could not be started after dependency installation.");
            System.exit(1);
        }
    }

    public static void run(String[] args, ForgeLauncher launcher) throws Exception {
        System.setProperty("dependencyresolver.bootstrap.active", "true");
        GateResult result = BootstrapGate.open(args).run();
        if (!result.proceed()) {
            throw new LaunchAbortedException(result);
        }
        launcher.launch(args);
    }

    @FunctionalInterface
    public interface ForgeLauncher {
        void launch(String[] args) throws Exception;
    }

    public static final class LaunchAbortedException extends Exception {
        private final int exitCode;

        public LaunchAbortedException(GateResult result) {
            super(result.message());
            this.exitCode = result.exitCode();
        }

        public int exitCode() {
            return exitCode;
        }
    }
}
