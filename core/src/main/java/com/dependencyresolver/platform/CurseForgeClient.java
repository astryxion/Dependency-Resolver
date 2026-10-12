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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

public final class CurseForgeClient {
    static final int MINECRAFT_GAME_ID = 432;
    static final int MOD_CLASS_ID = 6;
    private static final String WIDGET = "https://api.cfwidget.com";
    private static final String SITE = "https://www.curseforge.com";
    private static final String CATALOG = "https://api.curse.tools/v1";

    private final HttpTransport http;
    private final MetadataCache cache;
    private final String baseUrl;
    private final String apiKey;

    public CurseForgeClient(HttpTransport http, MetadataCache cache, String baseUrl, String apiKey) {
        this.http = http;
        this.cache = cache;
        this.baseUrl = trim(baseUrl == null ? "https://api.curseforge.com" : baseUrl);
        this.apiKey = apiKey == null ? "" : apiKey.trim();
    }

    public boolean configured() {
        return !apiKey.isBlank();
    }

    public List<RemoteFile> search(String modId, TargetEnvironment environment) throws IOException {
        if (!configured()) {
            return searchPublic(modId, environment);
        }
        ResolverLog.info("CurseForge search modId=" + modId + " minecraft=" + environment.minecraftVersion()
                + " loader=" + environment.loader().id());
        List<Json.JsonValue> projects = searchProjects(modId, environment, "slug");
        if (projects.isEmpty()) {
            projects = searchProjects(modId, environment, "searchFilter");
        }
        Json.JsonValue project = chooseProject(modId, projects);
        if (project == null || project.isNull()) {
            ResolverLog.info("CurseForge has no project match for " + modId);
            return List.of();
        }
        long projectId = project.get("id").asLong();
        String slug = project.get("slug").asString();
        String name = project.get("name").asString();
        ResolverLog.info("CurseForge project " + slug + " id=" + projectId);
        List<RemoteFile> files = new ArrayList<>();
        for (Json.JsonValue file : listFiles(projectId, environment)) {
            RemoteFile remote = toRemote(modId, slug, name, projectId, file, environment.minecraftVersion());
            if (remote != null) {
                files.add(remote);
            }
        }
        files.sort(comparator());
        ResolverLog.info("CurseForge returned " + files.size() + " Forge " + environment.minecraftVersion() + " files for " + modId);
        return files;
    }

    /**
     * Required project relations on the CurseForge file. These are the add-ons the CurseForge app downloads with the mod.
     * Empty when the project exists and lists none. Empty optional when CurseForge has no such project.
     */
    public Optional<List<String>> requiredModIds(String modId, MavenVersion installed, TargetEnvironment environment) throws IOException {
        if (!configured()) {
            return publicRequiredModIds(modId, installed, environment);
        }
        Json.JsonValue project = findProject(modId, environment);
        if (project == null) {
            project = findProject(modId + "-" + environment.loader().id(), environment);
        }
        if (project == null || project.isNull()) {
            return Optional.empty();
        }
        Json.JsonValue chosen = null;
        Json.JsonValue newest = null;
        for (Json.JsonValue file : listFiles(project.get("id").asLong(), environment)) {
            if (newest == null) {
                newest = file;
            }
            MavenVersion version = VersionGuess.fromFileName(file.get("fileName").asString(), environment.minecraftVersion());
            if (sameRelease(installed, version)) {
                chosen = file;
                break;
            }
        }
        if (chosen == null && installed != null) {
            ResolverLog.info("CurseForge has no " + modId + " " + installed.raw() + " file, so its add-on list will not be used.");
            return Optional.empty();
        }
        if (chosen == null) {
            chosen = newest;
        }
        if (chosen == null) {
            return Optional.of(List.of());
        }
        List<String> ids = new ArrayList<>();
        for (Json.JsonValue dependency : chosen.get("dependencies").array()) {
            if (dependency.get("relationType").asInt() != 3) {
                continue;
            }
            long dependencyProject = dependency.get("modId").asLong();
            if (dependencyProject <= 0) {
                continue;
            }
            String resolved = ProjectIds.modId(projectSlug(dependencyProject));
            if (!resolved.isBlank() && !ids.contains(resolved)) {
                ids.add(resolved);
            }
        }
        return Optional.of(ids);
    }

    private List<RemoteFile> searchPublic(String modId, TargetEnvironment environment) throws IOException {
        ResolverLog.info("CurseForge public catalog search modId=" + modId + " minecraft=" + environment.minecraftVersion()
                + " loader=" + environment.loader().id());
        PublicProject project = findPublicProject(modId, environment);
        if (project == null) {
            ResolverLog.info("CurseForge has no project match for " + modId);
            return List.of();
        }
        List<RemoteFile> files = new ArrayList<>();
        for (Json.JsonValue file : project.files()) {
            RemoteFile remote = file.get("fileName").asString().isBlank()
                    ? publicRemote(modId, project, file, environment.minecraftVersion())
                    : catalogRemote(modId, project, file, environment.minecraftVersion());
            if (remote != null) {
                files.add(remote);
            }
        }
        files.sort(comparator());
        ResolverLog.info("CurseForge returned " + files.size() + " Forge " + environment.minecraftVersion() + " files for " + modId);
        return files;
    }

    private Optional<List<String>> publicRequiredModIds(String modId, MavenVersion installed, TargetEnvironment environment) throws IOException {
        PublicProject project = findPublicProject(modId, environment);
        if (project == null) {
            return Optional.empty();
        }
        Json.JsonValue chosen = null;
        Json.JsonValue newest = null;
        MavenVersion newestVersion = null;
        for (Json.JsonValue file : project.files()) {
            MavenVersion version = VersionGuess.fromFileName(fileName(file), environment.minecraftVersion());
            if (newest == null || (version != null && (newestVersion == null || version.compareTo(newestVersion) > 0))) {
                newest = file;
                newestVersion = version;
            }
            if (sameRelease(installed, version)) {
                chosen = file;
                break;
            }
        }
        if (chosen == null && installed != null) {
            ResolverLog.info("CurseForge has no " + modId + " " + installed.raw() + " file, so its add-on list will not be used.");
            return Optional.empty();
        }
        if (chosen == null) {
            chosen = newest;
        }
        if (chosen == null) {
            return Optional.of(List.of());
        }
        return Optional.of(requiredSlugs(project.id(), chosen.get("id").asLong()));
    }

    private PublicProject findPublicProject(String modId, TargetEnvironment environment) throws IOException {
        for (String slug : slugCandidates(modId, environment)) {
            PublicProject project = publicProject(slug, environment);
            if (project != null && !project.files().isEmpty()) {
                return project;
            }
        }
        for (String slug : slugCandidates(modId, environment)) {
            PublicProject project = catalogBySlug(slug, environment);
            if (project != null && !project.files().isEmpty()) {
                return project;
            }
        }
        if (modId.indexOf('-') < 0 && modId.indexOf('_') < 0 && modId.length() >= 4 && modId.length() <= 24) {
            for (int split = 1; split < modId.length(); split++) {
                PublicProject project = catalogBySlug(modId.substring(0, split) + "-" + modId.substring(split), environment);
                if (project != null && !project.files().isEmpty()) {
                    return project;
                }
            }
        }
        return catalogByName(modId, environment);
    }

    private PublicProject catalogBySlug(String slug, TargetEnvironment environment) throws IOException {
        Json.JsonValue root = publicJson(CATALOG + "/mods/search?gameId=" + MINECRAFT_GAME_ID
                + "&classId=" + MOD_CLASS_ID + "&slug=" + encode(slug) + "&pageSize=5");
        if (root == null) {
            return null;
        }
        for (Json.JsonValue project : root.get("data").array()) {
            if (slug.equalsIgnoreCase(project.get("slug").asString())) {
                return catalogProject(project, environment);
            }
        }
        return null;
    }

    private PublicProject catalogByName(String modId, TargetEnvironment environment) throws IOException {
        String query = modId.replace('_', ' ').replace('-', ' ');
        Json.JsonValue root = publicJson(CATALOG + "/mods/search?gameId=" + MINECRAFT_GAME_ID
                + "&classId=" + MOD_CLASS_ID
                + "&searchFilter=" + encode(query)
                + "&gameVersion=" + encode(environment.minecraftVersion())
                + "&modLoaderType=" + environment.loader().curseForgeId()
                + "&pageSize=50&sortField=2&sortOrder=desc");
        if (root == null) {
            return null;
        }
        String needle = ProjectIds.compact(modId);
        Json.JsonValue chosen = null;
        int chosenLength = Integer.MAX_VALUE;
        for (Json.JsonValue project : root.get("data").array()) {
            String slug = project.get("slug").asString();
            String compact = ProjectIds.compact(ProjectIds.modId(slug));
            if (!compact.equals(needle)) {
                continue;
            }
            if (slug.length() < chosenLength) {
                chosen = project;
                chosenLength = slug.length();
            }
        }
        return chosen == null ? null : catalogProject(chosen, environment);
    }

    private PublicProject catalogProject(Json.JsonValue project, TargetEnvironment environment) throws IOException {
        long projectId = project.get("id").asLong();
        String slug = project.get("slug").asString();
        String name = project.get("name").asString();
        if (projectId <= 0 || slug.isBlank()) {
            return null;
        }
        List<Json.JsonValue> files = catalogFiles(projectId, environment);
        ResolverLog.info("CurseForge project " + slug + " id=" + projectId);
        return new PublicProject(projectId, slug, name.isBlank() ? slug : name, files);
    }

    private List<Json.JsonValue> catalogFiles(long projectId, TargetEnvironment environment) throws IOException {
        List<Json.JsonValue> files = new ArrayList<>();
        for (int page = 0; page < 3; page++) {
            int index = page * 50;
            Json.JsonValue root = publicJson(CATALOG + "/mods/" + projectId + "/files?gameVersion="
                    + encode(environment.minecraftVersion())
                    + "&modLoaderType=" + environment.loader().curseForgeId()
                    + "&pageSize=50&index=" + index);
            if (root == null) {
                break;
            }
            List<Json.JsonValue> data = root.get("data").array();
            for (Json.JsonValue file : data) {
                if (labeled(file, "gameVersions", environment.minecraftVersion())
                        && labeled(file, "gameVersions", environment.loader().id())) {
                    files.add(file);
                }
            }
            if (data.size() < 50) {
                break;
            }
        }
        return files;
    }

    private PublicProject publicProject(String slug, TargetEnvironment environment) throws IOException {
        Json.JsonValue root = publicJson(WIDGET + "/minecraft/mc-mods/" + encode(slug));
        if (root == null || root.isNull() || root.get("id").asLong() <= 0) {
            return null;
        }
        List<Json.JsonValue> files = new ArrayList<>();
        for (Json.JsonValue file : root.get("versions").get(environment.minecraftVersion()).array()) {
            if (listsLoader(file, environment.loader().id())) {
                files.add(file);
            }
        }
        String name = root.get("title").asString();
        ResolverLog.info("CurseForge project " + slug + " id=" + root.get("id").asLong());
        return new PublicProject(root.get("id").asLong(), slug, name == null || name.isBlank() ? slug : name, files);
    }

    private List<String> requiredSlugs(long projectId, long fileId) throws IOException {
        List<String> ids = new ArrayList<>();
        int index = 0;
        while (index < 200) {
            Json.JsonValue root = publicJson(SITE + "/api/v1/mods/" + projectId + "/files/" + fileId
                    + "/dependencies?index=" + index + "&pageSize=50");
            if (root == null || root.isNull()) {
                break;
            }
            List<Json.JsonValue> data = root.get("data").array();
            for (Json.JsonValue dependency : data) {
                if (!"RequiredDependency".equalsIgnoreCase(dependency.get("type").asString())) {
                    continue;
                }
                String resolved = ProjectIds.modId(dependency.get("slug").asString());
                if (!resolved.isBlank() && !ids.contains(resolved)) {
                    ids.add(resolved);
                }
            }
            int total = root.get("pagination").get("totalCount").asInt();
            index += data.size();
            if (data.size() < 50 || (total > 0 && index >= total)) {
                break;
            }
        }
        return ids;
    }

    private RemoteFile publicRemote(String modId, PublicProject project, Json.JsonValue file, String minecraftVersion) {
        if (!listsGame(file, minecraftVersion) || !listsLoader(file, "forge")) {
            return null;
        }
        String fileName = file.get("name").asString();
        long fileId = file.get("id").asLong();
        if (fileName.isBlank() || fileId <= 0) {
            return null;
        }
        String downloadUrl = SITE + "/api/v1/mods/" + project.id() + "/files/" + fileId + "/download";
        MavenVersion version = VersionGuess.fromFileName(fileName, minecraftVersion);
        if (version == null) {
            version = VersionGuess.fromFileName(file.get("display").asString(), minecraftVersion);
        }
        ResolverLog.info("CurseForge file " + fileName + " version=" + (version == null ? "unknown" : version.raw())
                + " url=" + ResolverLog.redact(downloadUrl));
        return new RemoteFile(
                modId,
                version == null ? "" : version.raw(),
                version,
                project.name(),
                ModPlatform.CURSEFORGE,
                Long.toString(project.id()),
                project.slug(),
                fileName,
                downloadUrl,
                null,
                releaseRank(file.get("type").asString()),
                file.get("uploaded_at").asString()
        );
    }

    private Json.JsonValue publicJson(String url) throws IOException {
        String cached = cache == null ? null : cache.get(url).orElse(null);
        if (cached != null) {
            return Json.parse(cached);
        }
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Accept", "application/json");
        headers.put("User-Agent", HttpTransport.userAgent());
        ResolverLog.debug("CurseForge GET " + ResolverLog.redact(url));
        HttpTransport.Response response = send(url, headers);
        if (response.status() == 404) {
            return null;
        }
        if (response.status() < 200 || response.status() >= 300) {
            throw new IOException("CurseForge request failed with HTTP " + response.status());
        }
        String body = response.text();
        if (body.isBlank()) {
            return null;
        }
        if (cache != null) {
            cache.put(url, body);
        }
        return Json.parse(body);
    }

    private RemoteFile catalogRemote(String modId, PublicProject project, Json.JsonValue file, String minecraftVersion) {
        if (!labeled(file, "gameVersions", minecraftVersion) || !labeled(file, "gameVersions", "forge")) {
            return null;
        }
        String fileName = file.get("fileName").asString();
        long fileId = file.get("id").asLong();
        if (fileName.isBlank() || fileId <= 0) {
            return null;
        }
        String downloadUrl = SITE + "/api/v1/mods/" + project.id() + "/files/" + fileId + "/download";
        MavenVersion version = VersionGuess.fromFileName(fileName, minecraftVersion);
        if (version == null) {
            version = VersionGuess.fromFileName(file.get("displayName").asString(), minecraftVersion);
        }
        int releaseType = file.get("releaseType").asInt();
        ResolverLog.info("CurseForge file " + fileName + " version=" + (version == null ? "unknown" : version.raw())
                + " url=" + ResolverLog.redact(downloadUrl));
        return new RemoteFile(
                modId,
                version == null ? "" : version.raw(),
                version,
                project.name(),
                ModPlatform.CURSEFORGE,
                Long.toString(project.id()),
                project.slug(),
                fileName,
                downloadUrl,
                null,
                releaseType == 0 ? 1 : releaseType,
                file.get("fileDate").asString()
        );
    }

    private static boolean sameRelease(MavenVersion installed, MavenVersion guessed) {
        if (installed == null || guessed == null) {
            return false;
        }
        if (installed.equals(guessed)) {
            return true;
        }
        String raw = guessed.raw().toLowerCase(java.util.Locale.ROOT);
        for (String suffix : new String[]{"-forge", "-fabric", "-neoforge", "-quilt"}) {
            if (raw.endsWith(suffix)) {
                return installed.equals(MavenVersion.parse(raw.substring(0, raw.length() - suffix.length())));
            }
        }
        return false;
    }

    private static String fileName(Json.JsonValue file) {
        String name = file.get("name").asString();
        return name.isBlank() ? file.get("fileName").asString() : name;
    }

    private static boolean labeled(Json.JsonValue file, String field, String expected) {
        for (Json.JsonValue value : file.get(field).array()) {
            if (value.asString().equalsIgnoreCase(expected)) {
                return true;
            }
        }
        return false;
    }

    private static List<String> slugCandidates(String modId, TargetEnvironment environment) {
        Set<String> slugs = new LinkedHashSet<>();
        addSlug(slugs, modId);
        addSlug(slugs, modId.replace('_', '-'));
        addSlug(slugs, modId.replace('-', '_'));
        List<String> copy = new ArrayList<>(slugs);
        for (String slug : copy) {
            addSlug(slugs, slug + "-" + environment.loader().id());
        }
        return new ArrayList<>(slugs);
    }

    private static void addSlug(Set<String> slugs, String slug) {
        if (slug != null && !slug.isBlank()) {
            slugs.add(slug.toLowerCase(Locale.ROOT));
        }
    }

    private static boolean listsGame(Json.JsonValue file, String minecraftVersion) {
        List<String> versions = labels(file);
        return versions.isEmpty() || versions.stream().anyMatch(minecraftVersion::equals);
    }

    private static boolean listsLoader(Json.JsonValue file, String loader) {
        return labels(file).stream().anyMatch(loader::equalsIgnoreCase);
    }

    private static List<String> labels(Json.JsonValue file) {
        List<String> versions = new ArrayList<>();
        for (Json.JsonValue version : file.get("versions").array()) {
            versions.add(version.asString());
        }
        return versions;
    }

    private static int releaseRank(String type) {
        if (type == null) {
            return 1;
        }
        return switch (type.toLowerCase(java.util.Locale.ROOT)) {
            case "beta" -> 2;
            case "alpha" -> 3;
            default -> 1;
        };
    }

    private record PublicProject(long id, String slug, String name, List<Json.JsonValue> files) {
    }

    private Json.JsonValue findProject(String modId, TargetEnvironment environment) throws IOException {
        List<Json.JsonValue> projects = searchProjects(modId, environment, "slug");
        if (projects.isEmpty()) {
            projects = searchProjects(modId, environment, "searchFilter");
        }
        return chooseProject(modId, projects);
    }

    private String projectSlug(long projectId) throws IOException {
        Json.JsonValue root = getJson(baseUrl + "/v1/mods/" + projectId);
        return root.get("data").get("slug").asString();
    }

    private List<Json.JsonValue> searchProjects(String modId, TargetEnvironment environment, String field) throws IOException {
        String url = baseUrl + "/v1/mods/search?gameId=" + MINECRAFT_GAME_ID
                + "&classId=" + MOD_CLASS_ID
                + "&" + field + "=" + encode(modId)
                + "&gameVersion=" + encode(environment.minecraftVersion())
                + "&modLoaderType=" + environment.loader().curseForgeId()
                + "&pageSize=10&sortField=2&sortOrder=desc";
        Json.JsonValue root = getJson(url);
        return root.get("data").array();
    }

    private List<Json.JsonValue> listFiles(long projectId, TargetEnvironment environment) throws IOException {
        List<Json.JsonValue> files = new ArrayList<>();
        for (int page = 0; page < 3; page++) {
            int index = page * 50;
            String url = baseUrl + "/v1/mods/" + projectId + "/files?gameVersion=" + encode(environment.minecraftVersion())
                    + "&modLoaderType=" + environment.loader().curseForgeId()
                    + "&pageSize=50&index=" + index;
            Json.JsonValue root = getJson(url);
            List<Json.JsonValue> data = root.get("data").array();
            files.addAll(data);
            if (data.size() < 50) {
                break;
            }
        }
        return files;
    }

    private RemoteFile toRemote(String modId, String slug, String projectName, long projectId, Json.JsonValue file,
                                String minecraftVersion) throws IOException {
        List<String> gameVersions = new ArrayList<>();
        for (Json.JsonValue version : file.get("gameVersions").array()) {
            gameVersions.add(version.asString());
        }
        if (!gameVersions.isEmpty() && gameVersions.stream().noneMatch(minecraftVersion::equals)) {
            return null;
        }
        if (!gameVersions.isEmpty() && gameVersions.stream().noneMatch(version -> version.equalsIgnoreCase("forge"))) {
            return null;
        }
        String fileName = file.get("fileName").asString();
        String downloadUrl = file.get("downloadUrl").asString();
        long fileId = file.get("id").asLong();
        if (downloadUrl == null || downloadUrl.isBlank() || downloadUrl.equals("null")) {
            downloadUrl = downloadUrl(projectId, fileId);
        }
        if (downloadUrl == null || downloadUrl.isBlank()) {
            ResolverLog.info("CurseForge file " + fileId + " (" + fileName + ") has no public download URL. Skipping it.");
            return null;
        }
        MavenVersion version = VersionGuess.fromFileName(fileName, minecraftVersion);
        if (version == null) {
            version = VersionGuess.fromFileName(file.get("displayName").asString(), minecraftVersion);
        }
        String sha1 = null;
        for (Json.JsonValue hash : file.get("hashes").array()) {
            if (hash.get("algo").asInt() == 1) {
                sha1 = hash.get("value").asString();
            }
        }
        ResolverLog.info("CurseForge file " + fileName + " version=" + (version == null ? "unknown" : version.raw())
                + " url=" + ResolverLog.redact(downloadUrl));
        return new RemoteFile(
                modId,
                version == null ? "" : version.raw(),
                version,
                projectName == null || projectName.isBlank() ? modId : projectName,
                ModPlatform.CURSEFORGE,
                Long.toString(projectId),
                slug,
                fileName,
                downloadUrl,
                sha1,
                file.get("releaseType").asInt() == 0 ? 1 : file.get("releaseType").asInt(),
                file.get("fileDate").asString()
        );
    }

    private String downloadUrl(long projectId, long fileId) throws IOException {
        String url = baseUrl + "/v1/mods/" + projectId + "/files/" + fileId + "/download-url";
        Json.JsonValue root = getJson(url);
        String value = root.get("data").asString();
        return value == null || value.isBlank() ? null : value;
    }

    private Json.JsonValue getJson(String url) throws IOException {
        String cached = cache == null ? null : cache.get(url).orElse(null);
        if (cached != null) {
            return Json.parse(cached);
        }
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Accept", "application/json");
        headers.put("User-Agent", HttpTransport.userAgent());
        headers.put("x-api-key", apiKey);
        ResolverLog.debug("CurseForge GET " + ResolverLog.redact(url));
        HttpTransport.Response response = send(url, headers);
        if (response.status() == 403 || response.status() == 401) {
            throw new IOException("CurseForge rejected the API key (HTTP " + response.status() + ")");
        }
        if (response.status() < 200 || response.status() >= 300) {
            throw new IOException("CurseForge request failed with HTTP " + response.status());
        }
        String body = response.text();
        if (cache != null) {
            cache.put(url, body);
        }
        return Json.parse(body);
    }

    private HttpTransport.Response send(String url, Map<String, String> headers) throws IOException {
        IOException last = null;
        for (int attempt = 1; attempt <= 3; attempt++) {
            HttpTransport.Response response = http.send(HttpTransport.Request.get(URI.create(url), headers));
            if (response.status() != 429 || attempt == 3) {
                return response;
            }
            ResolverLog.info("CurseForge rate limited the request. Retrying.");
            HttpTransport.sleep(2000L * attempt);
            last = new IOException("CurseForge rate limit");
        }
        throw last;
    }

    private static Json.JsonValue chooseProject(String modId, List<Json.JsonValue> projects) {
        String needle = modId.toLowerCase(java.util.Locale.ROOT);
        Json.JsonValue fallback = null;
        for (Json.JsonValue project : projects) {
            String slug = project.get("slug").asString().toLowerCase(java.util.Locale.ROOT);
            if (slug.equals(needle)) {
                return project;
            }
            if (fallback == null && (slug.equals(needle + "-forge") || slug.contains(needle))) {
                fallback = project;
            }
        }
        return fallback;
    }

    private static Comparator<RemoteFile> comparator() {
        return Comparator
                .comparing((RemoteFile file) -> file.version() == null ? null : file.version(), Comparator.nullsLast(Comparator.reverseOrder()))
                .thenComparingInt(RemoteFile::releaseRank)
                .thenComparing(RemoteFile::published, Comparator.nullsLast(Comparator.reverseOrder()));
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static String trim(String url) {
        if (url.endsWith("/")) {
            return url.substring(0, url.length() - 1);
        }
        return url;
    }
}
