package com.jcoder.hook;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HookEventTest {

    @Test
    void onlyToolEventsExposeToolData() {
        assertFalse(HookEvent.TURN_START.isToolEvent());
        assertFalse(HookEvent.TURN_END.isToolEvent());
        assertTrue(HookEvent.PRE_TOOL_USE.isToolEvent());
        assertTrue(HookEvent.POST_TOOL_USE.isToolEvent());
    }

    @Test
    void onlyPreToolUseCanRejectMainFlow() {
        assertFalse(HookEvent.TURN_START.allowsRejection());
        assertFalse(HookEvent.TURN_END.allowsRejection());
        assertTrue(HookEvent.PRE_TOOL_USE.allowsRejection());
        assertFalse(HookEvent.POST_TOOL_USE.allowsRejection());
    }
}
