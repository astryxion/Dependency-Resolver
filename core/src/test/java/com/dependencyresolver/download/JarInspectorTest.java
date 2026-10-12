package com.dependencyresolver.download;

import com.dependencyresolver.dependency.MavenVersion;
import com.dependencyresolver.instance.ModsTomlParser;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JarInspectorTest {
    @Test
    void citadelDeclaresForge47AndNoMinecraftDependency() {
        String toml = """
                modLoader="javafml"
                loaderVersion="[47,)"
                [[mods]]
                modId="citadel"
                version="2.6.3"
                displayName="Citadel"
                [[dependencies.citadel]]
                    modId="forge"
                    mandatory=true
                    versionRange="[47.0.34,)"
                    ordering="NONE"
                    side="BOTH"
                """;
        assertTrue(inspect(toml).supportsMinecraft("1.20.1"));
    }

    @Test
    void minecraftRangeForAnotherVersionIsRejected() {
        String toml = """
                modLoader="javafml"
                loaderVersion="[47,)"
                [[mods]]
                modId="example"
                version="1.0.0"
                [[dependencies.example]]
                    modId="minecraft"
                    mandatory=true
                    versionRange="[1.21,1.22)"
                    side="BOTH"
                """;
        assertFalse(inspect(toml).supportsMinecraft("1.20.1"));
    }

    @Test
    void forge48IsNotMinecraft1201() {
        String toml = """
                modLoader="javafml"
                loaderVersion="[48,)"
                [[mods]]
                modId="example"
                version="1.0.0"
                [[dependencies.example]]
                    modId="forge"
                    mandatory=true
                    versionRange="[48,)"
                    side="BOTH"
                """;
        assertFalse(inspect(toml).supportsMinecraft("1.20.1"));
    }

    private static JarInspector.Inspected inspect(String toml) {
        ModsTomlParser.ParsedMod parsed = new ModsTomlParser().parse(toml, "1.0.0").get(0);
        return new JarInspector.Inspected(
                parsed.modId(),
                parsed.displayName(),
                MavenVersion.parse(parsed.version()),
                parsed.modLoader(),
                parsed.loaderVersion(),
                parsed.dependencies()
        );
    }
}
