package io.jenkins.plugins.gitlabregistry;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link HelpHtmlSupport} (no JenkinsRule).
 */
public class HelpHtmlSupportTest {

    @Test
    public void inlineCss_loadsFromClasspath() {
        String css = HelpHtmlSupport.inlineCss();
        assertTrue(css.contains("--gri-help-lh"));
        assertTrue(css.contains("gri-help-nested"));
    }

    @Test
    public void wrap_addsStyleAndGriHelpRoot() {
        String wrapped = HelpHtmlSupport.wrap("<p>hi</p>");
        assertTrue(wrapped.contains("<style"));
        assertTrue(wrapped.contains("class=\"gri-help\""));
    }

    @Test
    public void wrap_addsGriHelpRootEvenWhenNestedPresent() {
        String html = "<div>\n  <p><b>Examples:</b></p>\n"
                + "  <div class=\"gri-help-nested\"><code>none</code></div>\n</div>";
        String wrapped = HelpHtmlSupport.wrap(html);
        assertTrue(wrapped.contains("class=\"gri-help\""));
        assertTrue(wrapped.contains("gri-help-nested"));
        assertTrue(wrapped.contains("gri-help-example"));
        assertTrue(HelpHtmlSupport.rootHasGriHelpClass(
                wrapped.substring(wrapped.indexOf("<div class=\"gri-help\""))));
    }

    @Test
    public void rootHasGriHelpClass_ignoresNestedOnly() {
        assertFalse(HelpHtmlSupport.rootHasGriHelpClass(
                "<div class=\"gri-help-nested\"><code>x</code></div>"));
        assertTrue(HelpHtmlSupport.rootHasGriHelpClass(
                "<div class=\"gri-help\"><div class=\"gri-help-nested\"></div></div>"));
    }
}
