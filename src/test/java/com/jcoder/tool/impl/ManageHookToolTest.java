package com.jcoder.tool.impl;

import com.jcoder.hook.HookContext;
import com.jcoder.hook.HookEvent;
import com.jcoder.hook.config.HookConfigEntry;
import com.jcoder.hook.config.HookManager;
import com.jcoder.hook.config.HookStore;
import com.jcoder.hook.dispatcher.HookDispatcher;
import com.jcoder.hook.executor.HookExecutorRegistry;
import com.jcoder.tool.ToolCategory;
import com.jcoder.tool.ToolDefinition;
import com.jcoder.tool.ToolExecuteResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.ArrayList;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ManageHookToolTest {

    @TempDir
    Path root;

    @Test
    void exposesCommandCategorySchemaAndAllEightOperations() {
        try (Fixture fixture = fixture()) {
            ManageHookTool tool = fixture.tool;
            ToolDefinition definition = tool.definition();

            assertEquals("ManageHook", tool.name());
            assertEquals(ToolCategory.COMMAND, tool.category());
            assertEquals(List.of(
                    "list", "get", "create", "update", "delete",
                    "enable", "disable", "reload"),
                    definition.properties().get("operation").others().get("enum"));
            assertEquals(List.of("operation"), definition.required());
            assertEquals("string", definition.returns().type());
            assertTrue(tool.description().contains("COMMAND and HTTP"));
        }
    }

    @Test
    void executesAllOperationsAndCreateReloadsDispatcherImmediately() {
        try (Fixture fixture = fixture()) {
            assertSuccess(fixture.tool.execute(Map.<String, Object>of("operation", "list")), "hooks");

            ToolExecuteResult created = fixture.tool.execute(Map.<String, Object>of(
                    "operation", "create", "hook", hookMap("managed", true)));
            assertFalse(created.isError(), created.output());
            assertTrue(created.output().contains("hook created and reloaded"));
            assertEquals(List.of("managed"), fixture.manager.list().stream()
                    .map(HookConfigEntry::id).toList());
            assertEquals(1, fixture.dispatcher.dispatch(
                    HookContext.turnStart("", root, "hello")).matchedCount());

            ToolExecuteResult get = fixture.tool.execute(Map.<String, Object>of(
                    "operation", "get", "id", "managed"));
            assertSuccess(get, "managed");

            ToolExecuteResult updated = fixture.tool.execute(Map.<String, Object>of(
                    "operation", "update", "id", "managed",
                    "hook", hookMap("managed", false)));
            assertFalse(updated.isError(), updated.output());
            assertTrue(fixture.manager.get("managed").orElseThrow().enabled() == Boolean.FALSE);
            assertTrue(fixture.dispatcher.definitions().isEmpty());

            ToolExecuteResult enabled = fixture.tool.execute(Map.<String, Object>of(
                    "operation", "enable", "id", "managed"));
            assertFalse(enabled.isError(), enabled.output());
            assertEquals(1, fixture.dispatcher.definitions().size());

            ToolExecuteResult disabled = fixture.tool.execute(Map.<String, Object>of(
                    "operation", "disable", "id", "managed"));
            assertFalse(disabled.isError(), disabled.output());
            assertTrue(fixture.dispatcher.definitions().isEmpty());

            ToolExecuteResult deleted = fixture.tool.execute(Map.<String, Object>of(
                    "operation", "delete", "id", "managed"));
            assertFalse(deleted.isError(), deleted.output());
            assertTrue(fixture.manager.list().isEmpty());

            ToolExecuteResult reload = fixture.tool.execute(Map.<String, Object>of("operation", "reload"));
            assertFalse(reload.isError(), reload.output());
            assertTrue(reload.output().contains("hooks reloaded"));
        }
    }

    @Test
    void rejectsMissingArgumentsUnsupportedOperationsAndInvalidHookObjects() {
        try (Fixture fixture = fixture()) {
            List<Map<String, Object>> invalidArguments = new ArrayList<>();
            invalidArguments.add(Map.of());
            invalidArguments.add(Map.of("operation", "unknown"));
            invalidArguments.add(Map.of("operation", "get"));
            invalidArguments.add(Map.of("operation", "delete", "id", "missing"));
            invalidArguments.add(Map.of("operation", "create"));
            invalidArguments.add(Map.of("operation", "create", "hook", "not-object"));
            invalidArguments.add(Map.of("operation", "create", "hook", Map.of("id", "bad")));
            invalidArguments.add(Map.of("operation", "update", "id", "missing", "hook", hookMap("x", true)));
            for (Map<String, Object> args : invalidArguments) {
                ToolExecuteResult result = fixture.tool.execute(args);
                assertTrue(result.isError(), args.toString());
                assertTrue(result.output().startsWith("Error:"), result.output());
            }
        }
    }

    @Test
    void ioFailureIsReturnedAsToolError() {
        Path blocked = root.resolve("blocked");
        try (Fixture fixture = fixture(blocked)) {
            // Make the store's .mycoder path unusable before create.
            try {
                java.nio.file.Files.createDirectories(blocked.resolve(".mycoder"));
                java.nio.file.Files.writeString(blocked.resolve(".mycoder/hooks"), "blocked");
            } catch (java.io.IOException e) {
                throw new AssertionError(e);
            }
            ToolExecuteResult result = fixture.tool.execute(Map.<String, Object>of(
                    "operation", "create", "hook", hookMap("io-failure", true)));
            assertTrue(result.isError());
            assertTrue(result.output().contains("Error managing hook config"));
        }
    }

    private Fixture fixture() {
        return fixture(root);
    }

    private Fixture fixture(Path projectRoot) {
        HookDispatcher dispatcher = new HookDispatcher(
                HookExecutorRegistry.createDefault(), List.of());
        HookManager manager = new HookManager(new HookStore(projectRoot), dispatcher);
        return new Fixture(dispatcher, manager, new ManageHookTool(manager));
    }

    private static Map<String, Object> hookMap(String id, boolean enabled) {
        return Map.of(
                "id", id,
                "enabled", enabled,
                "event", "TURN_START",
                "action", Map.of(
                        "type", "PROMPT",
                        "template", "managed {{message}}"),
                "reject", false,
                "once", false,
                "async", false,
                "onError", "CONTINUE",
                "timeoutMillis", 1_000);
    }

    private static void assertSuccess(ToolExecuteResult result, String expected) {
        assertFalse(result.isError(), result.output());
        assertTrue(result.output().contains(expected), result.output());
    }

    private record Fixture(
            HookDispatcher dispatcher,
            HookManager manager,
            ManageHookTool tool
    ) implements AutoCloseable {
        @Override
        public void close() {
            dispatcher.close();
        }
    }
}
