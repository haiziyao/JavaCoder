package com.jcoder.hook;

import com.jcoder.hook.action.CommandHookAction;
import com.jcoder.hook.action.HookAction;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HookDefinitionTest {

    private static final HookAction ACTION = new CommandHookAction("echo meow");

    @Test
    void normalizesDefaultsAndAcceptsValidPreToolHook() {
        HookDefinition definition = definition(
                "  meow_on_open  ",
                HookEvent.PRE_TOOL_USE,
                null,
                true,
                true,
                false,
                null,
                0
        );

        assertEquals("meow_on_open", definition.id());
        assertEquals(HookSelector.any(), definition.selector());
        assertEquals(HookErrorPolicy.CONTINUE, definition.onError());
        assertEquals(HookDefinition.DEFAULT_TIMEOUT_MILLIS, definition.timeoutMillis());
        assertTrue(definition.reject());
        assertTrue(definition.once());
        assertFalse(definition.async());
    }

    @Test
    void acceptsBoundaryIdsAndTimeouts() {
        String maximumId = "a" + "1".repeat(63);

        assertEquals(1, definition(
                "a", HookEvent.TURN_START, HookSelector.any(), false,
                false, false, HookErrorPolicy.CONTINUE, 1).timeoutMillis());
        assertEquals(HookDefinition.MAX_TIMEOUT_MILLIS, definition(
                maximumId, HookEvent.POST_TOOL_USE, HookSelector.any(), false,
                false, true, HookErrorPolicy.CONTINUE,
                HookDefinition.MAX_TIMEOUT_MILLIS).timeoutMillis());
    }

    @Test
    void rejectsMissingOrInvalidIds() {
        for (String id : new String[]{
                null, "  ", "Uppercase", "-leading", "has space", "a".repeat(65)
        }) {
            assertThrows(IllegalArgumentException.class,
                    () -> definition(id, HookEvent.TURN_START, HookSelector.any(),
                            false, false, false, HookErrorPolicy.CONTINUE, 10));
        }
    }

    @Test
    void requiresEventAndAction() {
        assertThrows(NullPointerException.class,
                () -> definition("missing-event", null, HookSelector.any(),
                        false, false, false, HookErrorPolicy.CONTINUE, 10));
        assertThrows(NullPointerException.class,
                () -> new HookDefinition(
                        "missing-action", HookEvent.TURN_START, HookSelector.any(), null,
                        false, false, false, HookErrorPolicy.CONTINUE, 10));
    }

    @Test
    void lifecycleEventsCannotUseToolSelectors() {
        HookSelector toolSelector = new HookSelector("WriteFile", Map.of(), null);

        assertThrows(IllegalArgumentException.class,
                () -> definition("turn-start-tool", HookEvent.TURN_START, toolSelector,
                        false, false, false, HookErrorPolicy.CONTINUE, 10));
        assertThrows(IllegalArgumentException.class,
                () -> definition("turn-end-tool", HookEvent.TURN_END, toolSelector,
                        false, false, false, HookErrorPolicy.CONTINUE, 10));
    }

    @Test
    void toolErrorConditionIsOnlyValidAfterToolExecution() {
        HookSelector selector = new HookSelector(null, Map.of(), true);

        assertThrows(IllegalArgumentException.class,
                () -> definition("pre-error", HookEvent.PRE_TOOL_USE, selector,
                        false, false, false, HookErrorPolicy.CONTINUE, 10));

        HookDefinition valid = definition(
                "post-error", HookEvent.POST_TOOL_USE, selector,
                false, false, false, HookErrorPolicy.CONTINUE, 10);
        assertEquals(Boolean.TRUE, valid.selector().toolError());
    }

    @Test
    void onlyPreToolHookCanRejectOnSuccess() {
        for (HookEvent event : new HookEvent[]{
                HookEvent.TURN_START, HookEvent.TURN_END, HookEvent.POST_TOOL_USE
        }) {
            assertThrows(IllegalArgumentException.class,
                    () -> definition("reject-" + event.name().toLowerCase(), event,
                            HookSelector.any(), true, false, false,
                            HookErrorPolicy.CONTINUE, 10));
        }
    }

    @Test
    void onlyPreToolHookCanRejectOnExecutorError() {
        for (HookEvent event : new HookEvent[]{
                HookEvent.TURN_START, HookEvent.TURN_END, HookEvent.POST_TOOL_USE
        }) {
            assertThrows(IllegalArgumentException.class,
                    () -> definition("error-" + event.name().toLowerCase(), event,
                            HookSelector.any(), false, false, false,
                            HookErrorPolicy.REJECT, 10));
        }

        HookDefinition valid = definition(
                "pre-error-reject", HookEvent.PRE_TOOL_USE, HookSelector.any(),
                false, false, false, HookErrorPolicy.REJECT, 10);
        assertEquals(HookErrorPolicy.REJECT, valid.onError());
    }

    @Test
    void asynchronousHookCannotRejectMainFlow() {
        assertThrows(IllegalArgumentException.class,
                () -> definition("async-reject", HookEvent.PRE_TOOL_USE, HookSelector.any(),
                        true, false, true, HookErrorPolicy.CONTINUE, 10));
        assertThrows(IllegalArgumentException.class,
                () -> definition("async-error-reject", HookEvent.PRE_TOOL_USE, HookSelector.any(),
                        false, false, true, HookErrorPolicy.REJECT, 10));
    }

    @Test
    void rejectsTimeoutOutsideAllowedRange() {
        assertThrows(IllegalArgumentException.class,
                () -> definition("negative-timeout", HookEvent.TURN_START, HookSelector.any(),
                        false, false, false, HookErrorPolicy.CONTINUE, -1));
        assertThrows(IllegalArgumentException.class,
                () -> definition("large-timeout", HookEvent.TURN_START, HookSelector.any(),
                        false, false, false, HookErrorPolicy.CONTINUE,
                        HookDefinition.MAX_TIMEOUT_MILLIS + 1));
    }

    private static HookDefinition definition(
            String id,
            HookEvent event,
            HookSelector selector,
            boolean reject,
            boolean once,
            boolean async,
            HookErrorPolicy onError,
            long timeoutMillis
    ) {
        return new HookDefinition(
                id,
                event,
                selector,
                ACTION,
                reject,
                once,
                async,
                onError,
                timeoutMillis
        );
    }
}
