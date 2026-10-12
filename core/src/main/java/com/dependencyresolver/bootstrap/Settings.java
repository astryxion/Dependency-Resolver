package com.dependencyresolver.bootstrap;

import com.dependencyresolver.log.ResolverLog;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Properties;

public record Settings(
        Path gameDir,
        String curseForgeApiKey,
        String curseForgeBase,
        String modrinthBase,
        boolean autoInstall,
        boolean debug,
        Duration cacheTtl
) {
    public static Settings load(Path gameDir) {
        Properties file = new Properties();
        Path properties = gameDir.resolve("config").resolve("dependencyresolver.properties");
        if (Files.isRegularFile(properties)) {
            try (Reader reader = Files.newBufferedReader(properties, StandardCharsets.UTF_8)) {
                file.load(reader);
            } catch (IOException exception) {
                ResolverLog.error("Could not read " + properties, exception);
            }
        } else {
            writeTemplate(properties);
        }
        String apiKey = first(
                System.getProperty("dependencyresolver.curseforgeApiKey"),
                System.getenv("CURSEFORGE_API_KEY"),
                file.getProperty("curseforgeApiKey")
        );
        return new Settings(
                gameDir,
                apiKey == null ? "" : apiKey.trim(),
                first(System.getProperty("dependencyresolver.curseforgeBase"), file.getProperty("curseforgeBase"), "https://api.curseforge.com"),
                first(System.getProperty("dependencyresolver.modrinthBase"), file.getProperty("modrinthBase"), "https://api.modrinth.com"),
                Boolean.parseBoolean(first(System.getProperty("dependencyresolver.autoInstall"), file.getProperty("autoInstall"), "false")),
                Boolean.parseBoolean(first(System.getProperty("dependencyresolver.debug"), file.getProperty("debug"), "false")),
                Duration.ofHours(6)
        );
    }

    private static void writeTemplate(Path properties) {
        try {
            Files.createDirectories(properties.getParent());
            try (Writer writer = Files.newBufferedWriter(properties, StandardCharsets.UTF_8)) {
                writer.write("""
                        # Optional CurseForge Core API key.
                        # CurseForge is searched first on every launch, including the first one, without this key.
                        # A key from https://console.curseforge.com/ switches that search to the official API.
                        # You can also set the CURSEFORGE_API_KEY environment variable.
                        #curseforgeApiKey=
                        """);
            }
        } catch (IOException exception) {
            ResolverLog.debug("Could not write dependency resolver settings: " + exception.getMessage());
        }
    }

    private static String first(String... values) {
        if (values == null) {
            return "";
        }
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return "";
    }
}
