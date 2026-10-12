package com.dependencyresolver.platform;

import com.dependencyresolver.dependency.MavenVersion;
import com.dependencyresolver.json.Json;
import com.dependencyresolver.log.ResolverLog;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public final class ModrinthClient {
    private final HttpTransport http;
    private final MetadataCache cache;
    private final String baseUrl;

    public ModrinthClient(HttpTransport http, MetadataCache cache, String baseUrl) {
        this.http = http;
        this.cache = cache;
        this.baseUrl = baseUrl == null || baseUrl.isBlank() ? "https://api.modrinth.com" : strip(baseUrl);
    }

    public List<RemoteFile> search(String modId, TargetEnvironment environment) throws IOException {
        ResolverLog.info("Modrinth fallback search modId=" + modId + " minecraft=" + environment.minecraftVersion()
                + " loader=" + environment.loader().id());
        for (Json.JsonValue project : slugProjects(modId, environment)) {
            List<RemoteFile> files = filesFor(modId, project, environment);
            if (!files.isEmpty()) {
                ResolverLog.info("Modrinth returned " + files.size() + " compatible files for " + modId);
                return files;
            }
        }
        for (Json.JsonValue project : searchHits(modId, environment)) {
            List<RemoteFile> files = filesFor(modId, project, environment);
            if (!files.isEmpty()) {
                ResolverLog.info("Modrinth returned " + files.size() + " compatible files for " + modId);
                return files;
            }
        }
        ResolverLog.info("Modrinth has no " + environment.loader().id() + " " + environment.minecraftVersion() + " files for " + modId);
        return List.of();
    }

    /**
     * Required add-ons published on the same project version CurseForge would install automatically.
     * Empty when the project exists and lists none. Empty optional when the project was not found.
     */
    public Optional<List<String>> requiredModIds(String modId, MavenVersion installed, TargetEnvironment environment) throws IOException {
        Json.JsonValue match = null;
        for (Json.JsonValue project : slugProjects(modId, environment)) {
            Json.JsonValue version = matchingVersion(project, installed, environment);
            if (version != null) {
                match = version;
                break;
            }
        }
        if (match == null) {
            for (Json.JsonValue project : searchHits(modId, environment)) {
                Json.JsonValue version = matchingVersion(project, installed, environment);
                if (version != null) {
                    match = version;
                    break;
                }
            }
        }
        if (match == null) {
            return Optional.empty();
        }
        List<String> ids = new ArrayList<>();
        for (Json.JsonValue dependency : match.get("dependencies").array()) {
            if (!"required".equalsIgnoreCase(dependency.get("dependency_type").asString())) {
                continue;
            }
            String projectId = dependency.get("project_id").asString();
            if (projectId.isBlank()) {
                continue;
            }
            Json.JsonValue requiredProject = projectBySlug(projectId);
            String slug = requiredProject == null ? "" : requiredProject.get("slug").asString();
            String resolved = ProjectIds.modId(slug);
            if (!resolved.isBlank() && !ids.contains(resolved)) {
                ids.add(resolved);
            }
        }
        return Optional.of(ids);
    }

    private Json.JsonValue projectBySlug(String modId) throws IOException {
        String url = baseUrl + "/v2/project/" + encode(modId);
        try {
            HttpTransport.Response response = send(url, headers());
            if (response.status() == 404) {
                return null;
            }
            if (response.status() < 200 || response.status() >= 300) {
                throw new IOException("Modrinth project lookup failed with HTTP " + response.status());
            }
            if (cache != null) {
                cache.put(url, response.text());
            }
            return Json.parse(response.text());
        } catch (IOException exception) {
            if (Network.isOffline(exception)) {
                throw exception;
            }
            ResolverLog.debug("Modrinth slug lookup failed for " + modId + ": " + exception.getMessage());
            return null;
        }
    }

    private List<Json.JsonValue> slugProjects(String modId, TargetEnvironment environment) throws IOException {
        List<Json.JsonValue> projects = new ArrayList<>();
        addProject(projects, projectBySlug(modId));
        addProject(projects, projectBySlug(modId.replace('_', '-')));
        addProject(projects, projectBySlug(modId.replace('-', '_')));
        addProject(projects, projectBySlug(modId + "-" + environment.loader().id()));
        addProject(projects, projectBySlug(modId.replace('_', '-') + "-" + environment.loader().id()));
        return projects;
    }

    private static void addProject(List<Json.JsonValue> projects, Json.JsonValue project) {
        if (project == null || project.isNull()) {
            return;
        }
        String slug = project.get("slug").asString();
        String id = project.get("id").asString();
        if (id.isBlank()) {
            id = project.get("project_id").asString();
        }
        if (slug.isBlank() && id.isBlank()) {
            return;
        }
        for (Json.JsonValue existing : projects) {
            if (!slug.isBlank() && slug.equals(existing.get("slug").asString())) {
                return;
            }
            String existingId = existing.get("id").asString();
            if (existingId.isBlank()) {
                existingId = existing.get("project_id").asString();
            }
            if (!id.isBlank() && id.equals(existingId)) {
                return;
            }
        }
        projects.add(project);
    }

    private List<RemoteFile> filesFor(String modId, Json.JsonValue project, TargetEnvironment environment) throws IOException {
        String projectId = project.get("id").asString();
        if (projectId.isBlank()) {
            projectId = project.get("project_id").asString();
        }
        if (projectId.isBlank()) {
            return List.of();
        }
        String slug = project.get("slug").asString();
        String title = project.get("title").asString();
        if (title.isBlank()) {
            title = project.get("name").asString();
        }
        ResolverLog.info("Modrinth project " + slug + " id=" + projectId);
        List<RemoteFile> files = new ArrayList<>();
        for (Json.JsonValue version : versions(projectId, environment).array()) {
            RemoteFile remote = toRemote(modId, slug, title.isBlank() ? modId : title, projectId, version, environment);
            if (remote != null) {
                files.add(remote);
            }
        }
        files.sort(Comparator
                .comparing((RemoteFile file) -> file.version(), Comparator.nullsLast(Comparator.reverseOrder()))
                .thenComparingInt(RemoteFile::releaseRank));
        return files;
    }

    private Json.JsonValue matchingVersion(Json.JsonValue project, MavenVersion installed, TargetEnvironment environment) throws IOException {
        String projectId = project.get("id").asString();
        if (projectId.isBlank()) {
            projectId = project.get("project_id").asString();
        }
        if (projectId.isBlank()) {
            return null;
        }
        Json.JsonValue newest = null;
        for (Json.JsonValue version : versions(projectId, environment).array()) {
            boolean loaderMatches = version.get("loaders").array().stream()
                    .anyMatch(value -> value.asString().equalsIgnoreCase(environment.loader().id()));
            boolean gameMatches = version.get("game_versions").array().stream()
                    .anyMatch(value -> value.asString().equals(environment.minecraftVersion()));
            if (!loaderMatches || !gameMatches) {
                continue;
            }
            if (newest == null) {
                newest = version;
            }
            if (installed != null && installed.equals(MavenVersion.parse(version.get("version_number").asString()))) {
                return version;
            }
        }
        if (installed != null) {
            return null;
        }
        return newest;
    }

    private Json.JsonValue versions(String projectId, TargetEnvironment environment) throws IOException {
        String versionsUrl = baseUrl + "/v2/project/" + encode(projectId) + "/version?loaders="
                + encode("[\"" + environment.loader().id() + "\"]")
                + "&game_versions=" + encode("[\"" + environment.minecraftVersion() + "\"]");
        return getJson(versionsUrl);
    }

    private List<Json.JsonValue> searchHits(String modId, TargetEnvironment environment) throws IOException {
        String facets = "[[\"project_type:mod\"],[\"versions:" + environment.minecraftVersion()
                + "\"],[\"categories:" + environment.loader().id() + "\"]]";
        String url = baseUrl + "/v2/search?query=" + encode(modId) + "&limit=10&facets=" + encode(facets);
        Json.JsonValue root = getJson(url);
        String needle = ProjectIds.compact(modId);
        List<Json.JsonValue> hits = new ArrayList<>();
        List<Json.JsonValue> rest = new ArrayList<>();
        for (Json.JsonValue hit : root.get("hits").array()) {
            String slug = ProjectIds.compact(hit.get("slug").asString());
            if (slug.equals(needle) || slug.equals(needle + environment.loader().id())) {
                hits.add(hit);
            } else if (!needle.isBlank() && slug.startsWith(needle)) {
                rest.add(hit);
            }
        }
        hits.addAll(rest);
        return hits;
    }

    private static RemoteFile toRemote(String modId, String slug, String title, String projectId, Json.JsonValue version,
                                       TargetEnvironment environment) {
        boolean loaderMatches = version.get("loaders").array().stream()
                .anyMatch(value -> value.asString().equalsIgnoreCase(environment.loader().id()));
        boolean gameMatches = version.get("game_versions").array().stream()
                .anyMatch(value -> value.asString().equals(environment.minecraftVersion()));
        if (!loaderMatches || !gameMatches) {
            return null;
        }
        Json.JsonValue primary = null;
        for (Json.JsonValue file : version.get("files").array()) {
            if (file.get("primary").asBoolean()) {
                primary = file;
                break;
            }
            if (primary == null) {
                primary = file;
            }
        }
        if (primary == null) {
            return null;
        }
        String fileName = primary.get("filename").asString();
        String url = primary.get("url").asString();
        if (url.isBlank() || !fileName.toLowerCase(java.util.Locale.ROOT).endsWith(".jar")) {
            return null;
        }
        String versionNumber = version.get("version_number").asString();
        MavenVersion parsed = versionNumber.isBlank() ? VersionGuess.fromFileName(fileName, environment.minecraftVersion()) : MavenVersion.parse(versionNumber);
        String sha1 = primary.get("hashes").get("sha1").asString();
        int rank = switch (version.get("version_type").asString()) {
            case "release" -> 1;
            case "beta" -> 2;
            case "alpha" -> 3;
            default -> 4;
        };
        ResolverLog.info("Modrinth file " + fileName + " version=" + (parsed == null ? versionNumber : parsed.raw())
                + " url=" + ResolverLog.redact(url));
        return new RemoteFile(
                modId,
                parsed == null ? versionNumber : parsed.raw(),
                parsed,
                title,
                ModPlatform.MODRINTH,
                projectId,
                slug,
                fileName,
                url,
                sha1.isBlank() ? null : sha1,
                rank,
                version.get("date_published").asString()
        );
    }

    private Json.JsonValue getJson(String url) throws IOException {
        String cached = cache == null ? null : cache.get(url).orElse(null);
        if (cached != null) {
            return Json.parse(cached);
        }
        ResolverLog.debug("Modrinth GET " + ResolverLog.redact(url));
        HttpTransport.Response response = send(url, headers());
        if (response.status() < 200 || response.status() >= 300) {
            throw new IOException("Modrinth request failed with HTTP " + response.status());
        }
        if (cache != null) {
            cache.put(url, response.text());
        }
        return Json.parse(response.text());
    }

    private HttpTransport.Response send(String url, Map<String, String> headers) throws IOException {
        for (int attempt = 1; attempt <= 3; attempt++) {
            HttpTransport.Response response = http.send(HttpTransport.Request.get(URI.create(url), headers));
            if (response.status() != 429 || attempt == 3) {
                return response;
            }
            ResolverLog.info("Modrinth rate limited the request. Retrying.");
            HttpTransport.sleep(2000L * attempt);
        }
        throw new IOException("Modrinth rate limit");
    }

    private static Map<String, String> headers() {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Accept", "application/json");
        headers.put("User-Agent", HttpTransport.userAgent());
        return headers;
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static String strip(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }
}
