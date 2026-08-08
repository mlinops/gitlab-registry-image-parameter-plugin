package io.jenkins.plugins.gitlabregistry;

import com.cloudbees.plugins.credentials.Credentials;
import com.cloudbees.plugins.credentials.CredentialsMatchers;
import com.cloudbees.plugins.credentials.CredentialsProvider;
import com.cloudbees.plugins.credentials.common.StandardCredentials;
import com.cloudbees.plugins.credentials.common.StandardListBoxModel;
import com.cloudbees.plugins.credentials.common.StandardUsernamePasswordCredentials;
import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.Extension;
import hudson.model.Item;
import hudson.model.Job;
import hudson.model.ParameterDefinition;
import hudson.model.ParameterValue;
import hudson.model.ParametersDefinitionProperty;
import hudson.model.SimpleParameterDefinition;
import hudson.model.StringParameterValue;
import hudson.security.ACL;
import hudson.util.FormValidation;
import hudson.util.ListBoxModel;
import jenkins.model.Jenkins;
import net.sf.json.JSONObject;
import org.jenkinsci.Symbol;
import org.jenkinsci.plugins.plaincredentials.StringCredentials;
import org.kohsuke.stapler.AncestorInPath;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;
import org.kohsuke.stapler.HttpResponses;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.Stapler;
import org.kohsuke.stapler.StaplerRequest2;
import org.kohsuke.stapler.StaplerResponse2;
import org.kohsuke.stapler.lang.Klass;
import org.kohsuke.stapler.verb.POST;

import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.ServletException;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import java.util.stream.Collectors;

/**
 * Lists container image tags from a GitLab project registry.
 * {@code defaultVersion} is preselected and forced into the dropdown if missing.
 * {@code regex} / {@code exclude} filter tags. Build page loads tags lazily via AJAX.
 * <p>
 * Pipeline Syntax / Snippet Generator: mandatory {@code name}, {@code repoUrl}, {@code imageName};
 * optional fields via setters (omitted when left at defaults).
 */
public class GitLabRegistryImageParameterDefinition extends SimpleParameterDefinition {

    private static final long serialVersionUID = 17L;
    private static final Pattern DIGITS = Pattern.compile("(\\d+)");
    private static final String ERROR_PREFIX = "ERROR:";

    /** Env-var / Pipeline-friendly parameter names. */
    static final Pattern NAME_PATTERN = Pattern.compile("^[A-Za-z_][A-Za-z0-9_]*$");
    /**
     * Docker image name path (lowercase components; see distribution reference).
     * Allows nested paths like {@code group/service}.
     */
    static final Pattern IMAGE_NAME_PATTERN = Pattern.compile(
            "^[a-z0-9]+(?:[._-][a-z0-9]+)*(?:/[a-z0-9]+(?:[._-][a-z0-9]+)*)*$");
    /** Description text; Jenkins markup formatter still sanitizes on render. */
    static final Pattern DESCRIPTION_UNSAFE = Pattern.compile(
            "(?i)<\\s*script|javascript:|vbscript:|data\\s*:\\s*text/html|on\\w+\\s*=");

    private final String repoUrl;
    private final String imageName;

    private String credentialsId = "";
    /**
     * Legacy Pipeline/XStream field. Always empty after migration;
     * first value is migrated into {@link #defaultVersion}.
     * Retained for compatibility; remove in {@code 6.0.0}.
     */
    private List<String> include = Collections.emptyList();
    private String regex = "";
    private String exclude = "";
    private String defaultVersion = "";
    private int perPage = 50;
    private int maxPages = 2;
    private int maxRows = 30;
    private String sortMode = "NONE";
    private int connectTimeoutMs = 5000;
    private int readTimeoutMs = 5000;
    private boolean skipSslVerification;

    /**
     * Pipeline Syntax / DataBound: only required fields.
     * Optional options use {@link DataBoundSetter} so Snippet Generator omits defaults.
     */
    @DataBoundConstructor
    public GitLabRegistryImageParameterDefinition(String name, String repoUrl, String imageName) {
        super(name);
        this.repoUrl = repoUrl == null ? "" : repoUrl.trim();
        this.imageName = imageName == null ? "" : imageName.trim();
    }

    /**
     * Full constructor for tests / XStream migration helpers.
     * Prefer {@link #GitLabRegistryImageParameterDefinition(String, String, String)} + setters in Pipeline.
     */
    @Deprecated
    public GitLabRegistryImageParameterDefinition(
            String name,
            String description,
            String repoUrl,
            String credentialsId,
            String imageName,
            Object include,
            String regex,
            String exclude,
            String defaultVersion,
            int perPage,
            int maxPages,
            int maxRows,
            String sortMode,
            int connectTimeoutMs,
            int readTimeoutMs,
            boolean skipSslVerification) {
        this(name, repoUrl, imageName);
        setDescription(description);
        setCredentialsId(credentialsId);
        setDefaultVersion(defaultVersion);
        setInclude(include);
        setRegex(regex);
        setExclude(exclude);
        setPerPage(perPage);
        setMaxPages(maxPages);
        setMaxRows(maxRows);
        setSortMode(sortMode);
        setConnectTimeoutMs(connectTimeoutMs);
        setReadTimeoutMs(readTimeoutMs);
        setSkipSslVerification(skipSslVerification);
    }

    private Object readResolve() {
        migrateIncludeToDefault();
        this.credentialsId = credentialsId == null ? "" : credentialsId;
        this.regex = regex == null ? "" : regex;
        this.exclude = exclude == null ? "" : exclude;
        this.defaultVersion = defaultVersion == null ? "" : defaultVersion;
        this.sortMode = (sortMode == null || sortMode.isBlank())
                ? "NONE"
                : sortMode.trim().toUpperCase(Locale.ROOT);
        this.perPage = clamp(perPage, 1, 100, 50);
        this.maxPages = clamp(maxPages, 1, 50, 2);
        this.maxRows = clamp(maxRows, 1, 500, 30);
        this.connectTimeoutMs = clamp(connectTimeoutMs, 100, 120_000, 5000);
        this.readTimeoutMs = clamp(readTimeoutMs, 100, 120_000, 5000);
        this.include = Collections.emptyList();
        return this;
    }

    private void migrateIncludeToDefault() {
        if (defaultVersion != null && !defaultVersion.isEmpty()) {
            return;
        }
        if (include != null && !include.isEmpty()) {
            String first = include.get(0);
            if (first != null && !first.isBlank()) {
                this.defaultVersion = first.trim();
            }
        }
    }

    static int clamp(int value, int min, int max, int defaultValue) {
        if (value <= 0) {
            return defaultValue;
        }
        return Math.min(max, Math.max(min, value));
    }

    @DataBoundSetter
    @Override
    public void setDescription(String description) {
        super.setDescription(description);
    }

    @DataBoundSetter
    public void setCredentialsId(String credentialsId) {
        this.credentialsId = credentialsId == null ? "" : credentialsId.trim();
    }

    /**
     * @deprecated use {@link #setDefaultVersion(String)}; kept for old Pipeline scripts until 6.0.0.
     */
    @Deprecated
    @DataBoundSetter
    public void setInclude(Object include) {
        List<String> legacy = normalizeInclude(include);
        this.include = Collections.emptyList();
        if ((defaultVersion == null || defaultVersion.isEmpty()) && !legacy.isEmpty()) {
            this.defaultVersion = legacy.get(0);
        }
    }

    @DataBoundSetter
    public void setRegex(String regex) {
        this.regex = regex == null ? "" : regex.trim();
    }

    @DataBoundSetter
    public void setExclude(String exclude) {
        this.exclude = exclude == null ? "" : exclude.trim();
    }

    @DataBoundSetter
    public void setDefaultVersion(String defaultVersion) {
        this.defaultVersion = defaultVersion == null ? "" : defaultVersion.trim();
    }

    @DataBoundSetter
    public void setPerPage(int perPage) {
        this.perPage = clamp(perPage, 1, 100, 50);
    }

    @DataBoundSetter
    public void setMaxPages(int maxPages) {
        this.maxPages = clamp(maxPages, 1, 50, 2);
    }

    @DataBoundSetter
    public void setMaxRows(int maxRows) {
        this.maxRows = clamp(maxRows, 1, 500, 30);
    }

    @DataBoundSetter
    public void setSortMode(String sortMode) {
        this.sortMode = (sortMode == null || sortMode.isBlank())
                ? "NONE"
                : sortMode.trim().toUpperCase(Locale.ROOT);
    }

    @DataBoundSetter
    public void setConnectTimeoutMs(int connectTimeoutMs) {
        this.connectTimeoutMs = clamp(connectTimeoutMs, 100, 120_000, 5000);
    }

    @DataBoundSetter
    public void setReadTimeoutMs(int readTimeoutMs) {
        this.readTimeoutMs = clamp(readTimeoutMs, 100, 120_000, 5000);
    }

    @DataBoundSetter
    public void setSkipSslVerification(boolean skipSslVerification) {
        this.skipSslVerification = skipSslVerification;
    }

    public String getRepoUrl() {
        return repoUrl;
    }

    public String getCredentialsId() {
        return credentialsId;
    }

    public String getImageName() {
        return imageName;
    }

    /**
     * JavaBeans getter for {@link #setInclude(Object)} so Pipeline Snippet Generator can uninstantiate.
     *
     * @deprecated always empty after migration; use {@link #getDefaultVersion()}. Removed in 6.0.0.
     */
    @Deprecated
    public List<String> getInclude() {
        return include == null ? Collections.emptyList() : include;
    }

    public String getRegex() {
        return regex;
    }

    public String getExclude() {
        return exclude;
    }

    public String getDefaultVersion() {
        return defaultVersion;
    }

    public boolean isSkipSslVerification() {
        return skipSslVerification;
    }

    /** Default for Build preselection (legacy {@code include} already migrated). */
    public String getResolvedDefault() {
        return defaultVersion == null ? "" : defaultVersion;
    }

    public String getFetchTagsUrl() {
        Job<?, ?> job = resolveJob();
        if (job == null) {
            return "";
        }
        return job.getUrl() + "descriptorByName/" + getDescriptor().getId() + "/fetchTags";
    }

    private Job<?, ?> resolveJob() {
        StaplerRequest2 req = Stapler.getCurrentRequest2();
        if (req != null) {
            return req.findAncestorObject(Job.class);
        }
        return null;
    }

    public int getPerPage() {
        return perPage;
    }

    public int getMaxPages() {
        return maxPages;
    }

    public int getMaxRows() {
        return maxRows;
    }

    public String getSortMode() {
        return sortMode;
    }

    public int getConnectTimeoutMs() {
        return connectTimeoutMs;
    }

    public int getReadTimeoutMs() {
        return readTimeoutMs;
    }

    /**
     * Sync fallback for scripts. Build page uses lazy AJAX instead.
     *
     * @deprecated requires {@link Item} context; use {@link #getChoices(Item)} or AJAX {@code fetchTags}.
     */
    @Deprecated
    @NonNull
    public List<String> getChoices() {
        List<String> err = new ArrayList<>();
        err.add(ERROR_PREFIX + " Item context is required to load tags");
        return err;
    }

    @NonNull
    public List<String> getChoices(Item context) {
        if (context == null) {
            List<String> err = new ArrayList<>();
            err.add(ERROR_PREFIX + " Item context is required to load tags");
            return err;
        }
        try {
            return fetchTags(context);
        } catch (Exception e) {
            List<String> err = new ArrayList<>();
            if (!defaultVersion.isEmpty()) {
                err.add(defaultVersion);
            }
            err.add(ERROR_PREFIX + " " + e.getClass().getSimpleName() + ": " + safeMessage(e));
            return err;
        }
    }

    @Override
    public ParameterValue createValue(String value) {
        String fallback = getResolvedDefault();
        if (value == null || value.isBlank() || isErrorValue(value)) {
            return new StringParameterValue(getName(), fallback, getDescription());
        }
        return new StringParameterValue(getName(), value, getDescription());
    }

    @Override
    public ParameterValue createValue(StaplerRequest2 req, JSONObject jo) {
        if (jo == null || !jo.has("value") || jo.get("value") == null) {
            return getDefaultParameterValue();
        }
        return createValue(jo.optString("value", getResolvedDefault()));
    }

    @Override
    public ParameterValue getDefaultParameterValue() {
        return createValue(getResolvedDefault());
    }

    static boolean isErrorValue(String value) {
        return value != null && value.trim().startsWith(ERROR_PREFIX);
    }

    /**
     * @deprecated always requires Job {@link Item}; use {@link #fetchTags(Item)}.
     */
    @Deprecated
    List<String> fetchTags() throws IOException {
        throw new IOException("fetchTags requires a Job Item context (use Build AJAX fetchTags)");
    }

    List<String> fetchTags(Item context) throws IOException {
        if (context == null) {
            throw new IOException("fetchTags requires a Job Item context");
        }
        if (repoUrl.isBlank()) {
            throw new IOException("repoUrl is required");
        }
        if (imageName.isBlank()) {
            throw new IOException("imageName is required");
        }

        String resolvedDefault = getResolvedDefault();
        String tagsCacheKey = credentialsId + "|" + repoUrl + "|" + imageName.toLowerCase(Locale.ROOT)
                + "|" + perPage + "|" + maxPages + "|" + maxRows
                + "|" + regex + "|" + exclude
                + "|" + resolvedDefault + "|" + sortMode + "|" + skipSslVerification;
        List<String> cachedTags = RegistryCaches.getTags(tagsCacheKey);
        if (cachedTags != null) {
            return cachedTags;
        }

        ParsedRepo parsed = parseRepoUrl(repoUrl);
        String token = resolveToken(credentialsId, context);
        GitLabRegistryClient client = new GitLabRegistryClient(
                connectTimeoutMs, readTimeoutMs, skipSslVerification);
        List<String> tags = client.listTagNames(
                parsed, token, imageName, perPage, maxPages, credentialsId, skipSslVerification);

        tags = applyRegexFilters(tags, regex, exclude);
        tags = sortTags(tags, sortMode);
        if (tags.size() > maxRows) {
            tags = new ArrayList<>(tags.subList(0, maxRows));
        }
        tags = ensureDefaultPresent(tags, resolvedDefault);

        RegistryCaches.putTags(tagsCacheKey, tags);
        return tags;
    }

    static List<String> applyRegexFilters(List<String> tags, String regex, String exclude) throws IOException {
        List<String> result = tags;
        if (exclude != null && !exclude.isBlank()) {
            Pattern pattern = compileRegex(exclude, "exclude");
            result = result.stream().filter(t -> !pattern.matcher(t).find()).collect(Collectors.toList());
        }
        if (regex != null && !regex.isBlank()) {
            Pattern pattern = compileRegex(regex, "regex");
            result = result.stream().filter(t -> pattern.matcher(t).find()).collect(Collectors.toList());
        }
        return result;
    }

    private static Pattern compileRegex(String value, String field) throws IOException {
        if (value.length() > FormValidators.MAX_REGEX_LENGTH) {
            throw new IOException(
                    "invalid " + field + ": longer than " + FormValidators.MAX_REGEX_LENGTH + " characters");
        }
        try {
            return Pattern.compile(value);
        } catch (PatternSyntaxException e) {
            throw new IOException("invalid " + field + ": " + e.getDescription());
        }
    }

    /**
     * Ensures {@code defaultVersion} is in the list (prepended when newly added).
     */
    static List<String> ensureDefaultPresent(List<String> tags, String defaultVersion) {
        if (defaultVersion == null || defaultVersion.isBlank()) {
            return tags;
        }
        if (tags.contains(defaultVersion)) {
            return tags;
        }
        LinkedHashSet<String> out = new LinkedHashSet<>();
        out.add(defaultVersion);
        out.addAll(tags);
        return new ArrayList<>(out);
    }

    static List<String> normalizeInclude(Object raw) {
        if (raw == null) {
            return Collections.emptyList();
        }
        List<String> out = new ArrayList<>();
        if (raw instanceof Iterable && !(raw instanceof CharSequence)) {
            for (Object o : (Iterable<?>) raw) {
                if (o == null) {
                    continue;
                }
                splitInto(out, o.toString());
            }
        } else {
            splitInto(out, raw.toString());
        }
        return out;
    }

    private static void splitInto(List<String> out, String s) {
        if (s == null || s.isBlank()) {
            return;
        }
        for (String part : s.split("[,\\n]")) {
            String t = part.trim();
            if (!t.isEmpty() && !out.contains(t)) {
                out.add(t);
            }
        }
    }

    static boolean matchesImage(String name, String path, String wanted) {
        if (wanted == null || wanted.isBlank()) {
            return false;
        }
        String w = wanted.trim();
        String n = name == null ? "" : name.trim();
        String p = path == null ? "" : path.trim();
        if (n.equalsIgnoreCase(w) || p.equalsIgnoreCase(w)) {
            return true;
        }
        String wLower = w.toLowerCase(Locale.ROOT);
        if (p.toLowerCase(Locale.ROOT).endsWith("/" + wLower)) {
            return true;
        }
        int slash = p.lastIndexOf('/');
        String last = slash >= 0 ? p.substring(slash + 1) : p;
        return last.equalsIgnoreCase(w);
    }

    static ParsedRepo parseRepoUrl(String repoUrl) {
        if (repoUrl == null || repoUrl.isBlank()) {
            throw new IllegalArgumentException(
                    "GitLab repo URL is required (http:// or https://…/group/project)");
        }
        String clean = repoUrl.trim().replaceAll("\\.git$", "").replaceAll("/+$", "");
        boolean https = clean.regionMatches(true, 0, "https://", 0, 8);
        boolean http = clean.regionMatches(true, 0, "http://", 0, 7);
        if (!https && !http) {
            throw new IllegalArgumentException(
                    "GitLab repo URL must start with http:// or https://");
        }
        int schemeEnd = clean.indexOf("://");
        int pathStart = clean.indexOf('/', schemeEnd + 3);
        if (pathStart < 0 || pathStart == clean.length() - 1) {
            throw new IllegalArgumentException(
                    "GitLab repo URL must include project path, e.g. https://gitlab.example/group/proj");
        }
        String base = clean.substring(0, pathStart);
        String projectPath = clean.substring(pathStart + 1).replaceAll("^/+", "");
        if (projectPath.isBlank()) {
            throw new IllegalArgumentException(
                    "GitLab repo URL must include project path, e.g. https://gitlab.example/group/proj");
        }
        ConnectionTester.assertHostAllowed(base);
        return new ParsedRepo(base, projectPath);
    }

    static FormValidation validateParameterName(String value) {
        return FormValidators.validateParameterName(value);
    }

    static FormValidation validateDescription(String value) {
        return FormValidators.validateDescription(value);
    }

    static FormValidation validateImageName(String value) {
        return FormValidators.validateImageName(value);
    }

    static FormValidation validatePositiveInt(String raw, String label, int min, int max, int defaultValue) {
        return FormValidators.validatePositiveInt(raw, label, min, max, defaultValue);
    }

    static String resolveToken(String credentialsId, Item item) {
        if (credentialsId == null || credentialsId.isBlank()) {
            return ""; // public project / anonymous API
        }
        Credentials creds = findAllowedCredential(credentialsId, item);
        if (creds instanceof StandardUsernamePasswordCredentials) {
            return ((StandardUsernamePasswordCredentials) creds).getPassword().getPlainText();
        }
        if (creds instanceof StringCredentials) {
            return ((StringCredentials) creds).getSecret().getPlainText();
        }
        throw new IllegalStateException(
                "credentials '" + credentialsId + "' type is not supported "
                        + "(use Username/Password or Secret text)");
    }

    static Credentials findAllowedCredential(String credentialsId, Item item) {
        if (credentialsId == null || credentialsId.isBlank()) {
            throw new IllegalStateException("credentialsId is required");
        }
        CredentialsMatcherAllowed matcher = new CredentialsMatcherAllowed(credentialsId);
        List<StandardCredentials> candidates;
        if (item != null) {
            @SuppressWarnings("deprecation")
            List<StandardCredentials> scoped = CredentialsProvider.lookupCredentials(
                    StandardCredentials.class,
                    item,
                    ACL.SYSTEM,
                    Collections.emptyList());
            candidates = scoped;
        } else {
            @SuppressWarnings("deprecation")
            List<StandardCredentials> root = CredentialsProvider.lookupCredentials(
                    StandardCredentials.class,
                    Jenkins.get(),
                    ACL.SYSTEM,
                    Collections.emptyList());
            candidates = root;
        }
        Credentials creds = CredentialsMatchers.firstOrNull(candidates, matcher);
        if (creds == null) {
            throw new IllegalStateException(
                    "credentials '" + credentialsId + "' not found, not accessible, "
                            + "or not an allowed type (Username/Password or Secret text)");
        }
        return creds;
    }

    private static final class CredentialsMatcherAllowed
            implements com.cloudbees.plugins.credentials.CredentialsMatcher {
        private final String id;

        CredentialsMatcherAllowed(String id) {
            this.id = id;
        }

        @Override
        public boolean matches(Credentials item) {
            if (!(item instanceof StandardCredentials)) {
                return false;
            }
            if (!id.equals(((StandardCredentials) item).getId())) {
                return false;
            }
            return item instanceof StandardUsernamePasswordCredentials
                    || item instanceof StringCredentials;
        }
    }

    static List<String> sortTags(List<String> tags, String mode) {
        List<String> copy = new ArrayList<>(tags);
        switch (mode) {
            case "ASC":
                copy.sort(String::compareTo);
                break;
            case "DESC":
                copy.sort(Comparator.reverseOrder());
                break;
            case "ASC_SMART":
            case "ASCENDING_SMART":
                copy.sort(Comparator.comparing(GitLabRegistryImageParameterDefinition::smartKey));
                break;
            case "DESC_SMART":
            case "DESCENDING_SMART":
                copy.sort(Comparator.comparing(GitLabRegistryImageParameterDefinition::smartKey).reversed());
                break;
            case "NONE":
            default:
                break;
        }
        return copy;
    }

    static String smartKey(String s) {
        String lower = s.toLowerCase(Locale.ROOT);
        Matcher m = DIGITS.matcher(lower);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            String num = m.group(1);
            String padded = "0".repeat(Math.max(0, 20 - num.length())) + num;
            m.appendReplacement(sb, Matcher.quoteReplacement(padded));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    private static String safeMessage(Exception e) {
        String msg = e.getMessage();
        if (msg == null) {
            return "";
        }
        return msg.length() > 160 ? msg.substring(0, 160) : msg;
    }

    static final class ParsedRepo {
        final String base;
        final String projectPath;

        ParsedRepo(String base, String projectPath) {
            this.base = base;
            this.projectPath = projectPath;
        }
    }

    @Extension
    @Symbol("gitLabRegistryImage")
    public static class DescriptorImpl extends ParameterDescriptor {
        @Override
        @NonNull
        public String getDisplayName() {
            return "GitLab Registry Image Tag";
        }

        /**
         * Serves field/type help without {@code X-Plugin-From}, so the UI tip does not append
         * "(from GitLab Registry Image Parameter)".
         */
        @Override
        public void doHelp(StaplerRequest2 req, StaplerResponse2 rsp) throws IOException, ServletException {
            String path = req.getRestOfPath();
            if (path.contains("..")) {
                throw new ServletException("Illegal path: " + path);
            }
            path = path.replace('/', '-');

            for (Klass<?> c = getKlass(); c != null; c = c.getSuperClass()) {
                RequestDispatcher rd = Stapler.getCurrentRequest2().getView(c, "help" + path);
                if (rd != null) {
                    rd.forward(req, rsp);
                    return;
                }
                URL url = staticHelpUrl(c, path);
                if (url != null) {
                    rsp.setContentType("text/html;charset=UTF-8");
                    try (InputStream in = url.openStream()) {
                        String literal = new String(in.readAllBytes(), StandardCharsets.UTF_8);
                        literal = hudson.Util.replaceMacro(
                                literal, Map.of("rootURL", req.getContextPath()));
                        rsp.getWriter().print(wrapHelpHtml(literal));
                    }
                    return;
                }
            }
            rsp.sendError(404);
        }

        /** Inline styles loaded from classpath {@code help-inline.css} via {@link HelpHtmlSupport}. */
        private static String wrapHelpHtml(String literal) {
            return HelpHtmlSupport.wrap(literal);
        }

        /** Cache-bust query for webapp assets (pom / plugin version). */
        public String getAssetVersion() {
            try {
                hudson.PluginWrapper pw = Jenkins.get().getPluginManager()
                        .getPlugin("gitlab-registry-image-parameter");
                if (pw != null && pw.getVersion() != null && !pw.getVersion().isBlank()) {
                    return pw.getVersion();
                }
            } catch (Exception ignored) {
                // fall through
            }
            return "999999-SNAPSHOT";
        }

        private static URL staticHelpUrl(Klass<?> c, String suffix) {
            Locale locale = Stapler.getCurrentRequest2().getLocale();
            String base = "help" + suffix;
            URL url = c.getResource(base + '_' + locale.getLanguage() + '_' + locale.getCountry()
                    + '_' + locale.getVariant() + ".html");
            if (url != null) {
                return url;
            }
            url = c.getResource(base + '_' + locale.getLanguage() + '_' + locale.getCountry() + ".html");
            if (url != null) {
                return url;
            }
            url = c.getResource(base + '_' + locale.getLanguage() + ".html");
            if (url != null) {
                return url;
            }
            return c.getResource(base + ".html");
        }

        private static void checkConfigurePermission(Item item) {
            if (item != null) {
                item.checkPermission(Item.CONFIGURE);
            } else {
                Jenkins.get().checkPermission(Jenkins.ADMINISTER);
            }
        }

        @POST
        public FormValidation doCheckName(@AncestorInPath Item item, @QueryParameter String value) {
            checkConfigurePermission(item);
            return validateParameterName(value);
        }

        @POST
        public FormValidation doCheckDescription(@AncestorInPath Item item, @QueryParameter String value) {
            checkConfigurePermission(item);
            return validateDescription(value);
        }

        /**
         * Format-only URL check (scheme, path, host allowlist). Does <b>not</b> call GitLab.
         * Live connectivity is {@link #doTestConnection} via the «Test connection» button.
         */
        @POST
        public FormValidation doCheckRepoUrl(@AncestorInPath Item item, @QueryParameter String value) {
            checkConfigurePermission(item);
            if (value == null || value.isBlank()) {
                return FormValidation.error("GitLab repo URL is required (http:// or https://…/group/project)");
            }
            try {
                parseRepoUrl(value);
                return FormValidation.ok();
            } catch (Exception e) {
                return FormValidation.error(e.getMessage());
            }
        }

        /**
         * One-shot GitLab probe (project + registry list). Invoked only by «Test connection».
         */
        @POST
        public FormValidation doTestConnection(
                @AncestorInPath Item item,
                @QueryParameter String repoUrl,
                @QueryParameter String credentialsId,
                @QueryParameter boolean skipSslVerification,
                @QueryParameter String connectTimeoutMs,
                @QueryParameter String readTimeoutMs) {
            checkConfigurePermission(item);
            if (repoUrl == null || repoUrl.isBlank()) {
                return FormValidation.error("Set GitLab Repo URL before testing connection");
            }
            final ParsedRepo parsed;
            try {
                parsed = parseRepoUrl(repoUrl);
            } catch (Exception e) {
                return FormValidation.error(e.getMessage());
            }
            return ConnectionTester.test(
                    item, parsed, credentialsId, skipSslVerification, connectTimeoutMs, readTimeoutMs);
        }

        @POST
        public FormValidation doCheckSkipSslVerification(
                @AncestorInPath Item item,
                @QueryParameter boolean value) {
            checkConfigurePermission(item);
            if (value) {
                return FormValidation.warning(
                        "Insecure: TLS certificate/hostname verification will be skipped. "
                                + "Use only for trusted internal GitLab. Re-run «Test connection» after changing.");
            }
            return FormValidation.ok();
        }

        @POST
        public FormValidation doCheckImageName(@AncestorInPath Item item, @QueryParameter String value) {
            checkConfigurePermission(item);
            return validateImageName(value);
        }

        @POST
        public FormValidation doCheckRegex(@AncestorInPath Item item, @QueryParameter String value) {
            return checkOptionalRegex(item, value, "Regex");
        }

        @POST
        public FormValidation doCheckExclude(@AncestorInPath Item item, @QueryParameter String value) {
            return checkOptionalRegex(item, value, "Exclude");
        }

        private static FormValidation checkOptionalRegex(Item item, String value, String label) {
            checkConfigurePermission(item);
            return FormValidators.validateOptionalRegex(value, label);
        }

        @POST
        public FormValidation doCheckPerPage(@AncestorInPath Item item, @QueryParameter String value) {
            checkConfigurePermission(item);
            return validatePositiveInt(value, "Values per page", 1, 100, 50);
        }

        @POST
        public FormValidation doCheckMaxPages(@AncestorInPath Item item, @QueryParameter String value) {
            checkConfigurePermission(item);
            return validatePositiveInt(value, "Max Pages", 1, 50, 2);
        }

        @POST
        public FormValidation doCheckMaxRows(@AncestorInPath Item item, @QueryParameter String value) {
            checkConfigurePermission(item);
            return validatePositiveInt(value, "Max values in list", 1, 500, 30);
        }

        @POST
        public FormValidation doCheckConnectTimeoutMs(@AncestorInPath Item item, @QueryParameter String value) {
            checkConfigurePermission(item);
            return validatePositiveInt(value, "Connect timeout (ms)", 100, 120_000, 5000);
        }

        @POST
        public FormValidation doCheckReadTimeoutMs(@AncestorInPath Item item, @QueryParameter String value) {
            checkConfigurePermission(item);
            return validatePositiveInt(value, "Read timeout (ms)", 100, 120_000, 5000);
        }

        @POST
        public ListBoxModel doFillCredentialsIdItems(
                @AncestorInPath Item item, @QueryParameter String credentialsId) {
            StandardListBoxModel model = new StandardListBoxModel();
            model.includeEmptyValue();
            com.cloudbees.plugins.credentials.CredentialsMatcher typeMatcher = CredentialsMatchers.anyOf(
                    CredentialsMatchers.instanceOf(StandardUsernamePasswordCredentials.class),
                    CredentialsMatchers.instanceOf(StringCredentials.class));
            if (item == null) {
                if (!Jenkins.get().hasPermission(Jenkins.ADMINISTER)) {
                    model.includeCurrentValue(credentialsId);
                    return model;
                }
                model.includeMatchingAs(
                        ACL.SYSTEM,
                        Jenkins.get(),
                        StandardCredentials.class,
                        Collections.emptyList(),
                        typeMatcher);
                model.includeCurrentValue(credentialsId);
                return model;
            }
            if (!item.hasPermission(Item.EXTENDED_READ)
                    && !item.hasPermission(CredentialsProvider.USE_ITEM)) {
                model.includeCurrentValue(credentialsId);
                return model;
            }
            model.includeMatchingAs(
                    ACL.SYSTEM,
                    item,
                    StandardCredentials.class,
                    Collections.emptyList(),
                    typeMatcher);
            model.includeCurrentValue(credentialsId);
            return model;
        }

        public ListBoxModel doFillSortModeItems() {
            ListBoxModel m = new ListBoxModel();
            m.add("NONE", "NONE");
            m.add("DESC", "DESC");
            m.add("ASC", "ASC");
            m.add("DESC_SMART", "DESC_SMART");
            m.add("ASC_SMART", "ASC_SMART");
            return m;
        }

        @POST
        public void doFetchTags(
                @AncestorInPath Item item,
                @QueryParameter String name,
                StaplerResponse2 rsp) throws IOException {
            if (item == null) {
                StaplerRequest2 req = Stapler.getCurrentRequest2();
                if (req != null) {
                    item = req.findAncestorObject(Item.class);
                }
            }
            if (item == null) {
                throw HttpResponses.forbidden();
            }
            item.checkPermission(Item.BUILD);

            JSONObject json = new JSONObject();
            try {
                GitLabRegistryImageParameterDefinition def = resolveDefinition(item, name);
                List<String> tags = def.fetchTags(item);
                json.put("ok", true);
                json.put("tags", tags);
                json.put("defaultVersion", def.getResolvedDefault());
            } catch (Exception e) {
                json.put("ok", false);
                json.put("error", e.getClass().getSimpleName() + ": " + safeMessage(e));
                json.put("tags", Collections.emptyList());
            }
            rsp.setStatus(200);
            rsp.setContentType("application/json;charset=UTF-8");
            rsp.getWriter().print(json);
        }

        static GitLabRegistryImageParameterDefinition resolveDefinition(Item item, String name)
                throws IOException {
            if (name == null || name.isBlank()) {
                throw new IOException("parameter name is required");
            }
            if (!(item instanceof Job)) {
                throw new IOException("fetchTags requires a Job context");
            }
            Job<?, ?> job = (Job<?, ?>) item;
            ParametersDefinitionProperty props = job.getProperty(ParametersDefinitionProperty.class);
            if (props == null) {
                throw new IOException("job has no parameters");
            }
            ParameterDefinition pd = props.getParameterDefinition(name.trim());
            if (!(pd instanceof GitLabRegistryImageParameterDefinition)) {
                throw new IOException("parameter '" + name + "' is not a GitLab Registry Image parameter");
            }
            return (GitLabRegistryImageParameterDefinition) pd;
        }
    }
}
