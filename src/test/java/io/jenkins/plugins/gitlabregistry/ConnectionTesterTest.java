package io.jenkins.plugins.gitlabregistry;

import org.junit.Test;

import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class ConnectionTesterTest {

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
