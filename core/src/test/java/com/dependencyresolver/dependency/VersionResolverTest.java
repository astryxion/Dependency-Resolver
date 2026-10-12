package com.dependencyresolver.dependency;

import com.dependencyresolver.bootstrap.FlowController;
import com.dependencyresolver.platform.ModPlatform;
import com.dependencyresolver.platform.RemoteFile;
import com.dependencyresolver.ui.ReportFormatter;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VersionResolverTest {
    private final DependencyResolver resolver = new DependencyResolver();

    @Test
    void exactVersionMatchesOnlyThatVersion() {
        VersionRange range = VersionRange.parse("1.2.8");
        assertTrue(range.contains(MavenVersion.parse("1.2.8")));
        assertTrue(range.contains(MavenVersion.parse("1.2.8.0")));
        assertFalse(range.contains(MavenVersion.parse("1.2.9")));
        assertEquals("1.2.8", resolver.selectVersion(range, versions("1.2.7", "1.2.8", "1.2.9")).orElseThrow().raw());
    }

    @Test
    void minimumMaximumAndBracketRanges() {
        assertTrue(VersionRange.parse(">=2.6.0").contains(MavenVersion.parse("2.6.0")));
        assertTrue(VersionRange.parse(">=2.6.0").contains(MavenVersion.parse("2.6.1")));
        assertFalse(VersionRange.parse(">=2.6.0").contains(MavenVersion.parse("2.5.9")));
        assertEquals("2.6.0+", VersionRange.parse(">=2.6.0").format());

        assertTrue(VersionRange.parse("<=1.2.8").contains(MavenVersion.parse("1.2.8")));
        assertFalse(VersionRange.parse("<=1.2.8").contains(MavenVersion.parse("1.2.9")));

        VersionRange inclusive = VersionRange.parse("[1.2.7,1.2.9]");
        assertTrue(inclusive.contains(MavenVersion.parse("1.2.7")));
        assertTrue(inclusive.contains(MavenVersion.parse("1.2.9")));
        assertFalse(inclusive.contains(MavenVersion.parse("1.2.6")));

        VersionRange exclusive = VersionRange.parse("[1.5.0,2.0.0)");
        assertTrue(exclusive.contains(MavenVersion.parse("1.5.0")));
        assertTrue(exclusive.contains(MavenVersion.parse("1.9.9")));
        assertFalse(exclusive.contains(MavenVersion.parse("2.0.0")));

        VersionRange open = VersionRange.parse("(1.2.6,1.2.9)");
        assertFalse(open.contains(MavenVersion.parse("1.2.6")));
        assertTrue(open.contains(MavenVersion.parse("1.2.7")));
        assertFalse(open.contains(MavenVersion.parse("1.2.9")));
        assertTrue(VersionRange.parse("[47,)").contains(MavenVersion.parse("47.4.10")));
    }

    @Test
    void geckolibExclusiveRangeSelectsNewestCompatible() {
        Optional<MavenVersion> selected = resolver.selectNewest(
                List.of(VersionRange.parse("> 1.2.6 and < 1.2.9")),
                List.of("1.2.5", "1.2.6", "1.2.7", "1.2.8", "1.2.9", "1.3.0"));
        assertEquals("1.2.8", selected.orElseThrow().raw());
    }

    @Test
    void combinedConstraintsFromTwoModsSelectOneVersion() {
        VersionRange combined = VersionRange.parse(">=1.2.7").intersect(VersionRange.parse("<1.2.9"));
        Optional<MavenVersion> selected = resolver.selectVersion(combined, versions("1.2.6", "1.2.7", "1.2.8", "1.2.9"));
        assertEquals("1.2.8", selected.orElseThrow().raw());
    }

    @Test
    void impossibleConstraintsAreEmpty() {
        VersionRange combined = VersionRange.parse(">=1.3.0").intersect(VersionRange.parse("<1.2.9"));
        assertTrue(combined.isEmpty());
        assertTrue(resolver.selectVersion(combined, versions("1.2.8", "1.3.0")).isEmpty());
    }

    @Test
    void preReleaseSortsBeforeTheRelease() {
        assertTrue(MavenVersion.parse("1.0-alpha").compareTo(MavenVersion.parse("1.0")) < 0);
        assertTrue(MavenVersion.parse("1.2.8-beta").compareTo(MavenVersion.parse("1.2.8")) < 0);
        assertEquals(0, MavenVersion.parse("1").compareTo(MavenVersion.parse("1.0.0")));
        assertTrue(MavenVersion.parse("1.2.10").compareTo(MavenVersion.parse("1.2.9")) > 0);
    }

    @Test
    void missingInstalledAndWrongVersions() {
        InstalledMod alex = mod("alexsmobs", "Alex's Mobs", "1.22.0", ">=2.6.0", "citadel");
        DependencyResolver.Plan missing = resolver.plan(List.of(alex), Side.CLIENT);
        assertEquals(Dependency.Status.MISSING, missing.byId("citadel").orElseThrow().status());

        InstalledMod citadel = mod("citadel", "Citadel", "2.6.1", "[1.20.1,1.21)", "minecraft");
        DependencyResolver.Plan installed = resolver.plan(List.of(alex, citadel), Side.CLIENT);
        assertEquals(Dependency.Status.SATISFIED, installed.byId("citadel").orElseThrow().status());
        assertFalse(installed.needsUser());

        InstalledMod old = mod("citadel", "Citadel", "2.4.0", "[1.20.1,1.21)", "minecraft");
        DependencyResolver.Plan wrong = resolver.plan(List.of(alex, old), Side.CLIENT);
        assertEquals(Dependency.Status.WRONG_VERSION, wrong.byId("citadel").orElseThrow().status());
    }

    @Test
    void compatibleModsShareOneDependencyAndConflictsDoNot() {
        InstalledMod modA = mod("moda", "Mod A", "1.0", ">=1.2.7", "geckolib");
        InstalledMod modB = mod("modb", "Mod B", "1.0", "<1.2.9", "geckolib");
        DependencyResolver.Plan compatible = resolver.plan(List.of(modA, modB), Side.CLIENT);
        assertEquals(">= 1.2.7 and < 1.2.9", compatible.byId("geckolib").orElseThrow().range().format());
        assertTrue(compatible.conflicts().isEmpty());

        InstalledMod high = mod("moda", "Mod A", "1.0", ">=1.3.0", "geckolib");
        InstalledMod low = mod("modb", "Mod B", "1.0", "<1.2.9", "geckolib");
        DependencyResolver.Plan conflict = resolver.plan(List.of(high, low), Side.CLIENT);
        assertEquals(Dependency.Status.CONFLICT, conflict.byId("geckolib").orElseThrow().status());
        assertEquals(1, conflict.conflicts().size());
        assertTrue(conflict.actionable().isEmpty());
    }

    @Test
    void dependencyChainIncludesTheInstalledBridge() {
        InstalledMod alex = mod("alexsmobs", "Alex's Mobs", "1.0", ">=2.6.0", "citadel");
        InstalledMod citadel = new InstalledMod(
                "citadel",
                "Citadel",
                MavenVersion.parse("2.6.1"),
                "javafml",
                "[47,)",
                Path.of("citadel.jar"),
                false,
                List.of(new DependencyConstraint("examplelib", "citadel", true, VersionRange.parse(">=1.0.0"), ">=1.0.0", Side.BOTH))
        );
        DependencyResolver.Plan plan = resolver.plan(List.of(alex, citadel), Side.CLIENT);
        String tree = plan.graph().toTreeString();
        assertTrue(tree.contains("Alex's Mobs"));
        assertTrue(tree.contains("Citadel"));
        assertTrue(tree.contains("examplelib"));
        assertTrue(tree.indexOf("Alex's Mobs") < tree.indexOf("Citadel"));
        assertTrue(tree.indexOf("Citadel") < tree.indexOf("examplelib"));
        assertEquals(Dependency.Status.MISSING, plan.byId("examplelib").orElseThrow().status());
    }

    @Test
    void sharedDependencyNamesEveryModThatRequiresIt() {
        InstalledMod naturalist = mod("naturalist", "Naturalist", "5.0", ">=4.0.0", "geckolib");
        InstalledMod awakened = new InstalledMod(
                "craziness_awakened", "Craziness Awakened", MavenVersion.parse("7.0"),
                "javafml", "[47,)", Path.of("craziness.jar"), false, List.of());
        DependencyResolver.Plan base = resolver.plan(List.of(naturalist, awakened), Side.CLIENT);
        Dependency shared = base.byId("geckolib").orElseThrow().withRequester("craziness_awakened");
        List<Dependency> dependencies = List.of(shared);
        String tree = DependencyGraph.build(List.of(naturalist, awakened), dependencies).toTreeString();
        assertTrue(tree.indexOf("Naturalist") < tree.indexOf("geckolib"));
        assertTrue(tree.indexOf("Craziness Awakened") < tree.lastIndexOf("geckolib"));
        assertEquals(1, dependencies.size());
        assertEquals("naturalist, craziness_awakened", shared.requiredBy());
    }

    @Test
    void platformAddonsStayUnderTheModThatRequiresThem() {
        InstalledMod awakened = new InstalledMod(
                "craziness_awakened", "Craziness Awakened", MavenVersion.parse("7.0"),
                "javafml", "[47,)", Path.of("craziness.jar"), false, List.of());
        InstalledMod jei = new InstalledMod(
                "jei", "Just Enough Items", MavenVersion.parse("15.62.0.219"),
                "javafml", "[47,)", Path.of("jei.jar"), false,
                List.of(new DependencyConstraint("mezz_config", "jei", true, VersionRange.parse("[0.6.3,1.0.0)"), "[0.6.3,1.0.0)", Side.BOTH)));
        InstalledMod embedded = new InstalledMod(
                "mezz_config", "Mezz Config", MavenVersion.parse("0.6.3"),
                "javafml", "[47,)", Path.of("jei.jar"), true, List.of());
        Dependency pehkui = new Dependency(
                "pehkui",
                List.of(new DependencyConstraint("pehkui", "craziness_awakened", true, VersionRange.any(), "", Side.BOTH)),
                VersionRange.any(), Dependency.Status.MISSING, List.of());
        Dependency attributeFix = new Dependency(
                "attributefix",
                List.of(new DependencyConstraint("attributefix", "craziness_awakened", true, VersionRange.any(), "", Side.BOTH)),
                VersionRange.any(), Dependency.Status.MISSING, List.of());
        DependencyResolver.Plan base = resolver.plan(List.of(awakened, jei, embedded), Side.CLIENT);
        List<Dependency> all = new java.util.ArrayList<>(base.dependencies());
        all.add(pehkui);
        all.add(attributeFix);
        DependencyResolver.Plan plan = new DependencyResolver.Plan(all, base.conflicts(), DependencyGraph.build(List.of(awakened, jei, embedded), all));
        assertTrue(com.dependencyresolver.platform.ProjectIds.same("mezzconfig", "mezz_config"));
        assertEquals(Dependency.Status.SATISFIED, plan.byId("mezz_config").orElseThrow().status());
        String tree = plan.graph().toTreeString();
        assertTrue(tree.contains("Craziness Awakened"));
        assertTrue(tree.contains("pehkui"));
        assertTrue(tree.indexOf("Craziness Awakened") < tree.indexOf("pehkui"));
        assertFalse(tree.contains("Just Enough Items"));
        RemoteFile file = new RemoteFile(
                "pehkui", "3.8.2", MavenVersion.parse("3.8.2"), "Pehkui", ModPlatform.CURSEFORGE,
                "1", "pehkui", "Pehkui.jar", "https://example.invalid/pehkui.jar", null, 1, "");
        String body = ReportFormatter.body(new FlowController.Snapshot(List.of(awakened, jei, embedded), plan, Map.of("pehkui", file), false, ""));
        assertTrue(body.contains("Craziness Awakened"));
        assertTrue(body.contains("Pehkui 3.8.2  for Craziness Awakened"));
        assertTrue(body.contains("attributefix  for Craziness Awakened"));
    }

    @Test
    void missingScreenUsesTheRequestedCopy() {
        InstalledMod alex = mod("alexsmobs", "Alex's Mobs", "1.0", ">=2.6.0", "citadel");
        DependencyResolver.Plan plan = resolver.plan(List.of(alex), Side.CLIENT);
        RemoteFile file = new RemoteFile(
                "citadel", "2.6.1", MavenVersion.parse("2.6.1"), "Citadel", ModPlatform.CURSEFORGE,
                "1", "citadel", "citadel-1.20.1-2.6.1.jar", "https://example.invalid/citadel.jar",
                null, 1, "");
        String body = ReportFormatter.body(new FlowController.Snapshot(List.of(alex), plan, Map.of("citadel", file), false, ""));
        assertEquals("Missing Dependency", ReportFormatter.title(new FlowController.Snapshot(List.of(alex), plan, Map.of("citadel", file), false, "")));
        assertTrue(body.contains("Alex's Mobs requires:"));
        assertTrue(body.contains("Citadel"));
        assertTrue(body.contains("2.6.0+"));
        assertTrue(body.contains("2.6.1"));
        assertTrue(body.contains("CurseForge"));
    }

    private static List<MavenVersion> versions(String... values) {
        return java.util.Arrays.stream(values).map(MavenVersion::parse).toList();
    }

    private static InstalledMod mod(String id, String name, String version, String range, String dependency) {
        return new InstalledMod(
                id,
                name,
                MavenVersion.parse(version),
                "javafml",
                "[47,)",
                Path.of(id + ".jar"),
                false,
                List.of(new DependencyConstraint(dependency, id, true, VersionRange.parse(range), range, Side.BOTH))
        );
    }
}
