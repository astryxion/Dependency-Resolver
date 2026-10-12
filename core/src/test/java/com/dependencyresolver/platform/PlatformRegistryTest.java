package com.dependencyresolver.platform;

import com.dependencyresolver.dependency.MavenVersion;
import com.dependencyresolver.dependency.VersionRange;
import com.dependencyresolver.log.ResolverLog;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlatformRegistryTest {
    @AfterEach
    void resetLog() {
        ResolverLog.reset();
    }

    @Test
    void curseForgeSuccessDoesNotQueryModrinth() throws IOException {
        ResolverLog.init(Files.createTempDirectory("resolver-log"), false);
        RoutingHttp http = new RoutingHttp();
        http.body("/v1/mods/search", """
                {"data":[{"id":123,"name":"GeckoLib","slug":"geckolib"}]}
                """);
        http.body("/v1/mods/123/files", files("1.2.5", "1.2.6", "1.2.7", "1.2.8", "1.2.9", "1.3.0"));
        PlatformRegistry registry = registry(http, true);
        PlatformRegistry.Lookup lookup = registry.lookup("geckolib", VersionRange.parse("> 1.2.6 and < 1.2.9"), TargetEnvironment.forgeClient());
        assertEquals("1.2.8", lookup.candidates().get(0).version().raw());
        assertEquals(ModPlatform.CURSEFORGE, lookup.candidates().get(0).platform());
        assertTrue(lookup.curseForgeTried());
        assertFalse(lookup.modrinthTried());
        assertTrue(http.paths.stream().noneMatch(path -> path.startsWith("/v2/")));
    }

    @Test
    void curseForgeMissFallsBackToModrinth() throws IOException {
        ResolverLog.init(Files.createTempDirectory("resolver-log"), false);
        RoutingHttp http = new RoutingHttp();
        http.body("/v1/mods/search", "{\"data\":[]}");
        http.body("/v2/project/geckolib", """
                {"id":"abc","slug":"geckolib","title":"GeckoLib"}
                """);
        http.body("/v2/project/abc/version", """
                [{"version_number":"1.2.8","version_type":"release","game_versions":["1.20.1"],"loaders":["forge"],
                  "files":[{"filename":"geckolib-1.20.1-1.2.8.jar","url":"https://cdn.example/geckolib.jar","primary":true,"hashes":{"sha1":"abc"}}]}]
                """);
        PlatformRegistry registry = registry(http, true);
        PlatformRegistry.Lookup lookup = registry.lookup("geckolib", VersionRange.parse("(1.2.6,1.2.9)"), TargetEnvironment.forgeClient());
        assertEquals(ModPlatform.MODRINTH, lookup.candidates().get(0).platform());
        assertEquals("1.2.8", lookup.candidates().get(0).version().raw());
        assertTrue(lookup.curseForgeTried());
        assertTrue(lookup.modrinthTried());
        assertTrue(http.paths.get(0).startsWith("/v1/"));
        assertTrue(http.paths.stream().anyMatch(path -> path.startsWith("/v2/")));
    }

    @Test
    void modrinthUsesTheForgeProjectWhenThePlainSlugHasNoForgeFiles() throws IOException {
        ResolverLog.init(Files.createTempDirectory("resolver-log"), false);
        RoutingHttp http = new RoutingHttp();
        http.body("/v1/mods/search", "{\"data\":[]}");
        http.body("/v2/project/bclib", """
                {"id":"fabric","slug":"bclib","title":"BCLib"}
                """);
        http.body("/v2/project/fabric/version", "[]");
        http.body("/v2/project/bclib-forge", """
                {"id":"forgeproj","slug":"bclib-forge","title":"BCLib Forge"}
                """);
        http.body("/v2/project/forgeproj/version", """
                [{"version_number":"20.0.13","version_type":"release","game_versions":["1.20.1"],"loaders":["forge"],
                  "files":[{"filename":"bclib-20.0.13.jar","url":"https://cdn.example/bclib.jar","primary":true,"hashes":{"sha1":"abc"}}]}]
                """);
        PlatformRegistry registry = registry(http, true);
        PlatformRegistry.Lookup lookup = registry.lookup("bclib", VersionRange.parse("[20.0.12,)"), TargetEnvironment.forgeClient());
        assertEquals("20.0.13", lookup.candidates().get(0).version().raw());
        assertEquals(ModPlatform.MODRINTH, lookup.candidates().get(0).platform());
        assertEquals("BCLib Forge", lookup.candidates().get(0).displayName());
    }

    @Test
    void publicCatalogFindsTheForgeProjectWithoutAnApiKey() throws IOException {
        ResolverLog.init(Files.createTempDirectory("resolver-log"), false);
        RoutingHttp http = new RoutingHttp();
        http.body("/minecraft/mc-mods/bclib", """
                {"id":495191,"title":"BCLib","versions":{"1.20.1":[
                  {"id":1,"name":"bclib-3.0.14.jar","display":"bclib 3.0.14","type":"release",
                   "versions":["Fabric","1.20.1"],"uploaded_at":"2024-01-01T00:00:00Z"}
                ]}}
                """);
        http.body("/minecraft/mc-mods/bclib-forge", """
                {"id":1435106,"title":"BCLib Forge","versions":{"1.20.1":[
                  {"id":8590585,"name":"BCLib-20.0.13.jar","display":"BCLib 20.0.13","type":"release",
                   "versions":["Forge","1.20.1"],"uploaded_at":"2026-08-06T20:24:42.077Z"}
                ]}}
                """);
        http.body("/api/v1/mods/1435106/files/8590585/dependencies", """
                {"data":[{"id":1435097,"slug":"wunderlib-forge","type":"RequiredDependency"},
                         {"id":9,"slug":"optional-lib","type":"OptionalDependency"}],
                 "pagination":{"index":0,"pageSize":50,"totalCount":2}}
                """);
        PlatformRegistry registry = registry(http, false);
        PlatformRegistry.Lookup lookup = registry.lookup("bclib", VersionRange.parse("[20.0.12,)"), TargetEnvironment.forgeClient());
        assertEquals("20.0.13", lookup.candidates().get(0).version().raw());
        assertEquals(ModPlatform.CURSEFORGE, lookup.candidates().get(0).platform());
        assertEquals("https://www.curseforge.com/api/v1/mods/1435106/files/8590585/download", lookup.candidates().get(0).downloadUrl());
        assertTrue(lookup.curseForgeTried());
        assertFalse(lookup.modrinthTried());
        assertEquals(List.of("wunderlib"), registry.requiredModIds("bclib", MavenVersion.parse("20.0.13"), TargetEnvironment.forgeClient()).orElseThrow());
        assertTrue(http.paths.stream().noneMatch(path -> path.startsWith("/v2/")));
    }

    @Test
    void curseForgeOnlyModsAreFoundWhenTheSlugDiffersFromTheModId() throws IOException {
        ResolverLog.init(Files.createTempDirectory("resolver-log"), false);
        RoutingHttp http = new RoutingHttp();
        http.body("/v1/mods/search", """
                {"data":[{"id":1541854,"name":"Multi Mob","slug":"multi-mob"}]}
                """);
        http.body("/v1/mods/1541854/files", """
                {"data":[{"id":8576683,"fileName":"multimobforge-1.20.1-1.0.3.jar","displayName":"Multi Mob 1.0.3",
                  "gameVersions":["1.20.1","Forge"],"releaseType":1,"fileDate":"2026-08-01T00:00:00Z"}]}
                """);
        PlatformRegistry registry = registry(http, false);
        PlatformRegistry.Lookup lookup = registry.lookup("multimob", VersionRange.parse("[1.0.0,)"), TargetEnvironment.forgeClient());
        assertEquals("1.0.3", lookup.candidates().get(0).version().raw());
        assertEquals(ModPlatform.CURSEFORGE, lookup.candidates().get(0).platform());
        assertFalse(lookup.modrinthTried());
    }

    @Test
    void curseForgePageRequirementsAreListedForTheInstalledFile() throws IOException {
        ResolverLog.init(Files.createTempDirectory("resolver-log"), false);
        RoutingHttp http = new RoutingHttp();
        http.body("/v1/mods/search", """
                {"data":[{"id":963163,"name":"Craziness Awakened (Orespawn Remake)","slug":"craziness-awakened"}]}
                """);
        http.body("/v1/mods/963163/files", """
                {"data":[{"id":7430459,"fileName":"craziness_awakened-7.0-forge-1.20.1.jar","displayName":"Craziness Awakened 7.0",
                  "gameVersions":["1.20.1","Forge"],"releaseType":1,"fileDate":"2025-07-14T00:00:00Z"}]}
                """);
        http.body("/api/v1/mods/963163/files/7430459/dependencies", """
                {"data":[{"slug":"attributefix","type":"RequiredDependency","name":"AttributeFix"},
                         {"slug":"geckolib","type":"RequiredDependency","name":"GeckoLib"},
                         {"slug":"pehkui","type":"RequiredDependency","name":"Pehkui"}],
                 "pagination":{"index":0,"pageSize":50,"totalCount":3}}
                """);
        PlatformRegistry registry = registry(http, false);
        assertEquals(List.of("attributefix", "geckolib", "pehkui"),
                registry.requiredModIds("craziness_awakened", MavenVersion.parse("7.0"), TargetEnvironment.forgeClient()).orElseThrow());
    }

    @Test
    void aDifferentProjectsAddonsAreNotAppliedToTheInstalledJar() throws IOException {
        ResolverLog.init(Files.createTempDirectory("resolver-log"), false);
        RoutingHttp http = new RoutingHttp();
        http.body("/minecraft/mc-mods/primitivemobs", """
                {"id":282415,"title":"Primitive Mobs","versions":{"1.20.1":[
                  {"id":9,"name":"primitivemobs-1.20.1-2.0.jar","display":"2.0","type":"release",
                   "versions":["Forge","1.20.1"],"uploaded_at":"2024-01-01T00:00:00Z"}
                ]}}
                """);
        http.body("/api/v1/mods/282415/files/9/dependencies", """
                {"data":[{"slug":"geckolib","type":"RequiredDependency"}],
                 "pagination":{"index":0,"pageSize":50,"totalCount":1}}
                """);
        http.status("/v2/project/primitivemobs", 404);
        http.status("/v2/project/primitivemobs-forge", 404);
        http.body("/v2/search", "{\"hits\":[]}");
        PlatformRegistry registry = registry(http, false);
        assertTrue(registry.requiredModIds("primitivemobs", MavenVersion.parse("1.20.1-1.2.0"), TargetEnvironment.forgeClient()).isEmpty());
        assertTrue(http.paths.stream().noneMatch(path -> path.contains("/dependencies")));
    }

    @Test
    void neitherPlatformHasACompatibleFile() throws IOException {
        ResolverLog.init(Files.createTempDirectory("resolver-log"), false);
        RoutingHttp http = new RoutingHttp();
        http.body("/v1/mods/search", "{\"data\":[]}");
        http.status("/v2/project/examplelib", 404);
        http.body("/v2/search", "{\"hits\":[]}");
        PlatformRegistry registry = registry(http, true);
        PlatformRegistry.Lookup lookup = registry.lookup("examplelib", VersionRange.parse(">=4.2.0"), TargetEnvironment.forgeClient());
        assertTrue(lookup.candidates().isEmpty());
        assertTrue(lookup.curseForgeTried());
        assertTrue(lookup.modrinthTried());
        assertFalse(lookup.offline());
    }

    private static PlatformRegistry registry(RoutingHttp http, boolean withKey) {
        return new PlatformRegistry(
                new CurseForgeClient(http, null, "https://api.curseforge.test", withKey ? "test-key" : ""),
                new ModrinthClient(http, null, "https://api.modrinth.test")
        );
    }

    private static String files(String... versions) {
        StringBuilder builder = new StringBuilder("{\"data\":[");
        for (int i = 0; i < versions.length; i++) {
            if (i > 0) {
                builder.append(',');
            }
            String version = versions[i];
            builder.append("""
                    {"id":%d,"modId":123,"displayName":"GeckoLib %s","fileName":"geckolib-1.20.1-%s.jar",
                     "downloadUrl":"https://edge.example/%s.jar","gameVersions":["1.20.1","Forge"],
                     "releaseType":1,"fileDate":"2024-01-0%dT00:00:00Z","hashes":[]}
                    """.formatted(i + 1, version, version, version, Math.min(i + 1, 9)));
        }
        return builder.append("]}").toString();
    }

    static final class RoutingHttp implements HttpTransport {
        final List<String> paths = new ArrayList<>();
        private final Map<String, byte[]> bodies = new java.util.LinkedHashMap<>();
        private final Map<String, Integer> statuses = new java.util.LinkedHashMap<>();

        void body(String path, String json) {
            bodies.put(path, json.getBytes(StandardCharsets.UTF_8));
        }

        void status(String path, int status) {
            statuses.put(path, status);
        }

        @Override
        public Response send(Request request) {
            String path = request.uri().getPath();
            paths.add(path);
            int status = statuses.getOrDefault(path, bodies.containsKey(path) ? 200 : 404);
            byte[] body = bodies.getOrDefault(path, "{}".getBytes(StandardCharsets.UTF_8));
            if (request.downloadTo() != null && status >= 200 && status < 300) {
                try {
                    Files.createDirectories(request.downloadTo().getParent());
                    Files.write(request.downloadTo(), body);
                } catch (IOException exception) {
                    throw new RuntimeException(exception);
                }
                return new Response(status, new byte[0], Map.of());
            }
            return new Response(status, body, Map.of());
        }
    }
}
