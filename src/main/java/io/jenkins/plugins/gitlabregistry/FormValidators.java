package io.jenkins.plugins.gitlabregistry;

import hudson.util.FormValidation;

import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Shared form-field validators (config UI + unit tests).
 */
final class FormValidators {

    /** Cap user regex length to reduce ReDoS risk on Build fetch. */
    static final int MAX_REGEX_LENGTH = 200;

    private FormValidators() {
    }

    static FormValidation validateParameterName(String value) {
        if (value == null || value.isBlank()) {
            return FormValidation.error("Name is required — use an env-var style identifier, e.g. DMS_VERSION");
        }
        String name = value.trim();
        if (!GitLabRegistryImageParameterDefinition.NAME_PATTERN.matcher(name).matches()) {
            return FormValidation.error(
                    "Invalid name '" + name + "'. Allowed: letters, digits, underscore; "
                            + "must start with a letter or '_'. Example: DMS_VERSION");
        }
        return FormValidation.ok();
    }

    static FormValidation validateDescription(String value) {
        if (value == null || value.isBlank()) {
            return FormValidation.ok();
        }
        if (GitLabRegistryImageParameterDefinition.DESCRIPTION_UNSAFE.matcher(value).find()) {
            return FormValidation.error(
                    "Description contains disallowed markup (script/handlers/javascript). "
                            + "Use plain text only.");
        }
        return FormValidation.ok();
    }

    static FormValidation validateImageName(String value) {
        if (value == null || value.isBlank()) {
            return FormValidation.error("Image name is required, e.g. document-management-service");
        }
        String name = value.trim();
        if (!GitLabRegistryImageParameterDefinition.IMAGE_NAME_PATTERN.matcher(name).matches()) {
            return FormValidation.error(
                    "Invalid image name '" + name + "'. Use Docker-style lowercase names "
                            + "(a-z, 0-9, '.', '_', '-'; '/' for path). No spaces or '*'.");
        }
        return FormValidation.ok();
    }

    static FormValidation validatePositiveInt(String raw, String label, int min, int max, int defaultValue) {
        if (raw == null || raw.isBlank()) {
            return FormValidation.ok("Using default " + defaultValue);
        }
        final int value;
        try {
            value = Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            return FormValidation.error(label + " must be an integer, e.g. " + defaultValue);
        }
        if (value < min || value > max) {
            return FormValidation.error(label + " must be between " + min + " and " + max + " (got " + value + ")");
        }
        return FormValidation.ok();
    }

    static FormValidation validateOptionalRegex(String value, String label) {
        if (value == null || value.isBlank()) {
            return FormValidation.ok();
        }
        if (value.length() > MAX_REGEX_LENGTH) {
            return FormValidation.error(
                    label + " must be at most " + MAX_REGEX_LENGTH + " characters (got " + value.length() + ")");
        }
        try {
            Pattern.compile(value);
            return FormValidation.ok();
        } catch (PatternSyntaxException e) {
            return FormValidation.error(
                    label + " is not a valid Java regex: " + e.getDescription()
                            + " (index " + e.getIndex() + ")");
        }
    }
}
