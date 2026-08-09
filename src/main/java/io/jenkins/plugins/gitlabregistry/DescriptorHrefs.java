package io.jenkins.plugins.gitlabregistry;

/**
 * Joins Jenkins context path and descriptorByName path segments into one absolute path.
 */
final class DescriptorHrefs {

    private DescriptorHrefs() {
    }

    /**
     * @param rootURL request context path ({@code ""} or {@code "/jenkins"})
     * @param segments path segments; leading/trailing slashes ignored per segment
     * @return absolute path, e.g. {@code /job/foo/descriptorByName/id/fetchTags}
     */
    static String join(String rootURL, String... segments) {
        StringBuilder sb = new StringBuilder();
        String root = rootURL == null ? "" : rootURL.trim();
        if (!root.isEmpty()) {
            if (!root.startsWith("/")) {
                sb.append('/');
            }
            if (root.endsWith("/")) {
                sb.append(root, 0, root.length() - 1);
            } else {
                sb.append(root);
            }
        }
        if (segments != null) {
            for (String segment : segments) {
                if (segment == null) {
                    continue;
                }
                String s = segment.trim();
                while (s.startsWith("/")) {
                    s = s.substring(1);
                }
                while (s.endsWith("/")) {
                    s = s.substring(0, s.length() - 1);
                }
                if (s.isEmpty()) {
                    continue;
                }
                sb.append('/').append(s);
            }
        }
        if (sb.length() == 0) {
            return "/";
        }
        return sb.toString();
    }
}
