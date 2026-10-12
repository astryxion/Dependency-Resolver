package com.dependencyresolver.bootstrap;

import com.dependencyresolver.instance.InstalledModScanner;
import com.dependencyresolver.log.ResolverLog;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BootstrapFlowTest {
    private final List<String> properties = List.of(
            "dependencyresolver.gameDir",
            "dependencyresolver.autoInstall",
            "dependencyresolver.curseforgeApiKey",
            "dependencyresolver.curseforgeBase",
            "dependencyresolver.modrinthBase"
    );

    @AfterEach
    void cleanup() {
        properties.forEach(System::clearProperty);
        ResolverLog.reset();
    }

    @Test
    void downloadsTheNewestCurseForgeMatchAndThenAllowsForgeToStart() throws Exception {
        Path game = Files.createTempDirectory("resolver-game");
        Path mods = game.resolve("mods");
        Files.createDirectory(mods);
        writeMod(mods.resolve("chaosawakens.jar"), consumer("Chaos Awakens", "chaosawakens", "(1.2.6,1.2.9)", "geckolib"));
        byte[] library = jarBytes(libraryToml("geckolib", "GeckoLib", "1.2.8"));
        List<String> hits = new ArrayList<>();
        HttpServer server = server(hits, library);
        server.start();
        try {
            configure(game, server);
            AtomicInteger launches = new AtomicInteger();
            DependencyBootstrap.run(new String[0], args -> launches.incrementAndGet());
            assertEquals(1, launches.get());
            assertTrue(hits.stream().anyMatch(path -> path.startsWith("/v1/")));
            assertTrue(hits.stream().noneMatch(path -> path.startsWith("/v2/")));
            assertTrue(hits.stream().anyMatch(path -> path.equals("/files/geckolib-1.20.1-1.2.8.jar")));
            InstalledModScanner scanner = new InstalledModScanner();
            assertTrue(scanner.scan(mods).stream().anyMatch(mod -> mod.modId().equals("geckolib") && mod.version().raw().equals("1.2.8")));
            assertTrue(Files.exists(mods.resolve("geckolib-1.20.1-1.2.8.jar")));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void fallsBackToModrinthWhenCurseForgeHasNothing() throws Exception {
        Path game = Files.createTempDirectory("resolver-game");
        Path mods = game.resolve("mods");
        Files.createDirectory(mods);
        writeMod(mods.resolve("consumer.jar"), consumer("Example Mod", "examplemod", ">=4.2.0", "examplelib"));
        byte[] library = jarBytes(libraryToml("examplelib", "Example Lib", "4.2.1"));
        List<String> hits = new ArrayList<>();
        HttpServer server = emptyCurseServer(hits, library);
        server.start();
        try {
            configure(game, server);
            AtomicInteger launches = new AtomicInteger();
            DependencyBootstrap.run(new String[0], args -> launches.incrementAndGet());
            assertEquals(1, launches.get());
            assertTrue(hits.get(0).startsWith("/v1/"));
            assertTrue(hits.stream().anyMatch(path -> path.startsWith("/v2/")));
            assertTrue(new InstalledModScanner().scan(mods).stream().anyMatch(mod -> mod.modId().equals("examplelib") && mod.version().raw().equals("4.2.1")));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void doesNotLaunchForgeWhenNoPlatformHasAFile() throws Exception {
        Path game = Files.createTempDirectory("resolver-game");
        Path mods = game.resolve("mods");
        Files.createDirectory(mods);
        writeMod(mods.resolve("consumer.jar"), consumer("Example Mod", "examplemod", ">=4.2.0", "examplelib"));
        List<String> hits = new ArrayList<>();
        HttpServer server = missingServer(hits);
        server.start();
        try {
            configure(game, server);
            AtomicInteger launches = new AtomicInteger();
            DependencyBootstrap.LaunchAbortedException error = assertThrows(
                    DependencyBootstrap.LaunchAbortedException.class,
                    () -> DependencyBootstrap.run(new String[0], args -> launches.incrementAndGet()));
            assertEquals(0, launches.get());
            assertTrue(error.getMessage().contains("examplelib"));
            assertEquals(0, Files.list(mods).filter(path -> path.getFileName().toString().startsWith("examplelib")).count());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void conflictDoesNotDownloadOrLaunch() throws Exception {
        Path game = Files.createTempDirectory("resolver-game");
        Path mods = game.resolve("mods");
        Files.createDirectory(mods);
        writeMod(mods.resolve("a.jar"), consumer("Mod A", "moda", ">=1.3.0", "geckolib"));
        writeMod(mods.resolve("b.jar"), consumer("Mod B", "modb", "<1.2.9", "geckolib"));
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        List<String> hits = new ArrayList<>();
        server.createContext("/", exchange -> {
            hits.add(exchange.getRequestURI().getPath());
            byte[] body = "{}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream output = exchange.getResponseBody()) {
                output.write(body);
            }
        });
        server.start();
        try {
            configure(game, server);
            AtomicInteger launches = new AtomicInteger();
            DependencyBootstrap.LaunchAbortedException error = assertThrows(
                    DependencyBootstrap.LaunchAbortedException.class,
                    () -> DependencyBootstrap.run(new String[0], args -> launches.incrementAndGet()));
            assertEquals(0, launches.get());
            assertTrue(hits.stream().noneMatch(path -> path.startsWith("/files/")));
            assertTrue(error.getMessage().toLowerCase().contains("geckolib") || error.getMessage().contains("No version"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void alreadyInstalledDependencySkipsTheNetwork() throws Exception {
        Path game = Files.createTempDirectory("resolver-game");
        Path mods = game.resolve("mods");
        Files.createDirectory(mods);
        writeMod(mods.resolve("chaosawakens.jar"), consumer("Chaos Awakens", "chaosawakens", "(1.2.6,1.2.9)", "geckolib"));
        writeMod(mods.resolve("geckolib-1.20.1-1.2.8.jar"), libraryToml("geckolib", "GeckoLib", "1.2.8"));
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        List<String> hits = new ArrayList<>();
        server.createContext("/", exchange -> {
            hits.add(exchange.getRequestURI().getPath());
            exchange.sendResponseHeaders(500, -1);
            exchange.close();
        });
        server.start();
        try {
            configure(game, server);
            AtomicInteger launches = new AtomicInteger();
            DependencyBootstrap.run(new String[0], args -> launches.incrementAndGet());
            assertEquals(1, launches.get());
            assertTrue(Files.isRegularFile(mods.resolve("geckolib-1.20.1-1.2.8.jar")));
            assertEquals(2, Files.list(mods).filter(path -> path.getFileName().toString().endsWith(".jar")).count());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void wrongInstalledVersionIsMovedAsideAndNotDeleted() throws Exception {
        Path game = Files.createTempDirectory("resolver-game");
        Path mods = game.resolve("mods");
        Files.createDirectory(mods);
        writeMod(mods.resolve("chaosawakens.jar"), consumer("Chaos Awakens", "chaosawakens", "(1.2.6,1.2.9)", "geckolib"));
        writeMod(mods.resolve("geckolib-old.jar"), libraryToml("geckolib", "GeckoLib", "1.2.5"));
        byte[] library = jarBytes(libraryToml("geckolib", "GeckoLib", "1.2.8"));
        HttpServer server = server(new ArrayList<>(), library);
        server.start();
        try {
            configure(game, server);
            DependencyBootstrap.run(new String[0], args -> {
            });
            assertFalse(Files.exists(mods.resolve("geckolib-old.jar")));
            assertTrue(Files.exists(mods.resolve("dependencyresolver-disabled").resolve("geckolib-old.jar")));
            assertTrue(new InstalledModScanner().scan(mods).stream().anyMatch(mod -> mod.modId().equals("geckolib") && mod.version().raw().equals("1.2.8")));
        } finally {
            server.stop(0);
        }
    }

    private static void configure(Path game, HttpServer server) {
        String base = "http://127.0.0.1:" + server.getAddress().getPort();
        System.setProperty("dependencyresolver.gameDir", game.toString());
        System.setProperty("dependencyresolver.autoInstall", "true");
        System.setProperty("dependencyresolver.curseforgeApiKey", "test-key");
        System.setProperty("dependencyresolver.curseforgeBase", base);
        System.setProperty("dependencyresolver.modrinthBase", base);
    }

    private static HttpServer server(List<String> hits, byte[] library) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            hits.add(path);
            byte[] body;
            if ("/v1/mods/search".equals(path)) {
                body = "{\"data\":[{\"id\":123,\"name\":\"GeckoLib\",\"slug\":\"geckolib\"}]}".getBytes(StandardCharsets.UTF_8);
            } else if (path.startsWith("/v1/mods/") && path.endsWith("/files")) {
                body = curseFiles(exchange.getLocalAddress().getPort()).getBytes(StandardCharsets.UTF_8);
            } else if ("/files/geckolib-1.20.1-1.2.8.jar".equals(path)) {
                body = library;
            } else {
                exchange.sendResponseHeaders(404, -1);
                exchange.close();
                return;
            }
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream output = exchange.getResponseBody()) {
                output.write(body);
            }
        });
        return server;
    }

    private static HttpServer emptyCurseServer(List<String> hits, byte[] library) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            hits.add(path);
            byte[] body;
            if (path.startsWith("/v1/")) {
                body = "{\"data\":[]}".getBytes(StandardCharsets.UTF_8);
            } else if ("/v2/project/examplelib".equals(path)) {
                body = "{\"id\":\"abc\",\"slug\":\"examplelib\",\"title\":\"Example Lib\"}".getBytes(StandardCharsets.UTF_8);
            } else if ("/v2/project/abc/version".equals(path)) {
                body = """
                        [{"version_number":"4.2.1","version_type":"release","game_versions":["1.20.1"],"loaders":["forge"],
                          "files":[{"filename":"examplelib-1.20.1-4.2.1.jar","url":"%s","primary":true,"hashes":{}}]}]
                        """.formatted("http://127.0.0.1:" + exchange.getLocalAddress().getPort() + "/files/examplelib.jar")
                        .getBytes(StandardCharsets.UTF_8);
            } else if ("/files/examplelib.jar".equals(path)) {
                body = library;
            } else {
                exchange.sendResponseHeaders(404, -1);
                exchange.close();
                return;
            }
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream output = exchange.getResponseBody()) {
                output.write(body);
            }
        });
        return server;
    }

    private static HttpServer missingServer(List<String> hits) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            hits.add(path);
            byte[] body = path.startsWith("/v2/search") ? "{\"hits\":[]}".getBytes(StandardCharsets.UTF_8) : "{\"data\":[]}".getBytes(StandardCharsets.UTF_8);
            int status = "/v2/project/examplelib".equals(path) ? 404 : 200;
            if (status == 404) {
                body = "{\"error\":\"not_found\"}".getBytes(StandardCharsets.UTF_8);
            }
            exchange.sendResponseHeaders(status, body.length);
            try (OutputStream output = exchange.getResponseBody()) {
                output.write(body);
            }
        });
        return server;
    }

    private static String curseFiles(int port) {
        String[] versions = {"1.2.5", "1.2.6", "1.2.7", "1.2.8", "1.2.9", "1.3.0"};
        StringBuilder builder = new StringBuilder("{\"data\":[");
        for (int i = 0; i < versions.length; i++) {
            if (i > 0) {
                builder.append(',');
            }
            String version = versions[i];
            builder.append("""
                    {"id":%d,"modId":123,"displayName":"GeckoLib %s","fileName":"geckolib-1.20.1-%s.jar",
                     "downloadUrl":"http://127.0.0.1:%d/files/geckolib-1.20.1-%s.jar","gameVersions":["1.20.1","Forge"],
                     "releaseType":1,"fileDate":"2024-01-0%dT00:00:00Z","hashes":[]}
                    """.formatted(i + 1, version, version, port, version, Math.min(i + 1, 9)));
        }
        return builder.append("]}").toString();
    }

    private static String consumer(String name, String id, String range, String dependency) {
        return """
                modLoader="javafml"
                loaderVersion="[47,)"
                license="MIT"
                [[mods]]
                modId="%s"
                version="1.0.0"
                displayName="%s"
                [[dependencies.%s]]
                modId="minecraft"
                mandatory=true
                versionRange="[1.20.1,1.21)"
                ordering="NONE"
                side="BOTH"
                [[dependencies.%s]]
                modId="%s"
                mandatory=true
                versionRange="%s"
                ordering="NONE"
                side="BOTH"
                """.formatted(id, name, id, id, dependency, range);
    }

    private static String libraryToml(String id, String name, String version) {
        return """
                modLoader="javafml"
                loaderVersion="[47,)"
                license="MIT"
                [[mods]]
                modId="%s"
                version="%s"
                displayName="%s"
                [[dependencies.%s]]
                modId="minecraft"
                mandatory=true
                versionRange="[1.20.1,1.21)"
                ordering="NONE"
                side="BOTH"
                [[dependencies.%s]]
                modId="forge"
                mandatory=true
                versionRange="[47,)"
                ordering="NONE"
                side="BOTH"
                """.formatted(id, version, name, id, id);
    }

    private static void writeMod(Path jar, String toml) throws IOException {
        Files.write(jar, jarBytes(toml));
    }

    private static byte[] jarBytes(String toml) throws IOException {
        java.io.ByteArrayOutputStream buffer = new java.io.ByteArrayOutputStream();
        Manifest manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        try (JarOutputStream output = new JarOutputStream(buffer, manifest)) {
            output.putNextEntry(new JarEntry("META-INF/mods.toml"));
            output.write(toml.getBytes(StandardCharsets.UTF_8));
            output.closeEntry();
        }
        return buffer.toByteArray();
    }
}
