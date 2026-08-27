package com.jcoder.hook.controller;

import com.jcoder.hook.HookActionType;
import com.jcoder.hook.HookContext;
import com.jcoder.hook.HookDefinition;
import com.jcoder.hook.HookErrorPolicy;
import com.jcoder.hook.HookEvent;
import com.jcoder.hook.HookExecutionResult;
import com.jcoder.hook.HookSelector;
import com.jcoder.hook.action.PromptHookAction;
import com.jcoder.hook.dispatcher.HookDispatchReport;
import com.jcoder.hook.dispatcher.HookDispatcher;
import com.jcoder.hook.executor.HookActionExecutor;
import com.jcoder.hook.executor.HookExecutorRegistry;
import com.jcoder.tool.ToolExecuteResult;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiFunction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ToolHookControllerTest {

    @Test
    void successfulRejectHookBlocksWithExecutorOutput() {
        RecordingExecutor executor = new RecordingExecutor(
                (definition, context) -> HookExecutionResult.success("configuration is protected", 1));
        HookDefinition hook = definition(
                "protect-config", HookEvent.PRE_TOOL_USE, true,
                false, HookErrorPolicy.CONTINUE);

        try (Fixture fixture = fixture(executor, List.of(hook))) {
            ToolHookController.BeforeToolResult before = fixture.controller.beforeTool(
                    "session-1", Path.of("."), "WriteFile",
                    Map.of("path", "application.json"));

            assertTrue(before.decision().rejected());
            assertEquals("configuration is protected", before.decision().message());
            assertEquals(1, before.report().matchedCount());
        }
    }

    @Test
    void executorFailureRejectsOnlyWhenOnErrorIsReject() {
        RecordingExecutor executor = new RecordingExecutor(
                (definition, context) -> HookExecutionResult.failure("check unavailable", "", 1));
        HookDefinition reject = definition(
                "fail-closed", HookEvent.PRE_TOOL_USE, false,
                false, HookErrorPolicy.REJECT);

        try (Fixture fixture = fixture(executor, List.of(reject))) {
            ToolHookController.BeforeToolResult before = fixture.controller.beforeTool(
                    "", Path.of("."), "WriteFile", Map.of());

            assertTrue(before.decision().rejected());
            assertEquals("hook fail-closed failed: check unavailable", before.decision().message());
            assertTrue(before.report().hasFailures());
        }
    }

    @Test
    void executorFailureContinuesWhenOnErrorIsContinue() {
        RecordingExecutor executor = new RecordingExecutor(
                (definition, context) -> HookExecutionResult.failure("optional check failed", "", 1));
        HookDefinition hook = definition(
                "fail-open", HookEvent.PRE_TOOL_USE, false,
                false, HookErrorPolicy.CONTINUE);

        try (Fixture fixture = fixture(executor, List.of(hook))) {
            ToolHookController.BeforeToolResult before = fixture.controller.beforeTool(
                    "", Path.of("."), "WriteFile", Map.of());

            assertFalse(before.decision().rejected());
            assertTrue(before.report().hasFailures());
        }
    }

    @Test
    void firstRejectingHookDeterminesMessageWhenSeveralReject() {
        RecordingExecutor executor = new RecordingExecutor((definition, context) ->
                HookExecutionResult.success("blocked-by-" + definition.id(), 1));
        HookDefinition first = definition(
                "first", HookEvent.PRE_TOOL_USE, true, false, HookErrorPolicy.CONTINUE);
        HookDefinition second = definition(
                "second", HookEvent.PRE_TOOL_USE, true, false, HookErrorPolicy.CONTINUE);

        try (Fixture fixture = fixture(executor, List.of(first, second))) {
            ToolHookController.BeforeToolResult before = fixture.controller.beforeTool(
                    "", Path.of("."), "WriteFile", Map.of());

            assertTrue(before.decision().rejected());
            assertEquals("blocked-by-first", before.decision().message());
            assertEquals(List.of("first", "second"), executor.ids);
        }
    }

    @Test
    void asynchronousPreHookIsScheduledAndCannotBlockCurrentTool() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        RecordingExecutor executor = new RecordingExecutor((definition, context) -> {
            entered.countDown();
            try {
                release.await(2, TimeUnit.SECONDS);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
            return HookExecutionResult.failure("late failure", "", 1);
        });
        HookDefinition async = definition(
                "async-observer", HookEvent.PRE_TOOL_USE, false,
                true, HookErrorPolicy.CONTINUE);
        Fixture fixture = fixture(executor, List.of(async));

        try {
            ToolHookController.BeforeToolResult before = fixture.controller.beforeTool(
                    "", Path.of("."), "WriteFile", Map.of());

            assertFalse(before.decision().rejected());
            assertEquals(1, before.report().scheduledCount());
            assertTrue(entered.await(2, TimeUnit.SECONDS));
        } finally {
            release.countDown();
            fixture.close();
        }
    }

    @Test
    void afterToolPassesRealOutputErrorAndDurationToPostContext() {
        AtomicReference<HookContext> captured = new AtomicReference<>();
        RecordingExecutor executor = new RecordingExecutor((definition, context) -> {
            captured.set(context);
            return HookExecutionResult.success("observed", 1);
        });
        HookDefinition post = definition(
                "post-observer", HookEvent.POST_TOOL_USE, false,
                false, HookErrorPolicy.CONTINUE);

        try (Fixture fixture = fixture(executor, List.of(post))) {
            ToolExecuteResult toolResult = ToolExecuteResult.error("disk full");
            HookDispatchReport report = fixture.controller.afterTool(
                    "session-9", Path.of("."), "WriteFile",
                    Map.of("path", "a.txt"), toolResult, 27);

            HookContext context = captured.get();
            assertEquals(1, report.matchedCount());
            assertEquals(HookEvent.POST_TOOL_USE, context.event());
            assertEquals("session-9", context.sessionId());
            assertEquals("WriteFile", context.toolName());
            assertEquals(Map.of("path", "a.txt"), context.toolArguments());
            assertEquals("disk full", context.toolOutput());
            assertTrue(context.toolError());
            assertEquals(27, context.toolDurationMillis());
        }
    }

    @Test
    void afterToolFailureIsObservationOnlyAndDoesNotChangeToolResult() {
        RecordingExecutor executor = new RecordingExecutor(
                (definition, context) -> HookExecutionResult.failure("audit unavailable", "", 1));
        HookDefinition post = definition(
                "post-failure", HookEvent.POST_TOOL_USE, false,
                false, HookErrorPolicy.CONTINUE);
        ToolExecuteResult toolResult = ToolExecuteResult.success("written");

        try (Fixture fixture = fixture(executor, List.of(post))) {
            HookDispatchReport report = fixture.controller.afterTool(
                    "", Path.of("."), "WriteFile", Map.of(), toolResult, 1);

            assertTrue(report.hasFailures());
            assertEquals("written", toolResult.output());
            assertFalse(toolResult.isError());
        }
    }

    @Test
    void validatesControllerAndBeforeResultInvariants() {
        assertThrows(NullPointerException.class, () -> new ToolHookController(null));

        RecordingExecutor executor = RecordingExecutor.successful();
        try (Fixture fixture = fixture(executor, List.of())) {
            assertThrows(NullPointerException.class,
                    () -> fixture.controller.afterTool(
                            "", Path.of("."), "Tool", Map.of(), null, 0));
        }

        HookDispatchReport post = HookDispatchReport.empty(HookEvent.POST_TOOL_USE);
        assertThrows(NullPointerException.class,
                () -> new ToolHookController.BeforeToolResult(null,
                        HookDispatchReport.empty(HookEvent.PRE_TOOL_USE)));
        assertThrows(NullPointerException.class,
                () -> new ToolHookController.BeforeToolResult(
                        com.jcoder.hook.HookDecision.continueExecution(), null));
        assertThrows(IllegalArgumentException.class,
                () -> new ToolHookController.BeforeToolResult(
                        com.jcoder.hook.HookDecision.continueExecution(), post));
    }

    private static HookDefinition definition(
            String id,
            HookEvent event,
            boolean reject,
            boolean async,
            HookErrorPolicy onError
    ) {
        return new HookDefinition(
                id, event, HookSelector.any(), new PromptHookAction("message"),
                reject, false, async, onError, 1_000);
    }

    private static Fixture fixture(
            RecordingExecutor executor,
            List<HookDefinition> definitions
    ) {
        HookExecutorRegistry registry = new HookExecutorRegistry().register(executor);
        HookDispatcher dispatcher = new HookDispatcher(registry, definitions);
        return new Fixture(dispatcher, new ToolHookController(dispatcher));
    }

    private record Fixture(
            HookDispatcher dispatcher,
            ToolHookController controller
    ) implements AutoCloseable {
        @Override
        public void close() {
            dispatcher.close();
        }
    }

    private static final class RecordingExecutor implements HookActionExecutor {
        private final AtomicInteger calls = new AtomicInteger();
        private final List<String> ids = new java.util.concurrent.CopyOnWriteArrayList<>();
        private final BiFunction<HookDefinition, HookContext, HookExecutionResult> behavior;

        private RecordingExecutor(
                BiFunction<HookDefinition, HookContext, HookExecutionResult> behavior
        ) {
            this.behavior = behavior;
        }

        private static RecordingExecutor successful() {
            return new RecordingExecutor(
                    (definition, context) -> HookExecutionResult.success("ok", 1));
        }

        @Override
        public HookActionType type() {
            return HookActionType.PROMPT;
        }

        @Override
        public HookExecutionResult execute(HookDefinition definition, HookContext context) {
            calls.incrementAndGet();
            ids.add(definition.id());
            return behavior.apply(definition, context);
        }
    }
}
