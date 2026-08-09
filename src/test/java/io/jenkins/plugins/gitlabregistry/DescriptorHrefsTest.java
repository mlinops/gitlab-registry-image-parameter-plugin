package io.jenkins.plugins.gitlabregistry;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

public class DescriptorHrefsTest {

    @Test
    public void join_emptyRoot_leadingSlashSegment_notProtocolRelative() {
        String href = DescriptorHrefs.join(
                "",
                "/job/foo",
                "descriptorByName/io.jenkins.plugins.gitlabregistry.GitLabRegistryImageParameterDefinition",
                "fetchTags");
        assertEquals(
                "/job/foo/descriptorByName/io.jenkins.plugins.gitlabregistry.GitLabRegistryImageParameterDefinition/fetchTags",
                href);
        assertFalse(href.startsWith("//"));
    }

    @Test
    public void join_withContextPath() {
        String href = DescriptorHrefs.join(
                "/jenkins",
                "job/foo",
                "descriptorByName/id",
                "fetchTags");
        assertEquals("/jenkins/job/foo/descriptorByName/id/fetchTags", href);
    }

    @Test
    public void join_contextPathTrailingSlash() {
        assertEquals(
                "/jenkins/job/foo/descriptorByName/id/fetchTags",
                DescriptorHrefs.join("/jenkins/", "/job/foo/", "/descriptorByName/id/", "/fetchTags/"));
    }
}
