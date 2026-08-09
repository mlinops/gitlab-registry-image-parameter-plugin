package io.jenkins.plugins.gitlabregistry;

import org.junit.jupiter.api.Test;

import java.net.URI;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

public class ConnectionTesterTest {

    @Test
    public void extractHost_supportsUnderscoreHostname() {
        assertEquals("git_lab.example",
                ConnectionTester.extractHost("https://git_lab.example:8443/group/proj"));
    }

    @Test
    public void extractHost_supportsIpv6Brackets() {
        assertEquals("2001:db8::1",
                ConnectionTester.extractHost("https://[2001:db8::1]/group/proj"));
    }

    @Test
    public void assertUriHostAllowed_rejectsLoopback() {
        try {
            ConnectionTester.assertUriHostAllowed(URI.create("http://127.0.0.1:1/api"));
            fail("expected IllegalArgumentException");
        } catch (IllegalArgumentException e) {
            assertTrue(e.getMessage().toLowerCase().contains("not allowed"));
        }
    }

    @Test
    public void deferUnknownHost_doesNotThrowForUnresolvable() {
        ConnectionTester.assertHostAllowed(
                "https://no-such-host-gri-test.invalid/group/proj",
                ConnectionTester.DnsPolicy.DEFER_UNKNOWN_HOST);
    }

    @Test
    public void requireResolved_rejectsUnresolvable() {
        try {
            ConnectionTester.assertHostAllowed(
                    "https://no-such-host-gri-test.invalid/group/proj",
                    ConnectionTester.DnsPolicy.REQUIRE_RESOLVED);
            fail("expected IllegalArgumentException");
        } catch (IllegalArgumentException e) {
            assertTrue(e.getMessage().toLowerCase().contains("resolv"));
        }
    }

    @Test
    public void requireResolved_rejectsLoopback() {
        try {
            ConnectionTester.assertHostAllowed(
                    "http://127.0.0.1:1/",
                    ConnectionTester.DnsPolicy.REQUIRE_RESOLVED);
            fail("expected IllegalArgumentException");
        } catch (IllegalArgumentException e) {
            assertTrue(e.getMessage().toLowerCase().contains("not allowed"));
        }
    }

    @Test
    public void requireResolved_rejectsMetadata() {
        try {
            ConnectionTester.assertHostAllowed(
                    "http://169.254.169.254/",
                    ConnectionTester.DnsPolicy.REQUIRE_RESOLVED);
            fail("expected IllegalArgumentException");
        } catch (IllegalArgumentException e) {
            assertTrue(e.getMessage().toLowerCase().contains("not allowed"));
        }
    }
}
