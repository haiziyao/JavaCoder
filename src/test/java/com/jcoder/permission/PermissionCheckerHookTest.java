package com.jcoder.permission;

import com.jcoder.tool.Tool;
import com.jcoder.tool.ToolCategory;
import com.jcoder.tool.ToolDefinition;
import com.jcoder.tool.ToolExecuteResult;
import com.jcoder.tool.ToolParamDefinition;
import com.jcoder.tool.ToolReturnDefinition;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PermissionCheckerHookTest {

    @TempDir
    Path root;

    @Test
    void manageHookIsAskByDefaultEvenForListOperation() {
        PermissionChecker checker = new PermissionChecker(PermissionMode.DEFAULT, root);
        PermissionChecker.CheckResult result = checker.check(
                new TestTool("ManageHook", ToolCategory.COMMAND),
                Map.of("operation", "list"));

        assertEquals(PermissionMode.Decision.ASK, result.decision());
    }

    @Test
    void descriptionContainsOperationIdAndCompleteHookButIsBounded() {
        PermissionChecker checker = new PermissionChecker(PermissionMode.DEFAULT, root);
        Map<String, Object> args = Map.of(
                "operation", "create",
                "id", "meow",
                "hook", Map.of(
                        "event", "PRE_TOOL_USE",
                        "action", Map.of("type", "COMMAND", "command", "echo meow")
                ));

        String description = checker.describeToolAction("ManageHook", args);
        assertTrue(description.contains("ManageHook"));
        assertTrue(description.contains("create"));
        assertTrue(description.contains("meow"));
        assertTrue(description.contains("PRE_TOOL_USE"));
        assertTrue(description.contains("COMMAND"));
        assertTrue(description.contains("echo meow"));

        String longDescription = checker.describeToolAction(
                "ManageHook",
                Map.of("operation", "create", "hook", "x".repeat(1_000)));
        assertTrue(longDescription.length() < 600);
        assertTrue(longDescription.endsWith("...[truncated]"));
    }

    @Test
    void allowAlwaysDoesNotPersistForManageHook() {
        PermissionChecker checker = new PermissionChecker(PermissionMode.DEFAULT, root);
        TestTool manageHook = new TestTool("ManageHook", ToolCategory.COMMAND);
        Map<String, Object> args = Map.of("operation", "create");

        checker.addAllowAlwaysRule(manageHook, args);

        assertEquals(PermissionMode.Decision.ASK,
                checker.check(manageHook, args).decision());
    }

    @Test
    void ordinaryToolAllowAlwaysStillWorksForComparison() {
        PermissionChecker checker = new PermissionChecker(PermissionMode.DEFAULT, root);
        TestTool command = new TestTool("Bash", ToolCategory.COMMAND);
        Map<String, Object> args = Map.of("command", "echo hi");

        assertEquals(PermissionMode.Decision.ASK, checker.check(command, args).decision());
        checker.addAllowAlwaysRule(command, args);
        assertEquals(PermissionMode.Decision.ALLOW, checker.check(command, args).decision());
    }

    private static final class TestTool implements Tool {
        private final String name;
        private final ToolCategory category;

        private TestTool(String name, ToolCategory category) {
            this.name = name;
            this.category = category;
        }

        @Override public String name() { return name; }
        @Override public String description() { return name; }
        @Override public ToolCategory category() { return category; }
        @Override public ToolDefinition definition() {
            return new ToolDefinition(name, description(), Map.of(),
                    java.util.List.of(), new ToolReturnDefinition("string", Map.of()));
        }
        @Override public ToolExecuteResult execute(Map<String, Object> args) {
            return ToolExecuteResult.success("ok");
        }
    }
}
