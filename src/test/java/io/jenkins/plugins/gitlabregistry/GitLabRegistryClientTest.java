package io.jenkins.plugins.gitlabregistry;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * HTTP client tests using JDK {@link com.sun.net.httpserver.HttpServer}.
 */
public class GitLabRegistryClientTest {

    private HttpServer server;
    private String base;
    private final AtomicInteger projectStatus = new AtomicInteger(200);
    private final AtomicInteger registryStatus = new AtomicInteger(200);
    private final AtomicReference<String> lastAuth = new AtomicReference<>();
    private final AtomicReference<String> lastPrivateToken = new AtomicReference<>();

    @BeforeEach
    public void startServer() throws IOException {
        System.setProperty(ConnectionTester.ALLOW_LOOPBACK_FOR_TESTS_PROP, "true");
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v4/projects/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            lastAuth.set(exchange.getRequestHeaders().getFirst("Authorization"));
            lastPrivateToken.set(exchange.getRequestHeaders().getFirst("PRIVATE-TOKEN"));
            if (path.contains("/tags")) {
                respond(exchange, registryStatus.get(),
                        "[{\"name\":\"1.0.0\"},{\"name\":\"1.0.1\"}]");
            } else if (path.contains("/registry/repositories")) {
                respond(exchange, registryStatus.get(),
                        "[{\"id\":\"7\",\"project_id\":\"42\",\"name\":\"elasticsearch\","
                                + "\"path\":\"group/proj/elasticsearch\"}]");
            } else {
                respond(exchange, projectStatus.get(),
                        "{\"path_with_namespace\":\"group/proj\"}");
            }
        });
        server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    public void stopServer() {
        System.clearProperty(ConnectionTester.ALLOW_LOOPBACK_FOR_TESTS_PROP);
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    public void probeAccess_success_anonymous() throws Exception {
        GitLabRegistryClient client = new GitLabRegistryClient(2000, 2000);
        String details = client.probeAccess(
                new GitLabRegistryImageParameterDefinition.ParsedRepo(base, "group/proj"),
                null);
        assertTrue(details.contains("group/proj"));
        assertTrue(details.contains("registry list readable"));
        assertEquals(null, lastAuth.get());
        assertEquals(null, lastPrivateToken.get());
    }

    @Test
    public void probeAccess_sendsTokenHeaders() throws Exception {
        GitLabRegistryClient client = new GitLabRegistryClient(2000, 2000);
        client.probeAccess(
                new GitLabRegistryImageParameterDefinition.ParsedRepo(base, "group/proj"),
                "secret-token");
        assertEquals("Bearer secret-token", lastAuth.get());
        assertEquals("secret-token", lastPrivateToken.get());
    }

    @Test
    public void probeAccess_mapsHttp401() {
        projectStatus.set(401);
        GitLabRegistryClient client = new GitLabRegistryClient(2000, 2000);
        try {
            client.probeAccess(
                    new GitLabRegistryImageParameterDefinition.ParsedRepo(base, "group/proj"),
                    "bad");
            fail("expected IOException");
        } catch (IOException e) {
            assertTrue(e.getMessage().contains("401"));
        }
    }

    @Test
    public void probeAccess_mapsHttp403() {
        projectStatus.set(403);
        GitLabRegistryClient client = new GitLabRegistryClient(2000, 2000);
        try {
            client.probeAccess(
                    new GitLabRegistryImageParameterDefinition.ParsedRepo(base, "group/proj"),
                    "t");
            fail("expected IOException");
        } catch (IOException e) {
            assertTrue(e.getMessage().contains("403"));
        }
    }

    @Test
    public void probeAccess_mapsHttp404() {
        projectStatus.set(404);
        GitLabRegistryClient client = new GitLabRegistryClient(2000, 2000);
        try {
            client.probeAccess(
                    new GitLabRegistryImageParameterDefinition.ParsedRepo(base, "group/proj"),
                    "t");
            fail("expected IOException");
        } catch (IOException e) {
            assertTrue(e.getMessage().contains("404"));
        }
    }

    @Test
    public void probeAccess_registryFailure() {
        registryStatus.set(403);
        GitLabRegistryClient client = new GitLabRegistryClient(2000, 2000);
        try {
            client.probeAccess(
                    new GitLabRegistryImageParameterDefinition.ParsedRepo(base, "group/proj"),
                    "t");
            fail("expected IOException");
        } catch (IOException e) {
            assertTrue(e.getMessage().contains("403"));
        }
    }

    @Test
    public void listTagNames_resolvesRepositoryAndReturnsTags() throws Exception {
        GitLabRegistryClient client = new GitLabRegistryClient(2000, 2000);
        List<String> tags = client.listTagNames(
                new GitLabRegistryImageParameterDefinition.ParsedRepo(base, "group/proj"),
                "secret-token",
                "elasticsearch",
                50,
                2,
                "cred");
        assertEquals(List.of("1.0.0", "1.0.1"), tags);
        assertEquals("Bearer secret-token", lastAuth.get());
    }

    @Test
    public void listTagNames_rejectsUnknownImage() {
        GitLabRegistryClient client = new GitLabRegistryClient(2000, 2000);
        try {
            client.listTagNames(
                    new GitLabRegistryImageParameterDefinition.ParsedRepo(base, "group/proj"),
                    "t",
                    "missing-image",
                    50,
                    2,
                    "cred");
            fail("expected IOException");
        } catch (IOException e) {
            assertTrue(e.getMessage().contains("not found"));
        }
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }
}
