package com.jcoder.skill;

import java.util.regex.Pattern;

/**
 * 用于 Skill 发现阶段的轻量元数据。
 *
 * description 同时承担两个作用：
 * 1. 告诉模型这个 Skill 做什么。
 * 2. 告诉模型什么情况下应该加载它。
 */
public record SkillMetadata(
        String name,
        String description
) {
    private static final Pattern VALID_NAME =
            Pattern.compile("[a-z0-9][a-z0-9_-]{0,63}");

    private static final int MAX_DESCRIPTION_LENGTH = 500;

    public SkillMetadata {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException(
                    "skill name is required"
            );
        }

        name = name.strip();

        if (!VALID_NAME.matcher(name).matches()) {
            throw new IllegalArgumentException(
                    "invalid skill name: "
                            + name
                            + "; expected lowercase letters, numbers, "
                            + "hyphens or underscores"
            );
        }

        if (description == null
                || description.isBlank()) {
            throw new IllegalArgumentException(
                    "skill description is required"
            );
        }

        description = description.strip();

        if (description.length()
                > MAX_DESCRIPTION_LENGTH) {
            throw new IllegalArgumentException(
                    "skill description is too long: "
                            + description.length()
                            + " characters"
            );
        }
    }
}