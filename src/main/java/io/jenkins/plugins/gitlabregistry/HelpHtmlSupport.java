package io.jenkins.plugins.gitlabregistry;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Loads help CSS once from classpath and wraps help HTML for Jenkins tips.
 * {@code <link>} is ignored when help is injected via {@code innerHTML}.
 */
final class HelpHtmlSupport {

    private static final Logger LOGGER = Logger.getLogger(HelpHtmlSupport.class.getName());
    private static final AtomicReference<String> INLINE_CSS = new AtomicReference<>();
    private static final Pattern CLASS_ATTR = Pattern.compile("\\bclass\\s*=\\s*[\"']([^\"']*)[\"']");

    private HelpHtmlSupport() {
    }

    static String wrap(String literal) {
        String body = literal == null ? "" : literal.trim();
        body = body
                .replace("<p><b>Example:</b>", "<p class=\"gri-help-example\"><b>Example:</b>")
                .replace("<p><b>Examples:</b>", "<p class=\"gri-help-example\"><b>Examples:</b>");
        // Exact root class token only — substring match would also hit gri-help-nested.
        if (!rootHasGriHelpClass(body)) {
            if (body.startsWith("<div")) {
                body = body.replaceFirst("<div\\b", "<div class=\"gri-help\"");
            } else {
                body = "<div class=\"gri-help\">" + body + "</div>";
            }
        }
        return "<style type=\"text/css\">" + inlineCss() + "</style>\n" + body;
    }

    /** True only if the opening root tag has the exact class token {@code gri-help}. */
    static boolean rootHasGriHelpClass(String body) {
        if (body == null || body.isEmpty()) {
            return false;
        }
        int gt = body.indexOf('>');
        if (gt < 0) {
            return false;
        }
        String openTag = body.substring(0, gt);
        Matcher m = CLASS_ATTR.matcher(openTag);
        if (!m.find()) {
            return false;
        }
        for (String token : m.group(1).trim().split("\\s+")) {
            if ("gri-help".equals(token)) {
                return true;
            }
        }
        return false;
    }

    static String inlineCss() {
        String cached = INLINE_CSS.get();
        if (cached != null) {
            return cached;
        }
        synchronized (INLINE_CSS) {
            cached = INLINE_CSS.get();
            if (cached != null) {
                return cached;
            }
            cached = loadCss();
            INLINE_CSS.set(cached);
            return cached;
        }
    }

    private static String loadCss() {
        try (InputStream in = HelpHtmlSupport.class.getResourceAsStream("help-inline.css")) {
            if (in == null) {
                LOGGER.log(Level.WARNING, "help-inline.css missing on classpath");
                return "";
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8).trim();
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "failed to load help-inline.css", e);
            return "";
        }
    }
}
