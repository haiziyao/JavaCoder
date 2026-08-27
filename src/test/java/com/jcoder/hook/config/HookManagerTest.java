package com.jcoder.hook.config;

import com.jcoder.hook.HookContext;
import com.jcoder.hook.HookErrorPolicy;
import com.jcoder.hook.HookEvent;
import com.jcoder.hook.action.PromptHookAction;
import com.jcoder.hook.dispatcher.HookDispatchReport;
import com.jcoder.hook.dispatcher.HookDispatcher;
import com.jcoder.hook.executor.HookExecutorRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HookManagerTest {

    @TempDir
    Path root;

    @Test
    void reloadLoadsEnabledOnlyInOrderAndDisabledRemainGoverned() throws Exception {
        HookStore store = new HookStore(root);
        HookConfigEntry enabled = entry("enabled", true);
        HookConfigEntry disabled = entry("disabled", false);
        store.save(List.of(enabled, disabled));

        try (HookDispatcher dispatcher = defaultDispatcher()) {
            HookManager manager = new HookManager(store, dispatcher);
            assertEquals(2, manager.reload());
            assertEquals(List.of(enabled, disabled), manager.list());
            assertEquals(List.of("enabled"), dispatcher.definitions().stream()
                    .map(com.jcoder.hook.HookDefinition::id).toList());

            HookConfigEntry invalidDisabled = new HookConfigEntry(
                    "invalid-disabled", false, HookEvent.TURN_START,
                    HookConfigEntry.SelectorConfig.any(),
                    new HookConfigEntry.ActionConfig(
                            com.jcoder.hook.HookActionType.PROMPT,
                            null, "not-valid", null, null, Map.of(), ""),
                    false, false, false, HookErrorPolicy.CONTINUE, 1_000);
            store.save(List.of(enabled, invalidDisabled));
            assertThrows(IllegalArgumentException.class, manager::reload);
            assertEquals(List.of("enabled"), dispatcher.definitions().stream()
                    .map(definition -> definition.id()).toList());
            assertEquals(List.of(enabled, disabled), manager.list());
        }
    }

    @Test
    void createUpdateDeleteAndEnableDisablePersistAndReload() throws Exception {
        HookStore store = new HookStore(root);
        try (HookDispatcher dispatcher = defaultDispatcher()) {
            HookManager manager = new HookManager(store, dispatcher);
            assertEquals(0, manager.reload());
            HookConfigEntry created = entry("managed", false);
            assertEquals(created, manager.create(created));
            assertEquals(List.of(created), store.load());
            assertTrue(dispatcher.definitions().isEmpty());

            HookConfigEntry enabled = manager.setEnabled("managed", true);
            assertTrue(enabled.isEnabled());
            assertEquals(List.of("managed"), dispatcher.definitions().stream()
                    .map(definition -> definition.id()).toList());

            HookConfigEntry updated = entry("managed", true);
            assertEquals(updated, manager.update("managed", updated));
            HookConfigEntry disabled = manager.setEnabled("managed", false);
            assertFalse(disabled.isEnabled());
            assertTrue(dispatcher.definitions().isEmpty());

            assertEquals(disabled, manager.delete("managed"));
            assertTrue(manager.list().isEmpty());
            assertTrue(manager.get("managed").isEmpty());
        }
    }

    @Test
    void managerRejectsDuplicatesUnknownIdsAndMismatchedUpdateIds() throws Exception {
        HookStore store = new HookStore(root);
        try (HookDispatcher dispatcher = defaultDispatcher()) {
            HookManager manager = new HookManager(store, dispatcher);
            HookConfigEntry first = entry("first", true);
            manager.create(first);
            assertThrows(IllegalArgumentException.class,
                    () -> manager.create(first));
            assertThrows(IllegalArgumentException.class,
                    () -> manager.update("missing", first));
            assertThrows(IllegalArgumentException.class,
                    () -> manager.delete("missing"));
            assertThrows(IllegalArgumentException.class,
                    () -> manager.setEnabled("missing", true));
            assertThrows(IllegalArgumentException.class,
                    () -> manager.update("first", entry("other", true)));
            assertThrows(IllegalArgumentException.class,
                    () -> manager.delete(" "));
            assertTrue(manager.get(null).isEmpty());
        }
    }

    @Test
    void managerEnforcesMaximumAndDuplicateIdsDuringReload() throws Exception {
        HookStore store = new HookStore(root);
        List<HookConfigEntry> tooMany = new ArrayList<>();
        for (int i = 0; i < 101; i++) {
            tooMany.add(entry("hook-" + i, true));
        }
        store.save(tooMany);

        try (HookDispatcher dispatcher = defaultDispatcher()) {
            HookManager manager = new HookManager(store, dispatcher);
            assertThrows(IllegalArgumentException.class, manager::reload);
            store.save(List.of(entry("same", true), entry("same", false)));
            assertThrows(IllegalArgumentException.class, manager::reload);
        }
    }

    @Test
    void failedDiskWriteLeavesEntriesAndDispatcherUntouched() throws Exception {
        Path blockedRoot = root.resolve("blocked");
        Files.createDirectories(blockedRoot.resolve(".mycoder"));
        Files.writeString(blockedRoot.resolve(".mycoder").resolve("hooks"), "not a directory");

        HookStore store = new HookStore(blockedRoot);
        try (HookDispatcher dispatcher = defaultDispatcher()) {
            HookManager manager = new HookManager(store, dispatcher);
            assertEquals(0, manager.reload());
            HookConfigEntry candidate = entry("cannot-save", true);
            assertThrows(java.io.IOException.class, () -> manager.create(candidate));
            assertTrue(manager.list().isEmpty());
            assertTrue(dispatcher.definitions().isEmpty());
        }
    }

    @Test
    void sameIdReplacementRetainsOnceStateAndDeletionAllowsRefire() throws Exception {
        HookStore store = new HookStore(root);
        HookConfigEntry once = new HookConfigEntry(
                "once-hook", true, HookEvent.TURN_START,
                HookConfigEntry.SelectorConfig.any(),
                new HookConfigEntry.ActionConfig(
                        com.jcoder.hook.HookActionType.PROMPT,
                        "message", null, null, null, Map.of(), ""),
                false, true, false, HookErrorPolicy.CONTINUE, 1_000);
        store.save(List.of(once));

        try (HookDispatcher dispatcher = defaultDispatcher()) {
            HookManager manager = new HookManager(store, dispatcher);
            manager.reload();
            assertEquals(1, dispatcher.dispatch(HookContext.turnStart("", root, "")).matchedCount());
            manager.update("once-hook", once);
            assertEquals(0, dispatcher.dispatch(HookContext.turnStart("", root, "")).matchedCount());
            manager.delete("once-hook");
            manager.create(once);
            assertEquals(1, dispatcher.dispatch(HookContext.turnStart("", root, "")).matchedCount());
        }
    }

    private HookConfigEntry entry(String id, boolean enabled) {
        return new HookConfigEntry(
                id, enabled, HookEvent.TURN_START,
                HookConfigEntry.SelectorConfig.any(),
                new HookConfigEntry.ActionConfig(
                        com.jcoder.hook.HookActionType.PROMPT,
                        "message-" + id, null, null, null, Map.of(), ""),
                false, false, false, HookErrorPolicy.CONTINUE, 1_000);
    }

    private static HookDispatcher defaultDispatcher() {
        return new HookDispatcher(HookExecutorRegistry.createDefault(), List.of());
    }
}
