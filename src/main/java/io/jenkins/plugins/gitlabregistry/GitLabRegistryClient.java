package io.jenkins.plugins.gitlabregistry;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import hudson.ProxyConfiguration;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Minimal GitLab Container Registry HTTP client (tags + repository lookup + access probe).
 */
final class GitLabRegistryClient {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final int connectTimeoutMs;
    private final int readTimeoutMs;

    GitLabRegistryClient(int connectTimeoutMs, int readTimeoutMs) {
        this.connectTimeoutMs = connectTimeoutMs;
        this.readTimeoutMs = readTimeoutMs;
    }

    /**
     * Connectivity check: project readable and registry list readable (1 row).
     *
     * @return human-readable success details
     */
    String probeAccess(GitLabRegistryImageParameterDefinition.ParsedRepo parsed, String token)
            throws IOException {
        String encodedProject = URLEncoder.encode(parsed.projectPath, StandardCharsets.UTF_8);
        JsonNode project = httpGetJson(parsed.base + "/api/v4/projects/" + encodedProject, token);
        String path = text(project, "path_with_namespace");
        if (path.isBlank()) {
            path = parsed.projectPath;
        }
        httpGetJson(
                parsed.base + "/api/v4/projects/" + encodedProject
                        + "/registry/repositories?per_page=1",
                token);
        return "project '" + path + "' reachable; registry list readable (read_api + read_registry)";
    }

    List<String> listTagNames(
            GitLabRegistryImageParameterDefinition.ParsedRepo parsed,
            String token,
            String imageName,
            int perPage,
            int maxPages,
            String credentialsId) throws IOException {
        String[] ids = resolveRepository(parsed, token, imageName, credentialsId);
        String repositoryId = ids[0];
        String projectId = ids[1];

        List<String> tags = new ArrayList<>();
        for (int page = 1; page <= maxPages; page++) {
            String api = parsed.base + "/api/v4/projects/" + projectId
                    + "/registry/repositories/" + repositoryId
                    + "/tags?per_page=" + perPage + "&page=" + page;
            JsonNode root = httpGetJson(api, token);
            if (!root.isArray() || root.size() == 0) {
                break;
            }
            for (JsonNode node : root) {
                String tagName = text(node, "name");
                if (!tagName.isBlank()) {
                    tags.add(tagName);
                }
            }
            if (root.size() < perPage) {
                break;
            }
        }
        return tags;
    }

    /** @return [repositoryId, projectId] */
    private String[] resolveRepository(
            GitLabRegistryImageParameterDefinition.ParsedRepo parsed,
            String token,
            String image,
            String credentialsId) throws IOException {
        String cred = credentialsId == null ? "" : credentialsId;
        String cacheKey = parsed.base + "|" + parsed.projectPath + "|" + image.toLowerCase(Locale.ROOT)
                + "|" + cred;
        RegistryCaches.CachedRepo cached = RegistryCaches.getRepo(cacheKey);
        if (cached != null) {
            return new String[]{cached.repositoryId, cached.projectId};
        }

        String encodedProject = URLEncoder.encode(parsed.projectPath, StandardCharsets.UTF_8);
        for (int page = 1; page <= 20; page++) {
            String api = parsed.base + "/api/v4/projects/" + encodedProject
                    + "/registry/repositories?per_page=100&page=" + page;
            JsonNode root = httpGetJson(api, token);
            if (!root.isArray() || root.size() == 0) {
                break;
            }
            for (JsonNode node : root) {
                if (GitLabRegistryImageParameterDefinition.matchesImage(
                        text(node, "name"), text(node, "path"), image)) {
                    String repositoryId = text(node, "id");
                    String projectId = text(node, "project_id");
                    if (repositoryId.isBlank() || projectId.isBlank()) {
                        throw new IOException("matched registry repository missing id/project_id");
                    }
                    RegistryCaches.putRepo(cacheKey, repositoryId, projectId);
                    return new String[]{repositoryId, projectId};
                }
            }
            if (root.size() < 100) {
                break;
            }
        }
        throw new IOException("image '" + image + "' not found in " + parsed.projectPath);
    }

    private JsonNode httpGetJson(String apiUrl, String token) throws IOException {
        final URI uri;
        try {
            uri = URI.create(apiUrl);
        } catch (IllegalArgumentException e) {
            throw new IOException("invalid GitLab API URL", e);
        }
        try {
            ConnectionTester.assertHostAllowed(apiUrl, ConnectionTester.DnsPolicy.REQUIRE_RESOLVED);
            ConnectionTester.assertUriHostAllowed(uri);
        } catch (IllegalArgumentException e) {
            throw new IOException(e.getMessage(), e);
        }

        HttpClient client = ProxyConfiguration.newHttpClientBuilder()
                .connectTimeout(Duration.ofMillis(Math.max(1, connectTimeoutMs)))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofMillis(Math.max(1, readTimeoutMs)))
                .GET();
        if (token != null && !token.isBlank()) {
            builder.header("PRIVATE-TOKEN", token);
            builder.header("Authorization", "Bearer " + token);
        }

        final HttpResponse<byte[]> response;
        try {
            response = client.send(builder.build(), HttpResponse.BodyHandlers.ofByteArray());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("GitLab HTTP request interrupted", e);
        }

        try {
            ConnectionTester.assertUriHostAllowed(response.uri());
        } catch (IllegalArgumentException e) {
            throw new IOException(e.getMessage(), e);
        }

        int code = response.statusCode();
        byte[] bytes = response.body() == null ? new byte[0] : response.body();
        if (code < 200 || code >= 300) {
            if (code == 401) {
                throw new IOException("HTTP 401 - invalid or missing GitLab token");
            }
            if (code == 403) {
                throw new IOException("HTTP 403 - need read_api+read_registry on token");
            }
            if (code == 404) {
                throw new IOException(
                        "HTTP 404 - project or registry not found "
                                + "(check URL path, project visibility, or credentials)");
            }
            throw new IOException("HTTP " + code);
        }
        return MAPPER.readTree(bytes);
    }

    private static String text(JsonNode node, String field) {
        JsonNode v = node.get(field);
        return v == null || v.isNull() ? "" : v.asText("");
    }
}
