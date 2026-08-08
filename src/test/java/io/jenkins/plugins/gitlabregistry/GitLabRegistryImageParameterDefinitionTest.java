package io.jenkins.plugins.gitlabregistry;

import hudson.model.ParameterValue;
import hudson.model.StringParameterValue;
import hudson.util.FormValidation;
import net.sf.json.JSONObject;
import org.junit.Test;
import org.kohsuke.stapler.StaplerRequest;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class GitLabRegistryImageParameterDefinitionTest {

    private static GitLabRegistryImageParameterDefinition sample() {
        return new GitLabRegistryImageParameterDefinition(
                "DMS_VERSION",
                "desc",
                "https://gitlab.example/group/proj.git",
                "gitlab_token",
                "document-management-service",
                null,
                "",
                "",
                "none",
                50,
                2,
                30,
                "NONE",
                5000,
                5000,
                false);
    }

    @Test
    public void parseRepoUrl_splitsBaseAndPath() {
        GitLabRegistryImageParameterDefinition.ParsedRepo p =
                GitLabRegistryImageParameterDefinition.parseRepoUrl(
                        "https://gitlab.best.local/docker/life-crm/lifecrm-main.git");
        assertEquals("https://gitlab.best.local", p.base);
        assertEquals("docker/life-crm/lifecrm-main", p.projectPath);
    }

    @Test
    public void parseRepoUrl_allowsHttp() {
        GitLabRegistryImageParameterDefinition.ParsedRepo p =
                GitLabRegistryImageParameterDefinition.parseRepoUrl(
                        "http://gitlab.internal/group/proj");
        assertEquals("http://gitlab.internal", p.base);
        assertEquals("group/proj", p.projectPath);
    }

    @Test
    public void parseRepoUrl_rejectsBadScheme() {
        try {
            GitLabRegistryImageParameterDefinition.parseRepoUrl("ftp://gitlab.example/group/proj");
            fail("expected IllegalArgumentException");
        } catch (IllegalArgumentException e) {
            assertTrue(e.getMessage().contains("http:// or https://"));
            assertTrue(e.getMessage().startsWith("GitLab repo URL"));
        }
    }

    @Test
    public void parseRepoUrl_rejectsLoopbackAndMetadata() {
        for (String url : new String[]{
                "https://localhost/group/proj",
                "https://127.0.0.1/group/proj",
                "https://169.254.169.254/latest",
                "https://metadata.google.internal/group/proj"
        }) {
            try {
                GitLabRegistryImageParameterDefinition.parseRepoUrl(url);
                fail("expected rejection for " + url);
            } catch (IllegalArgumentException e) {
                assertTrue(e.getMessage().toLowerCase().contains("not allowed")
                        || e.getMessage().toLowerCase().contains("loopback")
                        || e.getMessage().toLowerCase().contains("link-local")
                        || e.getMessage().toLowerCase().contains("metadata"));
            }
        }
    }

    @Test
    public void clamp_appliesUiCaps() {
        assertEquals(50, GitLabRegistryImageParameterDefinition.clamp(0, 1, 100, 50));
        assertEquals(100, GitLabRegistryImageParameterDefinition.clamp(999, 1, 100, 50));
        assertEquals(1, GitLabRegistryImageParameterDefinition.clamp(1, 1, 100, 50));
        assertEquals(50, GitLabRegistryImageParameterDefinition.clamp(50, 1, 50, 2));
    }

    @Test
    public void ctor_clampsPipelineBypassValues() {
        GitLabRegistryImageParameterDefinition def = new GitLabRegistryImageParameterDefinition(
                "X",
                "",
                "https://gitlab.example/g/p.git",
                "",
                "img",
                null,
                "",
                "",
                "",
                999,
                999,
                9999,
                "NONE",
                999_999,
                999_999,
                false);
        assertEquals(100, def.getPerPage());
        assertEquals(50, def.getMaxPages());
        assertEquals(500, def.getMaxRows());
        assertEquals(120_000, def.getConnectTimeoutMs());
        assertEquals(120_000, def.getReadTimeoutMs());
    }

    @Test
    public void validateParameterName() {
        assertEquals(FormValidation.Kind.OK,
                GitLabRegistryImageParameterDefinition.validateParameterName("DMS_VERSION").kind);
        assertEquals(FormValidation.Kind.ERROR,
                GitLabRegistryImageParameterDefinition.validateParameterName("").kind);
        assertEquals(FormValidation.Kind.ERROR,
                GitLabRegistryImageParameterDefinition.validateParameterName("1BAD").kind);
        assertEquals(FormValidation.Kind.ERROR,
                GitLabRegistryImageParameterDefinition.validateParameterName("bad-name").kind);
    }

    @Test
    public void validateImageName_dockerStyle() {
        assertEquals(FormValidation.Kind.OK,
                GitLabRegistryImageParameterDefinition.validateImageName("document-management-service").kind);
        assertEquals(FormValidation.Kind.OK,
                GitLabRegistryImageParameterDefinition.validateImageName("group/app").kind);
        assertEquals(FormValidation.Kind.ERROR,
                GitLabRegistryImageParameterDefinition.validateImageName("Has Space").kind);
        assertEquals(FormValidation.Kind.ERROR,
                GitLabRegistryImageParameterDefinition.validateImageName("foo*").kind);
        assertEquals(FormValidation.Kind.ERROR,
                GitLabRegistryImageParameterDefinition.validateImageName("UPPER").kind);
    }

    @Test
    public void validateDescription_blocksScript() {
        assertEquals(FormValidation.Kind.OK,
                GitLabRegistryImageParameterDefinition.validateDescription("Use **none**").kind);
        assertEquals(FormValidation.Kind.ERROR,
                GitLabRegistryImageParameterDefinition.validateDescription("<script>alert(1)</script>").kind);
    }

    @Test
    public void smartKey_padsNumbers() {
        String a = GitLabRegistryImageParameterDefinition.smartKey("v1.2.10");
        String b = GitLabRegistryImageParameterDefinition.smartKey("v1.2.9");
        assertTrue(a.compareTo(b) > 0);
    }

    @Test
    public void sortTags_desc() {
        List<String> sorted = GitLabRegistryImageParameterDefinition.sortTags(
                Arrays.asList("a", "c", "b"), "DESC");
        assertEquals(Arrays.asList("c", "b", "a"), sorted);
    }

    @Test
    public void matchesImage_byNameAndPathSuffix() {
        assertTrue(GitLabRegistryImageParameterDefinition.matchesImage(
                "document-management-service",
                "docker/life-crm/lifecrm-main/document-management-service",
                "document-management-service"));
        assertTrue(!GitLabRegistryImageParameterDefinition.matchesImage(
                "product-management-service",
                "docker/life-crm/lifecrm-main/product-management-service",
                "document-management-service"));
    }

    @Test
    public void applyRegexFilters_excludeThenRegex() throws IOException {
        List<String> tags = Arrays.asList("1.0.0", "1.0.0-snapshot", "2.0.0");
        assertEquals(
                Arrays.asList("1.0.0", "2.0.0"),
                GitLabRegistryImageParameterDefinition.applyRegexFilters(tags, "", "snapshot"));
        assertEquals(
                Arrays.asList("1.0.0", "1.0.0-snapshot"),
                GitLabRegistryImageParameterDefinition.applyRegexFilters(tags, "1\\.", ""));
    }

    @Test
    public void applyRegexFilters_invalidRegexThrowsIOException() {
        List<String> tags = Arrays.asList("1.0.0");
        try {
            GitLabRegistryImageParameterDefinition.applyRegexFilters(tags, "[", "");
            fail("expected IOException");
        } catch (IOException e) {
            assertTrue(e.getMessage().contains("invalid regex"));
        }
        try {
            GitLabRegistryImageParameterDefinition.applyRegexFilters(tags, "", "(");
            fail("expected IOException");
        } catch (IOException e) {
            assertTrue(e.getMessage().contains("invalid exclude"));
        }
    }

    @Test
    public void createValue_nullOrMissingJsonFallsBackToDefault() {
        GitLabRegistryImageParameterDefinition def = sample();
        ParameterValue fromNull = def.createValue((StaplerRequest) null, (JSONObject) null);
        assertEquals("none", ((StringParameterValue) fromNull).getValue());

        ParameterValue fromEmpty = def.createValue((StaplerRequest) null, new JSONObject());
        assertEquals("none", ((StringParameterValue) fromEmpty).getValue());
    }

    @Test
    public void createValue_rejectsErrorPrefix() {
        GitLabRegistryImageParameterDefinition def = sample();
        assertTrue(GitLabRegistryImageParameterDefinition.isErrorValue("ERROR: boom"));
        assertFalse(GitLabRegistryImageParameterDefinition.isErrorValue("v1.0.0"));

        ParameterValue pv = def.createValue("ERROR: HTTP 403");
        assertEquals("none", ((StringParameterValue) pv).getValue());
    }

    @Test
    public void applyRegexFilters_rejectsOverlongPattern() {
        String longPat = "a".repeat(FormValidators.MAX_REGEX_LENGTH + 1);
        try {
            GitLabRegistryImageParameterDefinition.applyRegexFilters(
                    Arrays.asList("v1.0"), longPat, "");
            fail("expected IOException");
        } catch (IOException e) {
            assertTrue(e.getMessage().contains("longer than"));
        }
    }

    @Test
    public void validateOptionalRegex_rejectsOverlong() {
        String longPat = "x".repeat(FormValidators.MAX_REGEX_LENGTH + 1);
        assertEquals(FormValidation.Kind.ERROR,
                FormValidators.validateOptionalRegex(longPat, "Regex").kind);
    }

    @Test
    public void fetchTags_withoutItem_throws() throws Exception {
        GitLabRegistryImageParameterDefinition def = sample();
        try {
            def.fetchTags();
            fail("expected IOException");
        } catch (IOException e) {
            assertTrue(e.getMessage().toLowerCase().contains("item"));
        }
    }

    @Test
    public void getChoices_withoutItem_returnsError() {
        List<String> choices = sample().getChoices();
        assertTrue(choices.get(0).startsWith("ERROR:"));
    }

    @Test
    public void ensureDefaultPresent_prependsWhenMissing() {
        List<String> withDefault = GitLabRegistryImageParameterDefinition.ensureDefaultPresent(
                Arrays.asList("1.0.0"), "none");
        assertEquals(Arrays.asList("none", "1.0.0"), withDefault);
    }

    @Test
    public void dataBound_minimalConstructor_andSetters() {
        GitLabRegistryImageParameterDefinition def = new GitLabRegistryImageParameterDefinition(
                "DMS_VERSION",
                "https://gitlab.example/group/proj.git",
                "document-management-service");
        assertEquals(50, def.getPerPage());
        assertEquals(2, def.getMaxPages());
        assertEquals("", def.getDefaultVersion());
        def.setDefaultVersion("none");
        def.setCredentialsId("tok");
        assertEquals("none", def.getDefaultVersion());
        assertEquals("tok", def.getCredentialsId());
    }

    @Test
    public void legacyInclude_migratesToDefaultWhenEmpty() {
        GitLabRegistryImageParameterDefinition def = new GitLabRegistryImageParameterDefinition(
                "X",
                "",
                "https://gitlab.example/g/p.git",
                "c",
                "img",
                Arrays.asList("none"),
                "",
                "",
                "",
                50,
                2,
                30,
                "NONE",
                5000,
                5000,
                false);
        assertEquals("none", def.getDefaultVersion());
        assertTrue(def.getInclude().isEmpty());
    }
}
