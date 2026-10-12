package com.dependencyresolver.bootstrap;

import com.dependencyresolver.instance.InstanceLocator;
import com.dependencyresolver.log.ResolverLog;
import com.dependencyresolver.platform.CurseForgeClient;
import com.dependencyresolver.platform.HttpTransport;
import com.dependencyresolver.platform.MetadataCache;
import com.dependencyresolver.platform.ModrinthClient;
import com.dependencyresolver.platform.Network;
import com.dependencyresolver.platform.PlatformRegistry;
import com.dependencyresolver.platform.TargetEnvironment;
import com.dependencyresolver.ui.DependencyWindow;
import com.dependencyresolver.ui.ReportFormatter;

import java.awt.GraphicsEnvironment;
import java.nio.file.Path;

public final class BootstrapGate {
    private final FlowController flow;
    private final boolean graphical;
    private final GateResult forced;

    public BootstrapGate(FlowController flow, boolean graphical) {
        this(flow, graphical, null);
    }

    private BootstrapGate(FlowController flow, boolean graphical, GateResult forced) {
        this.flow = flow;
        this.graphical = graphical;
        this.forced = forced;
    }

    public static BootstrapGate open(String[] args) {
        Path gameDir = InstanceLocator.locate(args);
        ResolverLog.init(gameDir, Boolean.getBoolean("dependencyresolver.debug"));
        String minecraft = InstanceLocator.minecraftVersion(args);
        if (!"1.20.1".equals(minecraft)) {
            ResolverLog.info("Refusing to resolve dependencies for Minecraft " + minecraft + ". This build supports 1.20.1 only.");
            return new BootstrapGate(null, false, GateResult.abort("This build of Dependency Resolver only supports Minecraft 1.20.1.", 2));
        }
        Settings settings = Settings.load(gameDir);
        ResolverLog.setSecret(settings.curseForgeApiKey());
        if (settings.debug()) {
            ResolverLog.init(gameDir, true);
            ResolverLog.setSecret(settings.curseForgeApiKey());
        }
        TargetEnvironment environment = new TargetEnvironment(minecraft, com.dependencyresolver.platform.LoaderKind.FORGE, InstanceLocator.side(args));
        HttpTransport http = new HttpTransport.JdkHttpTransport();
        MetadataCache cache = new MetadataCache(gameDir.resolve("config").resolve("dependencyresolver").resolve("cache"), settings.cacheTtl());
        PlatformRegistry registry = new PlatformRegistry(
                new CurseForgeClient(http, cache, settings.curseForgeBase(), settings.curseForgeApiKey()),
                new ModrinthClient(http, cache, settings.modrinthBase())
        );
        FlowController flow = new FlowController(settings, environment, registry, http);
        boolean graphical = !settings.autoInstall() && !GraphicsEnvironment.isHeadless();
        return new BootstrapGate(flow, graphical);
    }

    public GateResult run() {
        if (forced != null) {
            return forced;
        }
        if (flow == null) {
            return GateResult.abort("Dependency Resolver could not start.", 2);
        }
        FlowController.Snapshot snapshot = flow.inspect();
        if (!snapshot.plan().needsUser()) {
            ResolverLog.info("All required dependencies are satisfied. Continuing into Forge initialization.");
            ResolverLog.info("Loading state: " + LoadingState.READY_TO_LAUNCH);
            return GateResult.ready();
        }
        if (!graphical) {
            if (!flow.settings().autoInstall()) {
                ResolverLog.info("Loading state: " + LoadingState.ABORTED);
                return GateResult.abort(ReportFormatter.body(snapshot), 2);
            }
            try {
                FlowController.InstallReport report = flow.install(snapshot, new FlowController.Progress() {
                    @Override
                    public void status(String message) {
                        ResolverLog.info(message);
                    }

                    @Override
                    public void bytes(long read, long total) {
                    }
                });
                if (report.ready() && flow.readyToLaunch()) {
                    ResolverLog.info("Loading state: " + LoadingState.READY_TO_LAUNCH);
                    return GateResult.ready();
                }
                ResolverLog.info("Loading state: " + LoadingState.ABORTED);
                String message = report.message();
                if (message.isBlank()) {
                    message = ReportFormatter.body(flow.inspect());
                }
                return GateResult.abort(message, 2);
            } catch (RuntimeException exception) {
                ResolverLog.error("Dependency installation failed", exception);
                if (Network.isOffline(exception)) {
                    return GateResult.abort("Internet connection unavailable.\n\nThe dependency could not be downloaded.", 2);
                }
                return GateResult.abort(exception.getMessage() == null ? "The dependency could not be downloaded." : exception.getMessage(), 2);
            }
        }
        try {
            return DependencyWindow.show(flow, snapshot);
        } catch (Exception exception) {
            ResolverLog.error("Could not open the dependency window", exception);
            return GateResult.abort("The dependency window could not be opened. " + exception.getMessage(), 1);
        }
    }
}
