package io.jenkins.plugins.gitlabregistry;

import hudson.model.Item;
import hudson.util.FormValidation;

import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Locale;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * One-shot GitLab reachability check for the configuration UI + SSRF host guards.
 */
final class ConnectionTester {

    private static final Logger LOGGER = Logger.getLogger(ConnectionTester.class.getName());

    /**
     * System property for unit tests that bind JDK {@link com.sun.net.httpserver.HttpServer} on loopback.
     * Never enable in production.
     */
    static final String ALLOW_LOOPBACK_FOR_TESTS_PROP = "gitlab.registry.allowLoopbackForTests";

    enum DnsPolicy {
        /** Format check: unknown host is deferred to connect / Test connection. */
        DEFER_UNKNOWN_HOST,
        /** Connect-time: host must resolve; every address must pass the blocklist. */
        REQUIRE_RESOLVED
    }

    private ConnectionTester() {
    }

    static FormValidation test(
            Item item,
            GitLabRegistryImageParameterDefinition.ParsedRepo parsed,
            String credentialsId,
            boolean skipSsl,
            String connectTimeoutRaw,
            String readTimeoutRaw) {
        int connectMs = parseTimeoutOrDefault(connectTimeoutRaw, 5000);
        int readMs = parseTimeoutOrDefault(readTimeoutRaw, 5000);
        try {
            String token = GitLabRegistryImageParameterDefinition.resolveToken(credentialsId, item);
            if (skipSsl) {
                LOGGER.log(Level.WARNING,
                        "GitLab TLS verification disabled (skipSslVerification) for {0}",
                        parsed.base);
            }
            GitLabRegistryClient client = new GitLabRegistryClient(connectMs, readMs, skipSsl);
            client.probeAccess(parsed, token);
            String auth = (token == null || token.isBlank()) ? "anonymous/public" : "with credentials";
            return FormValidation.ok("Connection successful (" + auth + ")");
        } catch (Exception e) {
            String msg = e.getMessage();
            if (msg == null) {
                msg = e.getClass().getSimpleName();
            }
            if (msg.length() > 160) {
                msg = msg.substring(0, 160);
            }
            return FormValidation.error("Connection failed — " + msg);
        }
    }

    private static int parseTimeoutOrDefault(String raw, int defaultValue) {
        if (raw == null || raw.isBlank()) {
            return defaultValue;
        }
        try {
            int v = Integer.parseInt(raw.trim());
            return v > 0 ? Math.min(v, 120_000) : defaultValue;
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    /**
     * Blocks loopback / link-local / metadata targets (parse-time, unknown DNS deferred).
     * Site-local (RFC1918) is allowed — typical for internal GitLab.
     */
    static void assertHostAllowed(String baseUrl) {
        assertHostAllowed(baseUrl, DnsPolicy.DEFER_UNKNOWN_HOST);
    }

    /**
     * Re-check host after {@link HttpURLConnection#openConnection()} (DNS rebinding / TOCTOU).
     */
    static void assertHttpConnectionHostAllowed(HttpURLConnection conn) {
        if (conn == null || conn.getURL() == null) {
            throw new IllegalArgumentException("GitLab HTTP connection URL is missing");
        }
        java.net.URL u = conn.getURL();
        String host = u.getHost();
        if (host == null || host.isBlank()) {
            throw new IllegalArgumentException("GitLab HTTP connection host is missing");
        }
        int port = u.getPort();
        String authority = port > 0 ? host + ":" + port : host;
        assertHostAllowed(u.getProtocol() + "://" + authority + "/", DnsPolicy.REQUIRE_RESOLVED);
    }

    static void assertHostAllowed(String baseUrl, DnsPolicy policy) {
        String host;
        try {
            host = java.net.URI.create(baseUrl).getHost();
        } catch (Exception e) {
            throw new IllegalArgumentException("GitLab repo URL host is invalid");
        }
        if (host == null || host.isBlank()) {
            throw new IllegalArgumentException("GitLab repo URL host is missing");
        }
        String h = host.toLowerCase(Locale.ROOT);
        boolean testsAllowLoopback = Boolean.getBoolean(ALLOW_LOOPBACK_FOR_TESTS_PROP);
        if (!testsAllowLoopback && isBlockedHostname(h)) {
            throw blocked(host);
        }
        if (testsAllowLoopback && isMetadataHostname(h)) {
            throw blocked(host);
        }

        try {
            InetAddress[] addresses = InetAddress.getAllByName(host);
            if (addresses.length == 0) {
                if (policy == DnsPolicy.REQUIRE_RESOLVED) {
                    throw new IllegalArgumentException(
                            "GitLab repo URL host '" + host + "' could not be resolved");
                }
                return;
            }
            for (InetAddress addr : addresses) {
                if (isBlockedAddress(addr, testsAllowLoopback)) {
                    throw blocked(host);
                }
            }
        } catch (UnknownHostException e) {
            if (policy == DnsPolicy.REQUIRE_RESOLVED) {
                throw new IllegalArgumentException(
                        "GitLab repo URL host '" + host + "' could not be resolved", e);
            }
            LOGGER.log(Level.FINE, "repoUrl host DNS lookup failed for {0}: {1}",
                    new Object[]{host, e.toString()});
        }
    }

    private static boolean isBlockedHostname(String h) {
        return h.equals("localhost")
                || h.endsWith(".localhost")
                || isMetadataHostname(h)
                || h.equals("0.0.0.0")
                || h.equals("::")
                || h.equals("::1")
                || h.startsWith("127.");
    }

    private static boolean isMetadataHostname(String h) {
        return h.equals("metadata")
                || h.equals("metadata.google.internal")
                || h.equals("metadata.google");
    }

    private static boolean isBlockedAddress(InetAddress addr, boolean testsAllowLoopback) {
        if (testsAllowLoopback && addr.isLoopbackAddress()) {
            return false;
        }
        if (addr.isAnyLocalAddress()
                || addr.isLoopbackAddress()
                || addr.isLinkLocalAddress()
                || addr.isMulticastAddress()) {
            return true;
        }
        byte[] raw = addr.getAddress();
        // IPv4: 169.254.0.0/16 already link-local; also block 0.0.0.0/8 leftovers
        if (raw.length == 4 && (raw[0] & 0xff) == 0) {
            return true;
        }
        return false;
    }

    private static IllegalArgumentException blocked(String host) {
        return new IllegalArgumentException(
                "GitLab repo URL host '" + host + "' is not allowed (loopback/link-local/metadata)");
    }
}
