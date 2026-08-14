package com.jcoder.config;

import java.util.Objects;

/**
 * 作者：亥子曜
 * -后之览者，亦将有感于斯文
 */
public record PromptConfig(
        String identity,
        String behavior,
        String toolUsage,
        String codeQuality,
        String security,
        String taskPattern,
        String outputStyle
) {

    public enum Section {
        IDENTITY("identity.md"),
        BEHAVIOR("behavior.md"),
        TOOL_USAGE("tool-usage.md"),
        CODE_QUALITY("code-quality.md"),
        SECURITY("security.md"),
        TASK_PATTERN("task-pattern.md"),
        OUTPUT_STYLE("output-style.md");

        private final String fileName;

        Section(String fileName) {
            this.fileName = fileName;
        }

        public String fileName() {
            return fileName;
        }
    }

    public String prompt(Section section) {
        return switch (Objects.requireNonNull(section)) {
            case IDENTITY -> identity;
            case BEHAVIOR -> behavior;
            case TOOL_USAGE -> toolUsage;
            case CODE_QUALITY -> codeQuality;
            case SECURITY -> security;
            case TASK_PATTERN -> taskPattern;
            case OUTPUT_STYLE -> outputStyle;
        };
    }

    public PromptConfig {
        identity = normalize(identity);
        behavior = normalize(behavior);
        toolUsage = normalize(toolUsage);
        codeQuality = normalize(codeQuality);
        security = normalize(security);
        taskPattern = normalize(taskPattern);
        outputStyle = normalize(outputStyle);
    }

    private static String normalize(String content) {
        return content == null ? "" : content.strip();
    }
}
