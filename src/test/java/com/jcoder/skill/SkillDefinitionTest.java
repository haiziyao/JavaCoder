package com.jcoder.skill;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SkillDefinitionTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void storesCompleteSkillAndNormalizesItsSourceDirectory() {
        SkillMetadata metadata = new SkillMetadata(
                "java-review",
                "Review Java source code."
        );
        Path nonNormalized = temporaryDirectory
                .resolve("nested")
                .resolve("..")
                .resolve("java-review");

        SkillDefinition definition = new SkillDefinition(
                metadata,
                "  # Workflow\n\nRead the project.  ",
                nonNormalized,
                SkillDefinition.Source.PROJECT
        );

        assertEquals("java-review", definition.name());
        assertEquals(metadata, definition.metadata());
        assertEquals("# Workflow\n\nRead the project.", definition.body());
        assertEquals(
                temporaryDirectory.resolve("java-review")
                        .toAbsolutePath()
                        .normalize(),
                definition.sourceDirectory()
        );
        assertEquals(SkillDefinition.Source.PROJECT, definition.source());
        assertTrue(definition.sourceDirectory().isAbsolute());
    }

    @Test
    void rejectsMissingRequiredFields() {
        SkillMetadata metadata = new SkillMetadata("java-review", "Review Java.");

        assertThrows(NullPointerException.class,
                () -> new SkillDefinition(
                        null, "body", temporaryDirectory,
                        SkillDefinition.Source.USER));
        assertThrows(IllegalArgumentException.class,
                () -> new SkillDefinition(
                        metadata, null, temporaryDirectory,
                        SkillDefinition.Source.USER));
        assertThrows(IllegalArgumentException.class,
                () -> new SkillDefinition(
                        metadata, " \n ", temporaryDirectory,
                        SkillDefinition.Source.USER));
        assertThrows(NullPointerException.class,
                () -> new SkillDefinition(
                        metadata, "body", null,
                        SkillDefinition.Source.USER));
        assertThrows(NullPointerException.class,
                () -> new SkillDefinition(
                        metadata, "body", temporaryDirectory,
                        null));
    }
}
