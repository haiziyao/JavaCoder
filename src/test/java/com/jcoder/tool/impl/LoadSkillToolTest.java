package com.jcoder.tool.impl;

import com.jcoder.skill.SkillCatalog;
import com.jcoder.skill.SkillRuntime;
import com.jcoder.tool.ToolCategory;
import com.jcoder.tool.ToolDefinition;
import com.jcoder.tool.ToolExecuteResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LoadSkillToolTest {

    private static final String SECRET_SOP = "FULL_PRIVATE_SOP_BODY";

    @TempDir
    Path temporaryDirectory;

    private SkillRuntime runtime;
    private LoadSkillTool tool;

    @BeforeEach
    void setUp() throws Exception {
        Path projectRoot = temporaryDirectory.resolve("project-skills");
        Path directory = projectRoot.resolve("java-review");
        Files.createDirectories(directory);
        Files.writeString(directory.resolve("SKILL.md"), """
                ---
                name: java-review
                description: Review Java source.
                ---

                %s
                """.formatted(SECRET_SOP), StandardCharsets.UTF_8);
        SkillCatalog catalog = new SkillCatalog(
                temporaryDirectory.resolve("user-skills"), projectRoot);
        catalog.reload();
        runtime = new SkillRuntime(catalog);
        tool = new LoadSkillTool(runtime);
    }

    @Test
    void exposesExpectedToolSchema() {
        ToolDefinition definition = tool.definition();

        assertEquals("LoadSkill", tool.name());
        assertEquals(ToolCategory.READ, tool.category());
        assertEquals("LoadSkill", definition.name());
        assertEquals("string", definition.properties().get("name").type());
        assertEquals(java.util.List.of("name"), definition.required());
        assertEquals("string", definition.returns().type());
        assertTrue(definition.description().contains("next model request"));
    }

    @Test
    void rejectsMissingBlankAndUnknownNames() {
        ToolExecuteResult missing = tool.execute(Map.of());
        ToolExecuteResult blank = tool.execute(Map.of("name", "  "));
        ToolExecuteResult unknown = tool.execute(Map.of("name", "missing"));

        assertTrue(missing.isError());
        assertTrue(blank.isError());
        assertTrue(unknown.isError());
        assertTrue(missing.output().contains("name is required"));
        assertTrue(unknown.output().contains("unknown skill"));
    }

    @Test
    void firstAndRepeatedLoadsSucceedWithoutReturningFullSop() {
        ToolExecuteResult first = tool.execute(Map.of("name", "java-review"));
        ToolExecuteResult repeated = tool.execute(Map.of("name", "JAVA-REVIEW"));

        assertFalse(first.isError());
        assertFalse(repeated.isError());
        assertTrue(first.output().contains("next model request"));
        assertTrue(repeated.output().contains("already active"));
        assertFalse(first.output().contains(SECRET_SOP));
        assertFalse(repeated.output().contains(SECRET_SOP));
        assertEquals(java.util.List.of("java-review"), runtime.activeNames());
    }
}
