package com.dependencyresolver.bootstrap;

import com.dependencyresolver.dependency.Dependency;
import com.dependencyresolver.dependency.DependencyConstraint;
import com.dependencyresolver.dependency.DependencyResolver;
import com.dependencyresolver.dependency.Side;
import com.dependencyresolver.dependency.InstalledMod;
import com.dependencyresolver.dependency.PlatformIds;
import com.dependencyresolver.dependency.VersionRange;
import com.dependencyresolver.download.DownloadManager;
import com.dependencyresolver.download.FileInstaller;
import com.dependencyresolver.download.JarInspector;
import com.dependencyresolver.instance.InstalledModScanner;
import com.dependencyresolver.log.ResolverLog;
import com.dependencyresolver.platform.HttpTransport;
import com.dependencyresolver.platform.Network;
import com.dependencyresolver.platform.PlatformRegistry;
import com.dependencyresolver.platform.RemoteFile;
import com.dependencyresolver.platform.TargetEnvironment;
import com.dependencyresolver.ui.ReportFormatter;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

public final class FlowController {
    private final Settings settings;
    private final TargetEnvironment environment;
    private final Path modsDir;
    private final InstalledModScanner scanner;
    private final DependencyResolver resolver;
    private final PlatformRegistry registry;
    private final DownloadManager downloads;
    private final JarInspector inspector;
    private final FileInstaller installer;
    private final ReloadController reload;

    public FlowController(Settings settings, TargetEnvironment environment, PlatformRegistry registry, HttpTransport http) {
        this.settings = settings;
        this.environment = environment;
        this.modsDir = settings.gameDir().resolve("mods");
        this.scanner = new InstalledModScanner();
        this.resolver = new DependencyResolver();
        this.registry = registry;
        this.downloads = new DownloadManager(http, settings.gameDir().resolve("config").resolve("dependencyresolver").resolve("tmp"));
        this.inspector = new JarInspector();
        this.installer = new FileInstaller(scanner);
        this.reload = new ReloadController(scanner, resolver, modsDir, environment.side());
    }

    public Settings settings() {
        return settings;
    }

    public TargetEnvironment environment() {
        return environment;
    }

    public Snapshot inspect() {
        ResolverLog.info("Loading state: " + LoadingState.SCANNING);
        ResolverLog.info("Game directory: " + settings.gameDir());
        ResolverLog.info("Mods directory: " + modsDir);
        ResolverLog.info("Target Minecraft " + environment.minecraftVersion() + " loader " + environment.loader().id() + " side " + environment.side());
        ResolverLog.info(settings.curseForgeApiKey().isBlank()
                ? "CurseForge: public catalog"
                : "CurseForge API key: configured");
        List<InstalledMod> installed = scanner.scan(modsDir);
        ResolverLog.info("Discovered " + installed.size() + " mod entries");
        for (InstalledMod mod : installed) {
            ResolverLog.info("Mod " + mod.modId() + " " + mod.version().raw() + " file=" + mod.path().getFileName()
                    + (mod.embedded() ? " embedded" : ""));
            for (DependencyConstraint constraint : mod.dependencies()) {
                if (constraint.mandatory()) {
                    ResolverLog.info("  requires " + constraint.modId() + " " + constraint.range().format()
                            + " raw=" + constraint.rawRange() + " side=" + constraint.side());
                }
            }
        }
        DependencyResolver.Plan plan = includePlatformRequirements(installed, resolver.plan(installed, environment.side()));
        ResolverLog.info("Dependency graph:\n" + plan.graph().toTreeString());
        for (Dependency dependency : plan.dependencies()) {
            ResolverLog.info("Constraint " + dependency.modId() + " " + dependency.status() + " " + dependency.range().format());
        }
        Map<String, RemoteFile> choices = new LinkedHashMap<>();
        boolean offline = false;
        String detail = "";
        for (Dependency dependency : plan.actionable()) {
            PlatformRegistry.Lookup lookup = registry.lookup(dependency.modId(), dependency.range(), environment);
            if (lookup.offline()) {
                offline = true;
                detail = lookup.detail();
            }
            lookup.candidates().stream().findFirst().ifPresent(file -> {
                choices.put(dependency.modId(), file);
                ResolverLog.info("Selected " + dependency.modId() + " " + (file.version() == null ? file.fileName() : file.version().raw())
                        + " from " + file.platform().displayName());
            });
            if (!choices.containsKey(dependency.modId())) {
                ResolverLog.info("No compatible file selected for " + dependency.modId() + ". " + lookup.detail());
                if (!lookup.detail().isBlank()) {
                    detail = lookup.detail();
                }
            }
        }
        return new Snapshot(installed, plan, choices, offline, detail);
    }

    public InstallReport install(Snapshot snapshot, Progress progress) {
        ResolverLog.info("Loading state: " + LoadingState.DOWNLOADING);
        List<String> installed = new ArrayList<>();
        List<String> failed = new ArrayList<>();
        Map<String, VersionRange> queued = new LinkedHashMap<>();
        snapshot.plan().actionable().forEach(dependency -> queued.put(dependency.modId(), dependency.range()));
        Set<String> finished = new LinkedHashSet<>();
        boolean offline = false;
        while (!queued.isEmpty() && finished.size() < 40) {
            String modId = queued.keySet().iterator().next();
            VersionRange hinted = queued.remove(modId);
            if (!finished.add(modId)) {
                continue;
            }
            DependencyResolver.Plan current = resolver.plan(scanner.scan(modsDir), environment.side());
            Dependency dependency = current.byId(modId).orElse(null);
            VersionRange required = dependency == null ? hinted : dependency.range();
            if (dependency != null && dependency.status() == Dependency.Status.SATISFIED) {
                continue;
            }
            if (dependency != null && dependency.status() == Dependency.Status.CONFLICT) {
                failed.add(new com.dependencyresolver.dependency.DependencyConflict(modId, dependency.constraints()).summary());
                continue;
            }
            if (dependency != null && dependency.status() == Dependency.Status.DUPLICATE) {
                try {
                    installer.moveIncompatible(modId, dependency.range(), modsDir);
                    installed.add(modId + " " + dependency.providers().stream()
                            .filter(mod -> dependency.range().contains(mod.version()))
                            .map(mod -> mod.version().raw())
                            .findFirst()
                            .orElse(""));
                } catch (IOException exception) {
                    failed.add("The downloaded file could not be installed into the current instance.");
                    ResolverLog.error("Could not move incompatible " + modId, exception);
                }
                continue;
            }
            PlatformRegistry.Lookup lookup = registry.lookup(modId, required, environment);
            if (lookup.offline() && lookup.candidates().isEmpty()) {
                offline = true;
                failed.add("Internet connection unavailable.");
                continue;
            }
            if (lookup.candidates().isEmpty()) {
                String missing = dependency == null
                        ? "No Forge " + environment.minecraftVersion() + " version of " + modId + " satisfying " + required.format() + " was found."
                        : ReportFormatter.unavailable(dependency, environment.minecraftVersion(), "Forge");
                if (!lookup.detail().isBlank()) {
                    missing = missing + "\n\n" + lookup.detail();
                }
                failed.add(missing);
                continue;
            }
            String display = lookup.candidates().get(0).displayName();
            boolean placed = false;
            String lastError = "Could not download " + display + ".";
            int attempts = 0;
            for (RemoteFile candidate : lookup.candidates()) {
                if (attempts++ >= 6) {
                    break;
                }
                Path temp = null;
                try {
                    if (progress != null) {
                        progress.status("Downloading " + display + "...");
                    }
                    temp = downloads.download(candidate, (read, total) -> {
                        if (progress != null) {
                            progress.bytes(read, total);
                        }
                    });
                    if (progress != null) {
                        progress.status("✓ Download complete");
                        progress.status("Installing " + display + "...");
                    }
                    ResolverLog.info("Loading state: " + LoadingState.INSTALLING);
                    List<JarInspector.Inspected> inspectedMods = inspector.inspectAll(temp, environment.loader());
                    JarInspector.Inspected inspected = inspectedMods.stream()
                            .filter(mod -> com.dependencyresolver.platform.ProjectIds.same(mod.modId(), modId))
                            .findFirst()
                            .orElse(null);
                    String problem = inspected == null
                            ? "Downloaded project " + candidate.projectSlug() + " does not contain mod '" + modId + "'."
                            : validate(candidate, inspected, required, modId);
                    if (problem != null) {
                        lastError = problem;
                        ResolverLog.info("Rejected " + candidate.fileName() + ": " + problem);
                        Files.deleteIfExists(temp);
                        continue;
                    }
                    installer.install(temp, candidate.fileName(), inspected.modId(), required, modsDir);
                    enqueuePlatformRequirements(inspected, queued, finished);
                    if (progress != null) {
                        progress.status("✓ " + inspected.displayName() + " installed");
                    }
                    installed.add(inspected.displayName() + " " + inspected.version().raw());
                    placed = true;
                    enqueueTransitive(inspected, queued, finished);
                    break;
                } catch (IOException exception) {
                    lastError = Network.isOffline(exception)
                            ? "Internet connection unavailable."
                            : userMessage(exception, display);
                    ResolverLog.error("Install attempt failed for " + modId, exception);
                    if (Network.isOffline(exception)) {
                        offline = true;
                        break;
                    }
                    if (temp != null) {
                        try {
                            Files.deleteIfExists(temp);
                        } catch (IOException ignored) {
                            ResolverLog.debug("Could not delete temp file " + temp);
                        }
                    }
                }
            }
            if (!placed) {
                failed.add(lastError);
            }
        }
        boolean ready = reload.dependenciesSatisfied();
        if (ready) {
            ResolverLog.info("Loading state: " + LoadingState.READY_TO_LAUNCH);
        }
        return new InstallReport(installed, failed, offline, ready);
    }

    public boolean repairDuplicates() {
        DependencyResolver.Plan plan = resolver.plan(scanner.scan(modsDir), environment.side());
        boolean changed = false;
        for (Dependency dependency : plan.dependencies()) {
            if (dependency.status() != Dependency.Status.DUPLICATE) {
                continue;
            }
            try {
                installer.moveIncompatible(dependency.modId(), dependency.range(), modsDir);
                changed = true;
            } catch (IOException exception) {
                ResolverLog.error("Could not move incompatible copies of " + dependency.modId(), exception);
                return false;
            }
        }
        return changed || reload.dependenciesSatisfied();
    }

    public boolean readyToLaunch() {
        return reload.dependenciesSatisfied();
    }

    private DependencyResolver.Plan includePlatformRequirements(List<InstalledMod> installed, DependencyResolver.Plan plan) {
        List<Dependency> dependencies = new ArrayList<>(plan.dependencies());
        List<Dependency> extras = new ArrayList<>();
        boolean noted = false;
        for (InstalledMod mod : installed) {
            if (mod.embedded()) {
                continue;
            }
            for (String required : platformRequirements(mod.modId(), mod.version())) {
                if (PlatformIds.skip(required) || installed.stream().anyMatch(present -> com.dependencyresolver.platform.ProjectIds.same(present.modId(), required))) {
                    continue;
                }
                int existing = indexOfSame(dependencies, required);
                if (existing >= 0) {
                    Dependency updated = dependencies.get(existing).withRequester(mod.modId());
                    if (updated != dependencies.get(existing)) {
                        dependencies.set(existing, updated);
                        noted = true;
                        ResolverLog.info(mod.modId() + " also requires " + dependencies.get(existing).modId() + ".");
                    }
                    continue;
                }
                ResolverLog.info(mod.modId() + " lists " + required + " as a required add-on on CurseForge or Modrinth.");
                Dependency added = new Dependency(
                        required,
                        List.of(new DependencyConstraint(required, mod.modId(), true, VersionRange.any(), "", Side.BOTH)),
                        VersionRange.any(),
                        Dependency.Status.MISSING,
                        List.of()
                );
                extras.add(added);
                dependencies.add(added);
            }
        }
        if (extras.isEmpty() && !noted) {
            return plan;
        }
        return new DependencyResolver.Plan(dependencies, plan.conflicts(), com.dependencyresolver.dependency.DependencyGraph.build(installed, dependencies));
    }

    private static int indexOfSame(List<Dependency> dependencies, String required) {
        for (int i = 0; i < dependencies.size(); i++) {
            if (com.dependencyresolver.platform.ProjectIds.same(dependencies.get(i).modId(), required)) {
                return i;
            }
        }
        return -1;
    }

    private void enqueuePlatformRequirements(JarInspector.Inspected inspected, Map<String, VersionRange> queued, Set<String> finished) {
        for (String required : platformRequirements(inspected.modId(), inspected.version())) {
            if (PlatformIds.skip(required) || finished.contains(required) || queued.containsKey(required)) {
                continue;
            }
            boolean present = scanner.scan(modsDir).stream()
                    .anyMatch(mod -> com.dependencyresolver.platform.ProjectIds.same(mod.modId(), required));
            if (present) {
                continue;
            }
            ResolverLog.info("Platform lists " + required + " as required by " + inspected.modId());
            queued.put(required, VersionRange.any());
        }
    }

    private List<String> platformRequirements(String modId, com.dependencyresolver.dependency.MavenVersion version) {
        try {
            Optional<List<String>> required = registry.requiredModIds(modId, version, environment);
            return required.orElse(List.of());
        } catch (IOException exception) {
            ResolverLog.error("Could not read platform dependencies for " + modId, exception);
            return List.of();
        }
    }

    private void enqueueTransitive(JarInspector.Inspected inspected, Map<String, VersionRange> queued, Set<String> finished) {
        for (DependencyConstraint constraint : inspected.dependencies()) {
            if (!constraint.mandatory() || !constraint.appliesTo(environment.side()) || PlatformIds.skip(constraint.modId())) {
                continue;
            }
            if (finished.contains(constraint.modId()) || queued.containsKey(constraint.modId())) {
                continue;
            }
            DependencyResolver.Plan plan = resolver.plan(scanner.scan(modsDir), environment.side());
            Dependency dependency = plan.byId(constraint.modId()).orElse(null);
            if (dependency == null || dependency.needsDownload()) {
                ResolverLog.info("Transitive dependency " + inspected.modId() + " requires " + constraint.modId() + " " + constraint.range().format());
                queued.put(constraint.modId(), dependency == null ? constraint.range() : dependency.range());
            }
        }
    }

    private String validate(RemoteFile candidate, JarInspector.Inspected inspected, VersionRange required, String modId) {
        if (!inspected.modId().equals(modId)) {
            return "Downloaded project " + candidate.projectSlug() + " is mod '" + inspected.modId() + "', not '" + modId + "'.";
        }
        if (inspected.modLoader() != null && !inspected.modLoader().isBlank() && !environment.loader().acceptsModLoader(inspected.modLoader())) {
            return "The downloaded file is not a Forge mod.";
        }
        if (!inspected.supportsMinecraft(environment.minecraftVersion())) {
            return "The downloaded file does not support Minecraft " + environment.minecraftVersion() + ".";
        }
        if (!required.contains(inspected.version())) {
            return "The downloaded version " + inspected.version().raw() + " does not satisfy " + required.format() + ".";
        }
        if (candidate.version() != null && !candidate.version().equals(inspected.version())) {
            ResolverLog.info("Platform version " + candidate.version().raw() + " differed from the JAR version " + inspected.version().raw() + ". Using the JAR.");
        }
        return null;
    }

    private static String userMessage(IOException exception, String display) {
        String message = exception.getMessage() == null ? "" : exception.getMessage();
        if (message.startsWith("Could not download") || message.startsWith("The downloaded")) {
            return message;
        }
        return "Could not download " + display + ".";
    }

    public interface Progress {
        void status(String message);

        void bytes(long read, long total);
    }

    public record Snapshot(
            List<InstalledMod> installed,
            DependencyResolver.Plan plan,
            Map<String, RemoteFile> choices,
            boolean offline,
            String detail
    ) {
        public Snapshot {
            installed = installed == null ? List.of() : List.copyOf(installed);
            choices = choices == null ? Map.of() : Map.copyOf(choices);
            detail = detail == null ? "" : detail;
        }

        public RemoteFile choice(String modId) {
            return choices.get(modId);
        }
    }

    public record InstallReport(List<String> installed, List<String> failed, boolean offline, boolean ready) {
        public InstallReport {
            installed = installed == null ? List.of() : List.copyOf(installed);
            failed = failed == null ? List.of() : List.copyOf(failed);
        }

        public String message() {
            if (offline && installed.isEmpty()) {
                return "Internet connection unavailable.\n\nThe dependency could not be downloaded.";
            }
            StringBuilder builder = new StringBuilder();
            if (!failed.isEmpty()) {
                builder.append(failed.get(0));
            }
            if (!installed.isEmpty()) {
                if (!builder.isEmpty()) {
                    builder.append("\n\n");
                }
                builder.append("Installed:\n");
                installed.forEach(line -> builder.append("✓ ").append(line).append('\n'));
            }
            return builder.toString().stripTrailing();
        }
    }
}
