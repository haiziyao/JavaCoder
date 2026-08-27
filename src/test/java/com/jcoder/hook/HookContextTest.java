package com.jcoder.hook;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HookContextTest {

    @Test
    void turnFactoriesCreateLifecycleSnapshots() {
        Path cwd = Path.of(".");
        HookContext start = HookContext.turnStart(" session-1 ", cwd, "question");
        HookContext end = HookContext.turnEnd("session-1", cwd, "answer");

        assertEquals(HookEvent.TURN_START, start.event());
        assertEquals("session-1", start.sessionId());
        assertEquals("question", start.message());
        assertNull(start.toolName());
        assertEquals(Map.of(), start.toolArguments());
        assertEquals(cwd.toAbsolutePath().normalize(), start.workingDirectory());
        assertNotNull(start.occurredAt());

        assertEquals(HookEvent.TURN_END, end.event());
        assertEquals("answer", end.message());
        assertNull(end.toolName());
    }

    @Test
    void preToolFactoryCreatesPreExecutionSnapshotAndCopiesArguments() {
        Map<String, Object> arguments = new LinkedHashMap<>();
        arguments.put("path", "application.json");

        HookContext context = HookContext.preTool(
                "session-1", Path.of("."), " WriteFile ", arguments);
        arguments.put("late", "change");

        assertEquals(HookEvent.PRE_TOOL_USE, context.event());
        assertEquals("WriteFile", context.toolName());
        assertEquals(Map.of("path", "application.json"), context.toolArguments());
        assertEquals("", context.toolOutput());
        assertFalse(context.toolError());
        assertEquals(0, context.toolDurationMillis());
        assertThrows(UnsupportedOperationException.class,
                () -> context.toolArguments().put("new", "value"));
    }

    @Test
    void postToolFactoryPreservesCompletedResult() {
        HookContext context = HookContext.postTool(
                "session-1",
                Path.of("."),
                "WriteFile",
                Map.of("path", "application.json"),
                "permission denied",
                true,
                27
        );

        assertEquals(HookEvent.POST_TOOL_USE, context.event());
        assertEquals("permission denied", context.toolOutput());
        assertTrue(context.toolError());
        assertEquals(27, context.toolDurationMillis());
    }

    @Test
    void constructorNormalizesNullableFieldsAndRetainsTimestamp() {
        Instant timestamp = Instant.parse("2026-08-28T00:00:00Z");
        HookContext context = new HookContext(
                HookEvent.TURN_START,
                null,
                Path.of("."),
                null,
                null,
                null,
                null,
                false,
                0,
                timestamp
        );

        assertEquals("", context.sessionId());
        assertEquals("", context.message());
        assertEquals(Map.of(), context.toolArguments());
        assertEquals("", context.toolOutput());
        assertSame(timestamp, context.occurredAt());

        HookContext generatedTime = new HookContext(
                HookEvent.TURN_END, "", Path.of("."), "", null,
                Map.of(), "", false, 0, null);
        assertNotNull(generatedTime.occurredAt());
    }

    @Test
    void requiresEventWorkingDirectoryAndToolNameForToolEvents() {
        assertThrows(NullPointerException.class,
                () -> new HookContext(null, "", Path.of("."), "", null,
                        Map.of(), "", false, 0, Instant.now()));
        assertThrows(NullPointerException.class,
                () -> HookContext.turnStart("", null, "hello"));
        assertThrows(IllegalArgumentException.class,
                () -> HookContext.preTool("", Path.of("."), null, Map.of()));
        assertThrows(IllegalArgumentException.class,
                () -> HookContext.preTool("", Path.of("."), "  ", Map.of()));
    }

    @Test
    void lifecycleContextCannotContainToolData() {
        assertThrows(IllegalArgumentException.class,
                () -> lifecycleContext("WriteFile", Map.of(), "", false, 0));
        assertThrows(IllegalArgumentException.class,
                () -> lifecycleContext(null, Map.of("path", "file"), "", false, 0));
        assertThrows(IllegalArgumentException.class,
                () -> lifecycleContext(null, Map.of(), "output", false, 0));
        assertThrows(IllegalArgumentException.class,
                () -> lifecycleContext(null, Map.of(), "", true, 0));
        assertThrows(IllegalArgumentException.class,
                () -> lifecycleContext(null, Map.of(), "", false, 1));
    }

    @Test
    void preToolContextCannotContainCompletedResult() {
        assertThrows(IllegalArgumentException.class,
                () -> preToolContext("output", false, 0));
        assertThrows(IllegalArgumentException.class,
                () -> preToolContext("", true, 0));
        assertThrows(IllegalArgumentException.class,
                () -> preToolContext("", false, 1));
    }

    @Test
    void rejectsNegativeToolDuration() {
        assertThrows(IllegalArgumentException.class,
                () -> HookContext.postTool(
                        "", Path.of("."), "ReadFile", Map.of(), "", false, -1));
    }

    private static HookContext lifecycleContext(
            String toolName,
            Map<String, Object> arguments,
            String output,
            boolean error,
            long duration
    ) {
        return new HookContext(
                HookEvent.TURN_START, "", Path.of("."), "message",
                toolName, arguments, output, error, duration, Instant.now());
    }

    private static HookContext preToolContext(
            String output,
            boolean error,
            long duration
    ) {
        return new HookContext(
                HookEvent.PRE_TOOL_USE, "", Path.of("."), "",
                "ReadFile", Map.of(), output, error, duration, Instant.now());
    }
}
