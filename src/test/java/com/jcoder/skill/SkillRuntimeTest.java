package com.jcoder.skill;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SkillRuntimeTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void activatesCaseInsensitivelyAndReportsRepeatedUnknownAndBlankRequests()
            throws Exception {
        SkillRuntime runtime = runtime(skill("java-review", "Review Java.", "UNIQUE_SOP"));

        SkillRuntime.ActivationResult first = runtime.activate("  JAVA-Review ");
        SkillRuntime.ActivationResult repeated = runtime.activate("java-review");
        SkillRuntime.ActivationResult unknown = runtime.activate("missing");
        SkillRuntime.ActivationResult blank = runtime.activate("  ");
        SkillRuntime.ActivationResult nullName = runtime.activate(null);

        assertTrue(first.success());
        assertTrue(first.changed());
        assertEquals("java-review", first.skillName());
        assertTrue(repeated.success());
        assertFalse(repeated.changed());
        assertFalse(unknown.success());
        assertTrue(unknown.message().contains("unknown skill"));
        assertFalse(blank.success());
        assertFalse(nullName.success());
        assertEquals(List.of("java-review"), runtime.activeNames());
    }

    @Test
    void preservesActivationOrderAndEnforcesFourSkillLimit() throws Exception {
        SkillRuntime runtime = runtime(
                skill("alpha", "Alpha.", "A"),
                skill("beta", "Beta.", "B"),
                skill("gamma", "Gamma.", "C"),
                skill("delta", "Delta.", "D"),
                skill("epsilon", "Epsilon.", "E")
        );

        assertTrue(runtime.activate("gamma").success());
        assertTrue(runtime.activate("alpha").success());
        assertTrue(runtime.activate("delta").success());
        assertTrue(runtime.activate("beta").success());
        SkillRuntime.ActivationResult fifth = runtime.activate("epsilon");

        assertEquals(List.of("gamma", "alpha", "delta", "beta"), runtime.activeNames());
        assertFalse(fifth.success());
        assertTrue(fifth.message().contains("limit is 4"));
    }

    @Test
    void rejectsActivationThatWouldExceedTwentyThousandEstimatedTokens()
            throws Exception {
        SkillRuntime runtime = runtime(skill(
                "oversized",
                "An oversized SOP.",
                "x".repeat(80_001)
        ));

        SkillRuntime.ActivationResult result = runtime.activate("oversized");

        assertFalse(result.success());
        assertTrue(result.message().contains("20001 tokens"));
        assertTrue(runtime.activeNames().isEmpty());
    }

    @Test
    void unloadAndClearOnlyChangeActiveRuntimeState() throws Exception {
        SkillRuntime runtime = runtime(
                skill("alpha", "Alpha.", "A"),
                skill("beta", "Beta.", "B")
        );
        runtime.activate("alpha");
        runtime.activate("beta");

        assertTrue(runtime.unload(" ALPHA "));
        assertFalse(runtime.unload("alpha"));
        assertFalse(runtime.unload(null));
        assertEquals(List.of("beta"), runtime.activeNames());
        assertEquals(1, runtime.clear());
        assertEquals(0, runtime.clear());
        assertTrue(runtime.activeNames().isEmpty());
        assertEquals(List.of("alpha", "beta"),
                runtime.availableSkills().stream().map(SkillDefinition::name).toList());
    }

    @Test
    void reloadRemovesDeletedActiveSkill() throws Exception {
        Path skillDirectory = skill("alpha", "Alpha.", "ALPHA_SOP");
        SkillCatalog catalog = catalog();
        catalog.reload();
        SkillRuntime runtime = new SkillRuntime(catalog);
        runtime.activate("alpha");

        Files.delete(skillDirectory.resolve("SKILL.md"));
        Files.delete(skillDirectory);
        SkillCatalog.ReloadReport report = runtime.reloadCatalog();

        assertTrue(report.successful());
        assertTrue(runtime.activeNames().isEmpty());
        assertTrue(runtime.activeSkills().isEmpty());
    }

    @Test
    void rendersMetadataForAvailableSkillsAndFullBodyOnlyForActiveSkills()
            throws Exception {
        SkillRuntime runtime = runtime(
                skill("alpha", "Alpha description.", "UNIQUE_ALPHA_SOP"),
                skill("beta", "Beta description.", "UNIQUE_BETA_SOP")
        );

        String available = runtime.renderAvailableSkills();
        assertTrue(available.contains("alpha: Alpha description."));
        assertTrue(available.contains("beta: Beta description."));
        assertFalse(available.contains("UNIQUE_ALPHA_SOP"));
        assertFalse(available.contains("UNIQUE_BETA_SOP"));
        assertEquals("", runtime.renderActiveSkills());

        runtime.activate("beta");
        String active = runtime.renderActiveSkills();
        assertTrue(active.contains("## Skill: beta"));
        assertEquals(1, occurrences(active, "UNIQUE_BETA_SOP"));
        assertFalse(active.contains("UNIQUE_ALPHA_SOP"));
    }

    private SkillRuntime runtime(Path... ignored) {
        SkillCatalog catalog = catalog();
        catalog.reload();
        return new SkillRuntime(catalog);
    }

    private SkillCatalog catalog() {
        return new SkillCatalog(
                temporaryDirectory.resolve("user-skills"),
                temporaryDirectory.resolve("project-skills")
        );
    }

    private Path skill(String name, String description, String body) throws IOException {
        Path directory = temporaryDirectory.resolve("project-skills").resolve(name);
        Files.createDirectories(directory);
        Files.writeString(directory.resolve("SKILL.md"), "---\n"
                        + "name: " + name + "\n"
                        + "description: " + description + "\n"
                        + "---\n\n" + body + "\n",
                StandardCharsets.UTF_8);
        return directory;
    }

    private static int occurrences(String text, String expected) {
        return (text.length() - text.replace(expected, "").length()) / expected.length();
    }
}
