package com.jcoder.hook;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HookSelectorTest {

    private static final Path WORKING_DIRECTORY = Path.of(".");

    @Test
    void anySelectorMatchesEveryContextWithoutUsingToolData() {
        HookSelector selector = HookSelector.any();

        assertFalse(selector.usesToolData());
        assertTrue(selector.matches(HookContext.turnStart(
                "session-1", WORKING_DIRECTORY, "hello")));
        assertTrue(selector.matches(HookContext.preTool(
                "session-1", WORKING_DIRECTORY, "WriteFile", Map.of())));
    }

    @Test
    void matchesToolNameArgumentsAndErrorStateExactly() {
        HookSelector selector = new HookSelector(
                "  WriteFile  ",
                Map.of("count", "2", "path", "application.json"),
                true
        );
        HookContext matching = HookContext.postTool(
                "session-1",
                WORKING_DIRECTORY,
                "WriteFile",
                Map.of("count", 2, "path", "application.json"),
                "failed",
                true,
                5
        );

        assertTrue(selector.usesToolData());
        assertTrue(selector.matches(matching));
        assertFalse(selector.matches(HookContext.postTool(
                "session-1", WORKING_DIRECTORY, "ReadFile",
                matching.toolArguments(), "failed", true, 5)));
        assertFalse(selector.matches(HookContext.postTool(
                "session-1", WORKING_DIRECTORY, "WriteFile",
                Map.of("count", 3, "path", "application.json"), "failed", true, 5)));
        assertFalse(selector.matches(HookContext.postTool(
                "session-1", WORKING_DIRECTORY, "WriteFile",
                matching.toolArguments(), "ok", false, 5)));
    }

    @Test
    void normalizesAndDefensivelyCopiesArguments() {
        Map<String, String> source = new LinkedHashMap<>();
        source.put("  path  ", "application.json");

        HookSelector selector = new HookSelector("  ", source, null);
        source.put("late", "change");

        assertEquals(null, selector.toolName());
        assertEquals(Map.of("path", "application.json"), selector.argumentEquals());
        assertThrows(UnsupportedOperationException.class,
                () -> selector.argumentEquals().put("new", "value"));
    }

    @Test
    void rejectsInvalidArgumentConditions() {
        Map<String, String> nullName = new HashMap<>();
        nullName.put(null, "value");
        Map<String, String> nullValue = new HashMap<>();
        nullValue.put("path", null);

        assertThrows(IllegalArgumentException.class,
                () -> new HookSelector(null, nullName, null));
        assertThrows(IllegalArgumentException.class,
                () -> new HookSelector(null, Map.of("  ", "value"), null));
        assertThrows(IllegalArgumentException.class,
                () -> new HookSelector(null, nullValue, null));
    }

    @Test
    void rejectsNullContext() {
        assertThrows(NullPointerException.class,
                () -> HookSelector.any().matches(null));
    }
}
