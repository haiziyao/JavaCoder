package com.jcoder.skill;

import java.nio.file.Path;
import java.util.Objects;

/**
 * 一份已经从磁盘成功加载的完整 Skill。
 *
 * metadata：
 *     用于发现和展示。
 *
 * body：
 *     激活后注入模型的完整 SOP。
 *
 * sourceDirectory/source：
 *     记录它来自哪个目录和哪个作用域，
 *     方便显示覆盖关系以及后续 reload。
 */
public record SkillDefinition(
        SkillMetadata metadata,
        String body,
        Path sourceDirectory,
        Source source
) {
    public SkillDefinition {
        Objects.requireNonNull(
                metadata,
                "metadata"
        );

        if (body == null || body.isBlank()) {
            throw new IllegalArgumentException(
                    "skill body is required"
            );
        }

        body = body.strip();

        Objects.requireNonNull(
                sourceDirectory,
                "sourceDirectory"
        );

        sourceDirectory =
                sourceDirectory
                        .toAbsolutePath()
                        .normalize();

        Objects.requireNonNull(
                source,
                "source"
        );
    }

    public String name() {
        return metadata.name();
    }

    public enum Source {
        USER,
        PROJECT
    }
}