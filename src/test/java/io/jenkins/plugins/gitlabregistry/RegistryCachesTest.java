package io.jenkins.plugins.gitlabregistry;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;

public class RegistryCachesTest {

    @Test
    public void putGetTags_returnsDefensiveCopy() {
        String key = "tags-" + UUID.randomUUID();
        assertNull(RegistryCaches.getTags(key));

        List<String> original = new ArrayList<>(Arrays.asList("1.0.0", "2.0.0"));
        RegistryCaches.putTags(key, original);
        original.add("mutated");

        List<String> hit = RegistryCaches.getTags(key);
        assertNotNull(hit);
        assertEquals(Arrays.asList("1.0.0", "2.0.0"), hit);

        hit.add("also-mutated");
        assertEquals(Arrays.asList("1.0.0", "2.0.0"), RegistryCaches.getTags(key));
        assertNotSame(hit, RegistryCaches.getTags(key));
    }

    @Test
    public void putGetRepo_roundTrip() {
        String key = "repo-" + UUID.randomUUID();
        assertNull(RegistryCaches.getRepo(key));
        RegistryCaches.putRepo(key, "42", "99");
        RegistryCaches.CachedRepo hit = RegistryCaches.getRepo(key);
        assertNotNull(hit);
        assertEquals("42", hit.repositoryId);
        assertEquals("99", hit.projectId);
    }
}
