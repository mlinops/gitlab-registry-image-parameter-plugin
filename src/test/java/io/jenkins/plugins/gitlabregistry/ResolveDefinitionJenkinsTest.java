package io.jenkins.plugins.gitlabregistry;

import hudson.model.FreeStyleProject;
import hudson.model.ParametersDefinitionProperty;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

@WithJenkins
public class ResolveDefinitionJenkinsTest {

    @Test
    public void resolveDefinition_findsParameterOnJob(JenkinsRule j) throws Exception {
        FreeStyleProject project = j.createFreeStyleProject();
        GitLabRegistryImageParameterDefinition def = new GitLabRegistryImageParameterDefinition(
                "IMAGE_TAG",
                "https://gitlab.example/group/proj.git",
                "my-image");
        project.addProperty(new ParametersDefinitionProperty(def));

        GitLabRegistryImageParameterDefinition resolved =
                GitLabRegistryImageParameterDefinition.DescriptorImpl.resolveDefinition(
                        project, "IMAGE_TAG");
        assertSame(def, resolved);
    }

    @Test
    public void resolveDefinition_rejectsUnknownName(JenkinsRule j) throws Exception {
        FreeStyleProject project = j.createFreeStyleProject();
        GitLabRegistryImageParameterDefinition def = new GitLabRegistryImageParameterDefinition(
                "IMAGE_TAG",
                "https://gitlab.example/group/proj.git",
                "my-image");
        project.addProperty(new ParametersDefinitionProperty(def));

        try {
            GitLabRegistryImageParameterDefinition.DescriptorImpl.resolveDefinition(
                    project, "OTHER");
            fail("expected IOException");
        } catch (IOException e) {
            assertTrue(e.getMessage().contains("not a GitLab Registry Image parameter"));
        }
    }
}
