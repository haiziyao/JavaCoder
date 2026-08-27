package com.jcoder.hook;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HookResultTest {

    @Test
    void successFactoryCreatesNormalizedExecutionResult() {
        HookExecutionResult result = HookExecutionResult.success(null, 12);

        assertTrue(result.success());
        assertEquals("", result.output());
        assertEquals("", result.errorMessage());
        assertEquals(12, result.durationMillis());
    }

    @Test
    void failureFactoryPreservesErrorAndOutput() {
        HookExecutionResult result = HookExecutionResult.failure(
                "exit code 1", "partial output", 8);

        assertFalse(result.success());
        assertEquals("partial output", result.output());
        assertEquals("exit code 1", result.errorMessage());
        assertEquals(8, result.durationMillis());
    }

    @Test
    void executionResultRejectsContradictoryOrInvalidState() {
        assertThrows(IllegalArgumentException.class,
                () -> new HookExecutionResult(true, "", "unexpected error", 0));
        assertThrows(IllegalArgumentException.class,
                () -> new HookExecutionResult(false, "", null, 0));
        assertThrows(IllegalArgumentException.class,
                () -> new HookExecutionResult(false, "", "  ", 0));
        assertThrows(IllegalArgumentException.class,
                () -> HookExecutionResult.success("", -1));
    }

    @Test
    void continueDecisionDoesNotReject() {
        HookDecision decision = HookDecision.continueExecution();

        assertFalse(decision.rejected());
        assertEquals("", decision.message());
    }

    @Test
    void rejectDecisionRequiresReason() {
        HookDecision decision = HookDecision.reject("blocked by hook");

        assertTrue(decision.rejected());
        assertEquals("blocked by hook", decision.message());
        assertThrows(IllegalArgumentException.class, () -> HookDecision.reject(null));
        assertThrows(IllegalArgumentException.class, () -> HookDecision.reject("  "));
    }

    @Test
    void nonRejectingDecisionMayCarryInformationalMessage() {
        HookDecision decision = new HookDecision(false, "hook completed");

        assertFalse(decision.rejected());
        assertEquals("hook completed", decision.message());
    }
}
