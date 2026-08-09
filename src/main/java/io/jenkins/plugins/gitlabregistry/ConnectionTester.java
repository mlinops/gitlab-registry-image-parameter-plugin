package io.jenkins.plugins.gitlabregistry;

import hudson.model.Item;
import hudson.util.FormValidation;

import java.net.InetAddress;
import java.net.URI;
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
            String connectTimeoutRaw,
            String readTimeoutRaw) {
        int connectMs = parseTimeoutOrDefault(connectTimeoutRaw, 5000);
        int readMs = parseTimeoutOrDefault(readTimeoutRaw, 5000);
        try {
            String token = GitLabRegistryImageParameterDefinition.resolveToken(credentialsId, item);
            GitLabRegistryClient client = new GitLabRegistryClient(connectMs, readMs);

            String details = client.probeAccess(parsed, token);
            String auth = (token == null || token.isBlank()) ? "anonymous/public" : "with credentials";
            String msg = "Connection successful (" + auth + ")";
            if (details != null && !details.isBlank()) {
                String d = details.length() > 120 ? details.substring(0, 120) + "…" : details;
                msg = msg + " — " + d;
            }
            return FormValidation.ok(msg);
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
     * Extract host from base URL; supports IPv6 in brackets and hostnames {@code URI.getHost()} rejects
     * (e.g. underscores). Falls back to manual authority parsing when host is null.
     */
    static String extractHost(String baseUrl) {
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new IllegalArgumentException("GitLab repo URL host is missing");
        }
        try {
            String host = java.net.URI.create(baseUrl).getHost();
            if (host != null && !host.isBlank()) {
                return stripIpv6Brackets(host);
            }
        } catch (Exception ignored) {
            // fall through to manual parse
        }
        String s = baseUrl.trim();
        int scheme = s.indexOf("://");
        if (scheme < 0) {
            throw new IllegalArgumentException("GitLab repo URL host is invalid");
        }
        String authority = s.substring(scheme + 3);
        int slash = authority.indexOf('/');
        if (slash >= 0) {
            authority = authority.substring(0, slash);
        }
        int at = authority.lastIndexOf('@');
        if (at >= 0) {
            authority = authority.substring(at + 1);
        }
        if (authority.startsWith("[")) {
            int end = authority.indexOf(']');
            if (end < 0) {
                throw new IllegalArgumentException("GitLab repo URL host is invalid");
            }
            String host = authority.substring(1, end);
            if (host.isBlank()) {
                throw new IllegalArgumentException("GitLab repo URL host is missing");
            }
            return stripIpv6Brackets(host);
        }
        // hostname or IPv4, optional :port — split on last ':' only if looks like port
        int colon = authority.lastIndexOf(':');
        String host = colon > 0 ? authority.substring(0, colon) : authority;
        if (host.isBlank()) {
            throw new IllegalArgumentException("GitLab repo URL host is missing");
        }
        // Reject whitespace / obvious garbage
        if (host.indexOf(' ') >= 0 || host.indexOf('\\') >= 0) {
            throw new IllegalArgumentException("GitLab repo URL host is invalid");
        }
        return stripIpv6Brackets(host);
    }

    private static String stripIpv6Brackets(String host) {
        if (host != null && host.length() >= 2 && host.charAt(0) == '[' && host.charAt(host.length() - 1) == ']') {
            return host.substring(1, host.length() - 1);
        }
        return host;
    }

    static void assertHostAllowed(String baseUrl) {
        assertHostAllowed(baseUrl, DnsPolicy.DEFER_UNKNOWN_HOST);
    }

    /**
     * Re-check host for a request/response {@link URI} (DNS rebinding / TOCTOU).
     * Used with {@code java.net.http.HttpClient} ({@code followRedirects=NEVER}).
     */
    static void assertUriHostAllowed(URI uri) {
        if (uri == null) {
            throw new IllegalArgumentException("GitLab HTTP URI is missing");
        }
        String scheme = uri.getScheme();
        if (scheme == null
                || (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme))) {
            throw new IllegalArgumentException("GitLab HTTP URI scheme is invalid");
        }
        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            assertHostAllowed(uri.toString(), DnsPolicy.REQUIRE_RESOLVED);
            return;
        }
        host = stripIpv6Brackets(host);
        int port = uri.getPort();
        boolean ipv6 = host.indexOf(':') >= 0;
        String hostPart = ipv6 ? "[" + host + "]" : host;
        String authority = port > 0 ? hostPart + ":" + port : hostPart;
        assertHostAllowed(scheme + "://" + authority + "/", DnsPolicy.REQUIRE_RESOLVED);
    }

    static void assertHostAllowed(String baseUrl, DnsPolicy policy) {
        String host = extractHost(baseUrl);
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
                || isIpv4LoopbackLiteral(h);
    }

    private static boolean isIpv4LoopbackLiteral(String h) {
        // Only 127.0.0.0/8 dotted quads — do not treat hostnames starting with "127." as loopback.
        if (!h.startsWith("127.")) {
            return false;
        }
        String[] parts = h.split("\\.");
        if (parts.length != 4) {
            return false;
        }
        for (String p : parts) {
            try {
                int n = Integer.parseInt(p);
                if (n < 0 || n > 255) {
                    return false;
                }
            } catch (NumberFormatException e) {
                return false;
            }
        }
        return true;
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
