package io.jenkins.plugins.gitlabregistry;

import hudson.model.ParameterValue;
import hudson.model.StringParameterValue;
import hudson.util.FormValidation;
import net.sf.json.JSONObject;
import org.junit.jupiter.api.Test;
import org.kohsuke.stapler.StaplerRequest2;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

public class GitLabRegistryImageParameterDefinitionTest {

    private static GitLabRegistryImageParameterDefinition sample() {
        GitLabRegistryImageParameterDefinition def = new GitLabRegistryImageParameterDefinition(
                "DMS_VERSION",
                "https://gitlab.example/group/proj.git",
                "document-management-service");
        def.setDescription("desc");
        def.setCredentialsId("gitlab_token");
        def.setDefaultVersion("none");
        return def;
    }

    @Test
    public void parseRepoUrl_splitsBaseAndPath() {
        GitLabRegistryImageParameterDefinition.ParsedRepo p =
                GitLabRegistryImageParameterDefinition.parseRepoUrl(
                        "https://gitlab.example/group/nested/project.git");
        assertEquals("https://gitlab.example", p.base);
        assertEquals("group/nested/project", p.projectPath);
    }

    @Test
    public void parseRepoUrl_stripsUserInfoFromBase() {
        GitLabRegistryImageParameterDefinition.ParsedRepo p =
                GitLabRegistryImageParameterDefinition.parseRepoUrl(
                        "https://oauth2:glpat-secret@gitlab.example/group/proj.git");
        assertEquals("https://gitlab.example", p.base);
        assertEquals("group/proj", p.projectPath);
        assertFalse(p.base.contains("glpat-secret"));
        assertFalse(p.base.contains("@"));
    }

    @Test
    public void resolveDefinition_rejectsBlankName() {
        try {
            GitLabRegistryImageParameterDefinition.DescriptorImpl.resolveDefinition(null, "  ");
            fail("expected IOException");
        } catch (IOException e) {
            assertTrue(e.getMessage().contains("parameter name is required"));
        }
    }

    @Test
    public void resolveDefinition_requiresJobContext() {
        try {
            GitLabRegistryImageParameterDefinition.DescriptorImpl.resolveDefinition(null, "ES_VERSION");
            fail("expected IOException");
        } catch (IOException e) {
            assertTrue(e.getMessage().contains("Job context"));
        }
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
    public void setters_clampPipelineBypassValues() {
        GitLabRegistryImageParameterDefinition def = new GitLabRegistryImageParameterDefinition(
                "X",
                "https://gitlab.example/g/p.git",
                "img");
        def.setPerPage(999);
        def.setMaxPages(999);
        def.setMaxRows(9999);
        def.setConnectTimeoutMs(999_999);
        def.setReadTimeoutMs(999_999);
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
                "my-service",
                "group/project/my-service",
                "my-service"));
        assertTrue(!GitLabRegistryImageParameterDefinition.matchesImage(
                "other-service",
                "group/project/other-service",
                "my-service"));
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
        ParameterValue fromNull = def.createValue((StaplerRequest2) null, (JSONObject) null);
        assertEquals("none", ((StringParameterValue) fromNull).getValue());

        ParameterValue fromEmpty = def.createValue((StaplerRequest2) null, new JSONObject());
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
    public void getChoices_nullContext_returnsError() {
        List<String> choices = sample().getChoices(null);
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

}
