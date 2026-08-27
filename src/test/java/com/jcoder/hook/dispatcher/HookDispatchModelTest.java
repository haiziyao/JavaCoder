package com.jcoder.hook.dispatcher;

import com.jcoder.hook.HookDefinition;
import com.jcoder.hook.HookErrorPolicy;
import com.jcoder.hook.HookEvent;
import com.jcoder.hook.HookExecutionResult;
import com.jcoder.hook.HookSelector;
import com.jcoder.hook.action.PromptHookAction;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HookDispatchModelTest {

    @Test
    void completedInvocationCarriesExecutionResultAndDerivedState() {
        HookDefinition definition = definition("complete", HookEvent.TURN_START);
        HookExecutionResult result = HookExecutionResult.success("done", 3);

        HookInvocationResult invocation = HookInvocationResult.completed(definition, result);

        assertSame(definition, invocation.definition());
        assertEquals(HookInvocationResult.State.COMPLETED, invocation.state());
        assertSame(result, invocation.executionResult());
        assertTrue(invocation.isCompleted());
        assertFalse(invocation.isScheduled());
        assertFalse(invocation.isFailure());
    }

    @Test
    void completedFailureIsReportedAsFailure() {
        HookInvocationResult invocation = HookInvocationResult.completed(
                definition("failure", HookEvent.TURN_START),
                HookExecutionResult.failure("failed", "partial", 2));

        assertTrue(invocation.isCompleted());
        assertTrue(invocation.isFailure());
    }

    @Test
    void scheduledInvocationHasNoExecutionResultAndIsNotPrematureFailure() {
        HookInvocationResult invocation = HookInvocationResult.scheduled(
                definition("scheduled", HookEvent.TURN_START));

        assertEquals(HookInvocationResult.State.SCHEDULED, invocation.state());
        assertNull(invocation.executionResult());
        assertFalse(invocation.isCompleted());
        assertTrue(invocation.isScheduled());
        assertFalse(invocation.isFailure());
    }

    @Test
    void invocationRejectsContradictoryAndMissingState() {
        HookDefinition definition = definition("invalid", HookEvent.TURN_START);
        HookExecutionResult result = HookExecutionResult.success("", 0);

        assertThrows(NullPointerException.class,
                () -> new HookInvocationResult(null,
                        HookInvocationResult.State.SCHEDULED, null));
        assertThrows(NullPointerException.class,
                () -> new HookInvocationResult(definition, null, null));
        assertThrows(IllegalArgumentException.class,
                () -> new HookInvocationResult(definition,
                        HookInvocationResult.State.COMPLETED, null));
        assertThrows(IllegalArgumentException.class,
                () -> new HookInvocationResult(definition,
                        HookInvocationResult.State.SCHEDULED, result));
        assertThrows(NullPointerException.class,
                () -> HookInvocationResult.completed(definition, null));
    }

    @Test
    void reportCopiesInvocationsAndComputesAllSummaries() {
        HookInvocationResult success = HookInvocationResult.completed(
                definition("success", HookEvent.TURN_START),
                HookExecutionResult.success("ok", 1));
        HookInvocationResult failure = HookInvocationResult.completed(
                definition("failure", HookEvent.TURN_START),
                HookExecutionResult.failure("bad", "", 1));
        HookInvocationResult scheduled = HookInvocationResult.scheduled(
                definition("scheduled", HookEvent.TURN_START));
        List<HookInvocationResult> source = new ArrayList<>(
                List.of(success, failure, scheduled));

        HookDispatchReport report = new HookDispatchReport(HookEvent.TURN_START, source);
        source.clear();

        assertEquals(3, report.matchedCount());
        assertEquals(2, report.completedCount());
        assertEquals(1, report.scheduledCount());
        assertEquals(List.of(failure), report.failures());
        assertTrue(report.hasFailures());
        assertThrows(UnsupportedOperationException.class,
                () -> report.invocations().add(success));
        assertThrows(UnsupportedOperationException.class,
                () -> report.failures().add(success));
    }

    @Test
    void emptyReportNormalizesNullList() {
        HookDispatchReport direct = new HookDispatchReport(HookEvent.TURN_END, null);
        HookDispatchReport factory = HookDispatchReport.empty(HookEvent.TURN_END);

        for (HookDispatchReport report : List.of(direct, factory)) {
            assertEquals(0, report.matchedCount());
            assertEquals(0, report.completedCount());
            assertEquals(0, report.scheduledCount());
            assertFalse(report.hasFailures());
        }
    }

    @Test
    void reportRejectsMissingOrMismatchedInvocation() {
        HookInvocationResult turnStart = HookInvocationResult.scheduled(
                definition("start", HookEvent.TURN_START));
        List<HookInvocationResult> withNull = new ArrayList<>();
        withNull.add(null);

        assertThrows(NullPointerException.class,
                () -> new HookDispatchReport(null, List.of()));
        assertThrows(NullPointerException.class,
                () -> new HookDispatchReport(HookEvent.TURN_START, withNull));
        assertThrows(IllegalArgumentException.class,
                () -> new HookDispatchReport(HookEvent.TURN_END, List.of(turnStart)));
    }

    private static HookDefinition definition(String id, HookEvent event) {
        return new HookDefinition(
                id, event, HookSelector.any(), new PromptHookAction("message"),
                false, false, false, HookErrorPolicy.CONTINUE, 1_000);
    }
}
