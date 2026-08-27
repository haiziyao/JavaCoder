package com.jcoder.hook.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jcoder.hook.HookActionType;
import com.jcoder.hook.HookErrorPolicy;
import com.jcoder.hook.HookEvent;
import com.jcoder.hook.action.CommandHookAction;
import com.jcoder.hook.action.HttpHookAction;
import com.jcoder.hook.action.PromptHookAction;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HookConfigAndStoreTest {

    @TempDir
    Path root;

    @Test
    void allActionTypesRoundTripThroughJsonAndRuntimeDefinitions() throws Exception {
        List<HookConfigEntry> entries = List.of(
                entry("prompt", true, HookEvent.TURN_START,
                        action(HookActionType.PROMPT, "hello {{message}}", null, null),
                        HookConfigEntry.SelectorConfig.any()),
                entry("command", false, HookEvent.PRE_TOOL_USE,
                        action(HookActionType.COMMAND, null, "echo meow", null),
                        new HookConfigEntry.SelectorConfig(
                                "WriteFile", Map.of("path", "a.txt"), null)),
                entry("http", true, HookEvent.POST_TOOL_USE,
                        new HookConfigEntry.ActionConfig(
                                HookActionType.HTTP, null, null,
                                "http://127.0.0.1/hook", "PATCH",
                                Map.of("X-Test", "yes"), "{{tool_output}}"),
                        new HookConfigEntry.SelectorConfig(null, Map.of(), true))
        );
        ObjectMapper mapper = new ObjectMapper();

        String json = mapper.writeValueAsString(
                new HookConfigFile(HookConfigFile.CURRENT_VERSION, entries));
        HookConfigFile decoded = mapper.readValue(json, HookConfigFile.class);

        assertEquals(entries, decoded.hooks());
        assertInstanceOf(PromptHookAction.class, decoded.hooks().get(0).toDefinition().action());
        assertInstanceOf(CommandHookAction.class, decoded.hooks().get(1).toDefinition().action());
        assertInstanceOf(HttpHookAction.class, decoded.hooks().get(2).toDefinition().action());
    }

    @Test
    void entryAndFileApplyDefaultsAndCopies() {
        HookConfigEntry entry = new HookConfigEntry(
                "  defaulted  ", null, HookEvent.TURN_START, null,
                action(HookActionType.PROMPT, "hello", null, null),
                false, false, false, null, 0);
        List<HookConfigEntry> source = new ArrayList<>(List.of(entry));
        HookConfigFile file = new HookConfigFile(1, source);
        source.clear();

        assertEquals("defaulted", entry.id());
        assertTrue(entry.isEnabled());
        assertEquals(HookConfigEntry.SelectorConfig.any(), entry.selector());
        assertEquals(HookErrorPolicy.CONTINUE, entry.onError());
        assertEquals(10_000, entry.toDefinition().timeoutMillis());
        assertEquals(1, file.hooks().size());
        assertThrows(UnsupportedOperationException.class, () -> file.hooks().clear());
        assertEquals(List.of(), new HookConfigFile(1, null).hooks());
        assertEquals(List.of(), HookConfigFile.empty().hooks());
    }

    @Test
    void invalidActionFieldsAndDefinitionCombinationsAreRejected() {
        assertThrows(NullPointerException.class,
                () -> action(null, null, null, null));
        assertThrows(IllegalArgumentException.class,
                () -> action(HookActionType.PROMPT, "x", "unexpected", null).toAction());
        assertThrows(IllegalArgumentException.class,
                () -> action(HookActionType.COMMAND, "unexpected", "echo x", null).toAction());
        assertThrows(IllegalArgumentException.class,
                () -> action(HookActionType.HTTP, null, "unexpected", "http://localhost").toAction());
        assertThrows(IllegalArgumentException.class,
                () -> action(HookActionType.HTTP, null, null, " ").toAction());

        HookConfigEntry illegalAsyncReject = new HookConfigEntry(
                "illegal", true, HookEvent.PRE_TOOL_USE,
                HookConfigEntry.SelectorConfig.any(),
                action(HookActionType.PROMPT, "x", null, null),
                true, false, true, HookErrorPolicy.CONTINUE, 1_000);
        assertThrows(IllegalArgumentException.class, illegalAsyncReject::toDefinition);
        assertThrows(IllegalArgumentException.class,
                () -> new HookConfigFile(2, List.of()));
    }

    @Test
    void selectorAndHeadersAreImmutableDefensiveCopies() {
        Map<String, String> arguments = new java.util.LinkedHashMap<>();
        arguments.put("path", "a.txt");
        HookConfigEntry.SelectorConfig selector =
                new HookConfigEntry.SelectorConfig(" Tool ", arguments, null);
        arguments.put("late", "change");

        assertEquals("Tool", selector.toolName());
        assertEquals(Map.of("path", "a.txt"), selector.argumentEquals());
        assertThrows(UnsupportedOperationException.class,
                () -> selector.argumentEquals().put("x", "y"));
    }

    @Test
    void missingStoreFileLoadsEmptyAndSaveLoadIsAtomicRoundTrip() throws Exception {
        HookStore store = new HookStore(root);
        assertTrue(store.load().isEmpty());

        List<HookConfigEntry> entries = List.of(entry(
                "saved", true, HookEvent.TURN_START,
                action(HookActionType.PROMPT, "saved", null, null),
                HookConfigEntry.SelectorConfig.any()));
        store.save(entries);

        assertEquals(entries, store.load());
        assertTrue(Files.isRegularFile(store.configFile()));
        try (var files = Files.list(store.configFile().getParent())) {
            assertEquals(List.of("hooks.json"), files.map(path -> path.getFileName().toString()).toList());
        }
    }

    @Test
    void storeRejectsCorruptionVersionOversizeAndNonFile() throws Exception {
        HookStore corrupt = new HookStore(root.resolve("corrupt"));
        Files.createDirectories(corrupt.configFile().getParent());
        Files.writeString(corrupt.configFile(), "{not-json", StandardCharsets.UTF_8);
        assertThrows(Exception.class, corrupt::load);

        HookStore version = new HookStore(root.resolve("version"));
        Files.createDirectories(version.configFile().getParent());
        Files.writeString(version.configFile(), "{\"version\":2,\"hooks\":[]}");
        assertThrows(Exception.class, version::load);

        HookStore oversized = new HookStore(root.resolve("oversized"));
        Files.createDirectories(oversized.configFile().getParent());
        Files.writeString(oversized.configFile(), "x".repeat(1_000_001));
        java.io.IOException sizeError = assertThrows(java.io.IOException.class, oversized::load);
        assertTrue(sizeError.getMessage().contains("too large"));

        HookStore directory = new HookStore(root.resolve("directory"));
        Files.createDirectories(directory.configFile());
        java.io.IOException fileError = assertThrows(java.io.IOException.class, directory::load);
        assertTrue(fileError.getMessage().contains("not a regular file"));
    }

    private static HookConfigEntry entry(
            String id, boolean enabled, HookEvent event,
            HookConfigEntry.ActionConfig action,
            HookConfigEntry.SelectorConfig selector
    ) {
        return new HookConfigEntry(
                id, enabled, event, selector, action,
                false, false, false, HookErrorPolicy.CONTINUE, 1_000);
    }

    private static HookConfigEntry.ActionConfig action(
            HookActionType type, String template, String command, String uri
    ) {
        return new HookConfigEntry.ActionConfig(
                type, template, command, uri, null, Map.of(), "");
    }
}
