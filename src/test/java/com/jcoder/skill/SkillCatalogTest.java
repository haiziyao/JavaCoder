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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SkillCatalogTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void loadsValidSkillAndExposesItsMetadataBodyAndSource() throws Exception {
        Path userRoot = temporaryDirectory.resolve("user-skills");
        Path projectRoot = temporaryDirectory.resolve("project-skills");
        Path skillDirectory = writeSkill(
                projectRoot,
                "java-review",
                "Review Java source code.",
                "# Java Review\n\n1. Read the relevant files."
        );

        SkillCatalog catalog = new SkillCatalog(userRoot, projectRoot);
        SkillCatalog.ReloadReport report = catalog.reload();

        assertTrue(report.successful());
        assertEquals(1, report.loadedDefinitions());
        assertEquals(1, report.availableSkills());
        assertEquals(1, catalog.size());

        SkillDefinition skill = catalog.find("java-review").orElseThrow();
        assertEquals("java-review", skill.name());
        assertEquals("Review Java source code.", skill.metadata().description());
        assertEquals("# Java Review\n\n1. Read the relevant files.", skill.body());
        assertEquals(skillDirectory.toAbsolutePath().normalize(), skill.sourceDirectory());
        assertEquals(SkillDefinition.Source.PROJECT, skill.source());
    }

    @Test
    void reportsMissingOpeningFrontmatterWithoutBlockingOtherSkills() throws Exception {
        Path userRoot = temporaryDirectory.resolve("user-skills");
        Path projectRoot = temporaryDirectory.resolve("project-skills");
        writeRawSkill(projectRoot, "broken", "# No frontmatter");
        writeSkill(projectRoot, "healthy", "A healthy skill.", "# Healthy");

        SkillCatalog catalog = new SkillCatalog(userRoot, projectRoot);
        SkillCatalog.ReloadReport report = catalog.reload();

        assertFalse(report.successful());
        assertEquals(1, report.loadedDefinitions());
        assertEquals(1, report.availableSkills());
        assertTrue(catalog.find("healthy").isPresent());
        assertTrue(catalog.find("broken").isEmpty());
        assertEquals(1, report.errors().size());
        assertTrue(report.errors().getFirst().contains("must start with YAML frontmatter"));
        assertEquals(report.errors(), catalog.loadErrors());
    }

    @Test
    void reportsMissingClosingFrontmatterDelimiter() throws Exception {
        Path projectRoot = temporaryDirectory.resolve("project-skills");
        writeRawSkill(projectRoot, "broken", """
                ---
                name: broken
                description: Missing closing delimiter.
                # Body is never reached
                """);

        SkillCatalog.ReloadReport report = catalog(projectRoot).reload();

        assertEquals(0, report.availableSkills());
        assertTrue(report.errors().getFirst().contains("closing delimiter is missing"));
    }

    @Test
    void reportsMalformedYamlFrontmatter() throws Exception {
        Path projectRoot = temporaryDirectory.resolve("project-skills");
        writeRawSkill(projectRoot, "broken", """
                ---
                name: [unterminated
                description: Broken YAML.
                ---
                # Body
                """);

        SkillCatalog.ReloadReport report = catalog(projectRoot).reload();

        assertEquals(0, report.availableSkills());
        assertTrue(report.errors().getFirst().contains("invalid YAML frontmatter"));
    }

    @Test
    void reportsMissingName() throws Exception {
        Path projectRoot = temporaryDirectory.resolve("project-skills");
        writeRawSkill(projectRoot, "broken", """
                ---
                description: Missing name.
                ---
                # Body
                """);

        SkillCatalog.ReloadReport report = catalog(projectRoot).reload();

        assertTrue(report.errors().getFirst().contains("field \"name\" is required"));
        assertEquals(0, catalog(projectRoot).size());
    }

    @Test
    void reportsMissingDescription() throws Exception {
        Path projectRoot = temporaryDirectory.resolve("project-skills");
        writeRawSkill(projectRoot, "broken", """
                ---
                name: broken
                ---
                # Body
                """);

        SkillCatalog.ReloadReport report = catalog(projectRoot).reload();

        assertTrue(report.errors().getFirst().contains("field \"description\" is required"));
        assertEquals(0, report.availableSkills());
    }

    @Test
    void reportsEmptyBody() throws Exception {
        Path projectRoot = temporaryDirectory.resolve("project-skills");
        writeRawSkill(projectRoot, "broken", """
                ---
                name: broken
                description: Has no SOP.
                ---

                """);

        SkillCatalog.ReloadReport report = catalog(projectRoot).reload();

        assertTrue(report.errors().getFirst().contains("skill SOP body is empty"));
        assertEquals(0, report.availableSkills());
    }

    @Test
    void reportsDirectoryNameMismatch() throws Exception {
        Path projectRoot = temporaryDirectory.resolve("project-skills");
        Path directory = projectRoot.resolve("directory-name");
        Files.createDirectories(directory);
        Files.writeString(directory.resolve("SKILL.md"), skillFile(
                "declared-name",
                "Names do not match.",
                "# Body"
        ));

        SkillCatalog.ReloadReport report = catalog(projectRoot).reload();

        assertTrue(report.errors().getFirst().contains("does not match skill name"));
        assertEquals(0, report.availableSkills());
    }

    @Test
    void reportsDirectoryWithoutSkillManifest() throws Exception {
        Path projectRoot = temporaryDirectory.resolve("project-skills");
        Files.createDirectories(projectRoot.resolve("missing-manifest"));

        SkillCatalog.ReloadReport report = catalog(projectRoot).reload();

        assertTrue(report.errors().getFirst().contains("SKILL.md is missing"));
        assertEquals(0, report.availableSkills());
    }

    @Test
    void rejectsSkillFileAboveConfiguredSizeLimit() throws Exception {
        Path projectRoot = temporaryDirectory.resolve("project-skills");
        Path manifest = projectRoot.resolve("oversized/SKILL.md");
        Files.createDirectories(manifest.getParent());
        Files.writeString(
                manifest,
                "x".repeat(256 * 1024 + 1),
                StandardCharsets.UTF_8
        );

        SkillCatalog.ReloadReport report = catalog(projectRoot).reload();

        assertTrue(report.errors().getFirst().contains("SKILL.md is too large"));
        assertEquals(0, report.availableSkills());
    }

    @Test
    void projectSkillOverridesUserSkillWithSameName() throws Exception {
        Path userRoot = temporaryDirectory.resolve("user-skills");
        Path projectRoot = temporaryDirectory.resolve("project-skills");
        writeSkill(userRoot, "shared", "User description.", "# User workflow");
        writeSkill(projectRoot, "shared", "Project description.", "# Project workflow");

        SkillCatalog catalog = new SkillCatalog(userRoot, projectRoot);
        SkillCatalog.ReloadReport report = catalog.reload();

        assertEquals(2, report.loadedDefinitions());
        assertEquals(1, report.availableSkills());
        SkillDefinition selected = catalog.find("shared").orElseThrow();
        assertEquals("Project description.", selected.metadata().description());
        assertEquals("# Project workflow", selected.body());
        assertEquals(SkillDefinition.Source.PROJECT, selected.source());
    }

    @Test
    void listsSkillsInDeterministicNameOrderAcrossTiers() throws Exception {
        Path userRoot = temporaryDirectory.resolve("user-skills");
        Path projectRoot = temporaryDirectory.resolve("project-skills");
        writeSkill(userRoot, "zulu", "Zulu skill.", "# Zulu");
        writeSkill(projectRoot, "alpha", "Alpha skill.", "# Alpha");
        writeSkill(userRoot, "middle", "Middle skill.", "# Middle");

        SkillCatalog catalog = new SkillCatalog(userRoot, projectRoot);
        catalog.reload();

        assertEquals(
                List.of("alpha", "middle", "zulu"),
                catalog.list().stream().map(SkillDefinition::name).toList()
        );
    }

    @Test
    void findNormalizesCaseAndOuterWhitespace() throws Exception {
        Path projectRoot = temporaryDirectory.resolve("project-skills");
        writeSkill(projectRoot, "java-review", "Review Java.", "# Review");
        SkillCatalog catalog = catalog(projectRoot);
        catalog.reload();

        assertTrue(catalog.find("  JAVA-Review \n").isPresent());
        assertTrue(catalog.find(null).isEmpty());
        assertTrue(catalog.find("   ").isEmpty());
        assertTrue(catalog.find("unknown").isEmpty());
    }

    @Test
    void reloadReflectsDeletedAndNewSkills() throws Exception {
        Path projectRoot = temporaryDirectory.resolve("project-skills");
        Path alpha = writeSkill(projectRoot, "alpha", "Alpha skill.", "# Alpha");
        SkillCatalog catalog = catalog(projectRoot);
        catalog.reload();
        assertTrue(catalog.find("alpha").isPresent());

        Files.delete(alpha.resolve("SKILL.md"));
        Files.delete(alpha);
        writeSkill(projectRoot, "beta", "Beta skill.", "# Beta");
        SkillCatalog.ReloadReport report = catalog.reload();

        assertTrue(report.successful());
        assertEquals(1, report.loadedDefinitions());
        assertEquals(1, report.availableSkills());
        assertTrue(catalog.find("alpha").isEmpty());
        assertTrue(catalog.find("beta").isPresent());
    }

    @Test
    void returnedCollectionsAreImmutableSnapshots() throws Exception {
        Path projectRoot = temporaryDirectory.resolve("project-skills");
        writeSkill(projectRoot, "alpha", "Alpha skill.", "# Alpha");
        Path broken = projectRoot.resolve("broken");
        Files.createDirectories(broken);

        SkillCatalog catalog = catalog(projectRoot);
        SkillCatalog.ReloadReport report = catalog.reload();

        assertThrows(UnsupportedOperationException.class,
                () -> catalog.list().add(catalog.list().getFirst()));
        assertThrows(UnsupportedOperationException.class,
                () -> catalog.loadErrors().add("new error"));
        assertThrows(UnsupportedOperationException.class,
                () -> report.errors().clear());
    }

    @Test
    void safeYamlConstructorRejectsArbitraryJavaObjectTags() throws Exception {
        Exploit.constructed = false;
        Path projectRoot = temporaryDirectory.resolve("project-skills");
        writeRawSkill(projectRoot, "unsafe", """
                ---
                name: unsafe
                description: Must not instantiate arbitrary objects.
                payload: !!com.jcoder.skill.SkillCatalogTest$Exploit {}
                ---
                # Unsafe body
                """);

        SkillCatalog.ReloadReport report = catalog(projectRoot).reload();

        assertFalse(Exploit.constructed);
        assertEquals(0, report.availableSkills());
        assertTrue(report.errors().getFirst().contains("invalid YAML frontmatter"));
    }

    @Test
    void absentSkillRootsAreAValidEmptyCatalog() {
        SkillCatalog catalog = new SkillCatalog(
                temporaryDirectory.resolve("missing-user"),
                temporaryDirectory.resolve("missing-project")
        );

        SkillCatalog.ReloadReport report = catalog.reload();

        assertTrue(report.successful());
        assertEquals(0, report.loadedDefinitions());
        assertEquals(0, report.availableSkills());
        assertTrue(catalog.list().isEmpty());
    }

    @Test
    void reloadReportDefensivelyCopiesErrorsAndValidatesCounts() {
        List<String> mutableErrors = new java.util.ArrayList<>(List.of("broken"));
        SkillCatalog.ReloadReport report = new SkillCatalog.ReloadReport(1, 0, mutableErrors);
        mutableErrors.add("later");

        assertEquals(List.of("broken"), report.errors());
        assertFalse(report.successful());
        assertTrue(new SkillCatalog.ReloadReport(0, 0, null).successful());
        assertThrows(IllegalArgumentException.class,
                () -> new SkillCatalog.ReloadReport(-1, 0, List.of()));
        assertThrows(IllegalArgumentException.class,
                () -> new SkillCatalog.ReloadReport(0, -1, List.of()));
    }

    private SkillCatalog catalog(Path projectRoot) {
        return new SkillCatalog(
                temporaryDirectory.resolve("user-skills"),
                projectRoot
        );
    }

    private static Path writeSkill(
            Path root,
            String name,
            String description,
            String body
    ) throws IOException {
        return writeRawSkill(root, name, skillFile(name, description, body));
    }

    private static Path writeRawSkill(
            Path root,
            String directoryName,
            String content
    ) throws IOException {
        Path directory = root.resolve(directoryName);
        Files.createDirectories(directory);
        Files.writeString(
                directory.resolve("SKILL.md"),
                content,
                StandardCharsets.UTF_8
        );
        return directory;
    }

    private static String skillFile(
            String name,
            String description,
            String body
    ) {
        return "---\n"
                + "name: " + name + "\n"
                + "description: " + description + "\n"
                + "---\n\n"
                + body
                + "\n";
    }

    public static final class Exploit {
        static boolean constructed;

        public Exploit() {
            constructed = true;
        }
    }
}
