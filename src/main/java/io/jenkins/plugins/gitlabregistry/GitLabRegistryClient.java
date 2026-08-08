package io.jenkins.plugins.gitlabregistry;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Minimal GitLab Container Registry HTTP client (tags + repository lookup + access probe).
 */
final class GitLabRegistryClient {

    private static final Logger LOGGER = Logger.getLogger(GitLabRegistryClient.class.getName());
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final TrustManager[] TRUST_ALL = new TrustManager[]{
            new X509TrustManager() {
                @Override
                public void checkClientTrusted(X509Certificate[] chain, String authType) {
                }

                @Override
                public void checkServerTrusted(X509Certificate[] chain, String authType) {
                }

                @Override
                public X509Certificate[] getAcceptedIssuers() {
                    return new X509Certificate[0];
                }
            }
    };
    private static final HostnameVerifier TRUST_ALL_HOSTS = (hostname, session) -> true;
    private static volatile SSLSocketFactory trustAllSocketFactory;

    private final int connectTimeoutMs;
    private final int readTimeoutMs;
    private final boolean skipSslVerification;

    GitLabRegistryClient(int connectTimeoutMs, int readTimeoutMs, boolean skipSslVerification) {
        this.connectTimeoutMs = connectTimeoutMs;
        this.readTimeoutMs = readTimeoutMs;
        this.skipSslVerification = skipSslVerification;
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
            String credentialsId,
            boolean skipSslVerification) throws IOException {
        String[] ids = resolveRepository(
                parsed, token, imageName, credentialsId, skipSslVerification);
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
            String credentialsId,
            boolean skipSslVerification) throws IOException {
        String cred = credentialsId == null ? "" : credentialsId;
        String cacheKey = parsed.base + "|" + parsed.projectPath + "|" + image.toLowerCase(Locale.ROOT)
                + "|" + cred + "|" + skipSslVerification;
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
        URL url = URI.create(apiUrl).toURL();
        // Resolve and blocklist before connect (DNS rebinding / TOCTOU).
        try {
            ConnectionTester.assertHostAllowed(apiUrl, ConnectionTester.DnsPolicy.REQUIRE_RESOLVED);
        } catch (IllegalArgumentException e) {
            throw new IOException(e.getMessage(), e);
        }
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        try {
            try {
                ConnectionTester.assertHttpConnectionHostAllowed(conn);
            } catch (IllegalArgumentException e) {
                throw new IOException(e.getMessage(), e);
            }
            applySslIfNeeded(conn, url.getHost());
            if (token != null && !token.isBlank()) {
                conn.setRequestProperty("PRIVATE-TOKEN", token);
                conn.setRequestProperty("Authorization", "Bearer " + token);
            }
            conn.setConnectTimeout(connectTimeoutMs);
            conn.setReadTimeout(readTimeoutMs);
            conn.setInstanceFollowRedirects(false);
            conn.setRequestMethod("GET");
            int code = conn.getResponseCode();
            // Re-check after connect in case of redirect / URL mutation
            try {
                ConnectionTester.assertHttpConnectionHostAllowed(conn);
            } catch (IllegalArgumentException e) {
                throw new IOException(e.getMessage(), e);
            }
            byte[] bytes;
            try (InputStream stream = (code >= 200 && code < 300)
                    ? conn.getInputStream()
                    : conn.getErrorStream()) {
                bytes = stream == null ? new byte[0] : stream.readAllBytes();
            }
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
        } finally {
            conn.disconnect();
        }
    }

    private void applySslIfNeeded(HttpURLConnection conn, String host) throws IOException {
        if (!skipSslVerification || !(conn instanceof HttpsURLConnection)) {
            return;
        }
        LOGGER.log(Level.WARNING,
                "Disabling TLS certificate/hostname verification for GitLab host {0}",
                host == null ? "?" : host);
        HttpsURLConnection https = (HttpsURLConnection) conn;
        https.setSSLSocketFactory(trustAllFactory());
        https.setHostnameVerifier(TRUST_ALL_HOSTS);
    }

    private static SSLSocketFactory trustAllFactory() throws IOException {
        SSLSocketFactory cached = trustAllSocketFactory;
        if (cached != null) {
            return cached;
        }
        synchronized (GitLabRegistryClient.class) {
            if (trustAllSocketFactory == null) {
                try {
                    SSLContext ctx = SSLContext.getInstance("TLS");
                    ctx.init(null, TRUST_ALL, new java.security.SecureRandom());
                    trustAllSocketFactory = ctx.getSocketFactory();
                } catch (GeneralSecurityException e) {
                    throw new IOException("failed to disable SSL verification: " + e.getMessage(), e);
                }
            }
            return trustAllSocketFactory;
        }
    }

    private static String text(JsonNode node, String field) {
        JsonNode v = node.get(field);
        return v == null || v.isNull() ? "" : v.asText("");
    }
}
