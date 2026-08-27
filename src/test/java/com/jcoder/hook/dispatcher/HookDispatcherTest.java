package com.jcoder.hook.dispatcher;

import com.jcoder.hook.HookActionType;
import com.jcoder.hook.HookContext;
import com.jcoder.hook.HookDefinition;
import com.jcoder.hook.HookErrorPolicy;
import com.jcoder.hook.HookEvent;
import com.jcoder.hook.HookExecutionResult;
import com.jcoder.hook.HookSelector;
import com.jcoder.hook.action.PromptHookAction;
import com.jcoder.hook.executor.HookActionExecutor;
import com.jcoder.hook.executor.HookExecutorRegistry;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiFunction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HookDispatcherTest {

    @Test
    void dispatchMatchesEventAndSelectorAndSkipsEverythingElse() {
        RecordingExecutor executor = RecordingExecutor.successful();
        HookDefinition matching = definition(
                "matching", HookEvent.PRE_TOOL_USE,
                new HookSelector("WriteFile", Map.of("path", "application.json"), null),
                false, false);
        HookDefinition wrongEvent = definition(
                "wrong-event", HookEvent.POST_TOOL_USE, HookSelector.any(), false, false);
        HookDefinition wrongTool = definition(
                "wrong-tool", HookEvent.PRE_TOOL_USE,
                new HookSelector("ReadFile", Map.of(), null), false, false);
        HookDefinition wrongArgument = definition(
                "wrong-argument", HookEvent.PRE_TOOL_USE,
                new HookSelector(null, Map.of("path", "other.json"), null), false, false);

        try (HookDispatcher dispatcher = dispatcher(
                executor, List.of(wrongEvent, wrongTool, matching, wrongArgument))) {
            HookDispatchReport report = dispatcher.dispatch(HookContext.preTool(
                    "", Path.of("."), "WriteFile",
                    Map.of("path", "application.json")));

            assertEquals(1, report.matchedCount());
            assertEquals("matching", report.invocations().getFirst().definition().id());
            assertEquals(List.of("matching"), executor.executedIds);
        }
    }

    @Test
    void synchronousHooksExecuteAndReportInDefinitionOrder() {
        RecordingExecutor executor = RecordingExecutor.successful();
        List<HookDefinition> definitions = List.of(
                definition("first", HookEvent.TURN_START, HookSelector.any(), false, false),
                definition("second", HookEvent.TURN_START, HookSelector.any(), false, false),
                definition("third", HookEvent.TURN_START, HookSelector.any(), false, false));

        try (HookDispatcher dispatcher = dispatcher(executor, definitions)) {
            HookDispatchReport report = dispatcher.dispatch(turnStart());

            assertEquals(List.of("first", "second", "third"), executor.executedIds);
            assertEquals(
                    List.of("first", "second", "third"),
                    report.invocations().stream()
                            .map(result -> result.definition().id())
                            .toList()
            );
            assertEquals(3, report.completedCount());
            assertEquals(0, report.scheduledCount());
            assertFalse(report.hasFailures());
        }
    }

    @Test
    void synchronousExecutionPreservesRealFailureResult() {
        HookExecutionResult failure = HookExecutionResult.failure("speaker failed", "partial", 4);
        RecordingExecutor executor = new RecordingExecutor((definition, context) -> failure);

        try (HookDispatcher dispatcher = dispatcher(
                executor,
                List.of(definition(
                        "failure", HookEvent.TURN_START, HookSelector.any(), false, false)))) {
            HookDispatchReport report = dispatcher.dispatch(turnStart());

            assertEquals(1, report.completedCount());
            assertTrue(report.hasFailures());
            assertSame(failure, report.invocations().getFirst().executionResult());
        }
    }

    @Test
    void asynchronousDispatchReportsScheduledThenDrainReturnsRealResult() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        HookExecutionResult realResult = HookExecutionResult.success("async done", 7);
        RecordingExecutor executor = new RecordingExecutor((definition, context) -> {
            entered.countDown();
            try {
                if (!release.await(2, TimeUnit.SECONDS)) {
                    return HookExecutionResult.failure("test release timed out", "", 0);
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                return HookExecutionResult.failure("interrupted", "", 0);
            }
            return realResult;
        });
        HookDispatcher dispatcher = dispatcher(
                executor,
                List.of(definition(
                        "async-hook", HookEvent.TURN_START, HookSelector.any(), false, true)));

        try {
            HookDispatchReport report = dispatcher.dispatch(turnStart());

            assertEquals(1, report.scheduledCount());
            assertEquals(0, report.completedCount());
            assertTrue(report.invocations().getFirst().isScheduled());
            assertTrue(dispatcher.drainAsyncCompletedResults().isEmpty());
            assertTrue(entered.await(2, TimeUnit.SECONDS));

            release.countDown();
            List<HookInvocationResult> drained = awaitAsyncResults(dispatcher, 1);

            assertEquals(1, drained.size());
            assertTrue(drained.getFirst().isCompleted());
            assertSame(realResult, drained.getFirst().executionResult());
            assertTrue(dispatcher.drainAsyncCompletedResults().isEmpty());
        } finally {
            release.countDown();
            dispatcher.close();
        }
    }

    @Test
    void onceReservationIsAtomicAcrossConcurrentDispatches() throws Exception {
        int workers = 24;
        RecordingExecutor executor = RecordingExecutor.successful();
        HookDispatcher dispatcher = dispatcher(
                executor,
                List.of(definition(
                        "atomic-once", HookEvent.TURN_START, HookSelector.any(), true, false)));
        ExecutorService callers = Executors.newVirtualThreadPerTaskExecutor();
        CountDownLatch ready = new CountDownLatch(workers);
        CountDownLatch start = new CountDownLatch(1);

        try {
            List<Future<Integer>> futures = new ArrayList<>();
            for (int index = 0; index < workers; index++) {
                futures.add(callers.submit(() -> {
                    ready.countDown();
                    if (!start.await(2, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("start gate timed out");
                    }
                    return dispatcher.dispatch(turnStart()).matchedCount();
                }));
            }

            assertTrue(ready.await(2, TimeUnit.SECONDS));
            start.countDown();

            int totalMatches = 0;
            for (Future<Integer> future : futures) {
                totalMatches += future.get(2, TimeUnit.SECONDS);
            }

            assertEquals(1, totalMatches);
            assertEquals(1, executor.calls.get());
        } finally {
            start.countDown();
            callers.shutdownNow();
            dispatcher.close();
        }
    }

    @Test
    void failedOnceHookIsNotRetriedAutomatically() {
        RecordingExecutor executor = new RecordingExecutor(
                (definition, context) -> HookExecutionResult.failure("failed", "", 1));
        HookDefinition once = definition(
                "failed-once", HookEvent.TURN_START, HookSelector.any(), true, false);

        try (HookDispatcher dispatcher = dispatcher(executor, List.of(once))) {
            assertTrue(dispatcher.dispatch(turnStart()).hasFailures());
            assertEquals(0, dispatcher.dispatch(turnStart()).matchedCount());
            assertEquals(1, executor.calls.get());
        }
    }

    @Test
    void replaceDefinitionsRejectsDuplicatesMissingExecutorsAndNullMembers() {
        RecordingExecutor executor = RecordingExecutor.successful();
        HookDefinition original = definition(
                "original", HookEvent.TURN_START, HookSelector.any(), false, false);

        try (HookDispatcher dispatcher = dispatcher(executor, List.of(original))) {
            HookDefinition duplicateA = definition(
                    "duplicate", HookEvent.TURN_START, HookSelector.any(), false, false);
            HookDefinition duplicateB = definition(
                    "duplicate", HookEvent.TURN_END, HookSelector.any(), false, false);
            assertThrows(IllegalArgumentException.class,
                    () -> dispatcher.replaceDefinitions(List.of(duplicateA, duplicateB)));

            List<HookDefinition> withNull = new ArrayList<>();
            withNull.add(null);
            assertThrows(NullPointerException.class,
                    () -> dispatcher.replaceDefinitions(withNull));

            assertEquals(List.of(original), dispatcher.definitions());
        }

        HookExecutorRegistry emptyRegistry = new HookExecutorRegistry();
        HookDefinition noExecutor = definition(
                "no-executor", HookEvent.TURN_START, HookSelector.any(), false, false);
        assertThrows(IllegalArgumentException.class,
                () -> new HookDispatcher(emptyRegistry, List.of(noExecutor)));
    }

    @Test
    void definitionsAreImmutableSnapshotsAndNullListMeansEmpty() {
        RecordingExecutor executor = RecordingExecutor.successful();
        HookDefinition first = definition(
                "first", HookEvent.TURN_START, HookSelector.any(), false, false);
        List<HookDefinition> source = new ArrayList<>();
        source.add(first);

        try (HookDispatcher dispatcher = dispatcher(executor, source)) {
            source.clear();
            assertEquals(List.of(first), dispatcher.definitions());
            assertThrows(UnsupportedOperationException.class,
                    () -> dispatcher.definitions().add(first));

            dispatcher.replaceDefinitions(null);
            assertTrue(dispatcher.definitions().isEmpty());
            assertEquals(0, dispatcher.dispatch(turnStart()).matchedCount());
        }
    }

    @Test
    void replacingSameIdPreservesOnceState() {
        RecordingExecutor executor = RecordingExecutor.successful();
        HookDefinition firstVersion = definition(
                "stable-id", HookEvent.TURN_START, HookSelector.any(), true, false);
        HookDefinition secondVersion = definition(
                "stable-id", HookEvent.TURN_START, HookSelector.any(), true, false);

        try (HookDispatcher dispatcher = dispatcher(executor, List.of(firstVersion))) {
            assertEquals(1, dispatcher.dispatch(turnStart()).matchedCount());
            dispatcher.replaceDefinitions(List.of(secondVersion));
            assertEquals(0, dispatcher.dispatch(turnStart()).matchedCount());
            assertEquals(1, executor.calls.get());
        }
    }

    @Test
    void deletingIdClearsItsOnceStateBeforeItIsAddedAgain() {
        RecordingExecutor executor = RecordingExecutor.successful();
        HookDefinition once = definition(
                "temporary-id", HookEvent.TURN_START, HookSelector.any(), true, false);

        try (HookDispatcher dispatcher = dispatcher(executor, List.of(once))) {
            assertEquals(1, dispatcher.dispatch(turnStart()).matchedCount());
            dispatcher.replaceDefinitions(List.of());
            dispatcher.replaceDefinitions(List.of(once));
            assertEquals(1, dispatcher.dispatch(turnStart()).matchedCount());
            assertEquals(2, executor.calls.get());
        }
    }

    @Test
    void resetOnceStateAllowsAllOnceHooksToRunAgain() {
        RecordingExecutor executor = RecordingExecutor.successful();
        HookDefinition once = definition(
                "resettable", HookEvent.TURN_START, HookSelector.any(), true, false);

        try (HookDispatcher dispatcher = dispatcher(executor, List.of(once))) {
            assertEquals(1, dispatcher.dispatch(turnStart()).matchedCount());
            assertEquals(0, dispatcher.dispatch(turnStart()).matchedCount());
            dispatcher.resetOnceState();
            assertEquals(1, dispatcher.dispatch(turnStart()).matchedCount());
            assertEquals(2, executor.calls.get());
        }
    }

    @Test
    void closeIsIdempotentRejectsNewWorkAndStillAllowsInspectionAndDrain()
            throws Exception {
        RecordingExecutor executor = RecordingExecutor.successful();
        HookDefinition async = definition(
                "close-async", HookEvent.TURN_START, HookSelector.any(), false, true);
        HookDispatcher dispatcher = dispatcher(executor, List.of(async));

        HookDispatchReport scheduled = dispatcher.dispatch(turnStart());
        assertEquals(1, scheduled.scheduledCount());

        dispatcher.close();
        dispatcher.close();

        assertThrows(IllegalStateException.class, () -> dispatcher.dispatch(turnStart()));
        assertThrows(IllegalStateException.class,
                () -> dispatcher.replaceDefinitions(List.of()));
        assertEquals(List.of(async), dispatcher.definitions());
        assertEquals(1, awaitAsyncResults(dispatcher, 1).size());
        assertTrue(dispatcher.drainAsyncCompletedResults().isEmpty());
        dispatcher.resetOnceState();
    }

    @Test
    void constructorRequiresRegistryAndDispatchRequiresContext() {
        RecordingExecutor executor = RecordingExecutor.successful();
        HookExecutorRegistry registry = registry(executor);

        assertThrows(NullPointerException.class,
                () -> new HookDispatcher(null, List.of()));

        try (HookDispatcher dispatcher = new HookDispatcher(registry, List.of())) {
            assertThrows(NullPointerException.class, () -> dispatcher.dispatch(null));
        }
    }

    private static HookDispatcher dispatcher(
            RecordingExecutor executor,
            List<HookDefinition> definitions
    ) {
        return new HookDispatcher(registry(executor), definitions);
    }

    private static HookExecutorRegistry registry(RecordingExecutor executor) {
        return new HookExecutorRegistry().register(executor);
    }

    private static HookDefinition definition(
            String id,
            HookEvent event,
            HookSelector selector,
            boolean once,
            boolean async
    ) {
        return new HookDefinition(
                id, event, selector, new PromptHookAction("message-" + id),
                false, once, async, HookErrorPolicy.CONTINUE, 1_000);
    }

    private static HookContext turnStart() {
        return HookContext.turnStart("session-1", Path.of("."), "hello");
    }

    private static List<HookInvocationResult> awaitAsyncResults(
            HookDispatcher dispatcher,
            int expectedCount
    ) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(2).toNanos();
        List<HookInvocationResult> results = new ArrayList<>();

        while (System.nanoTime() < deadline && results.size() < expectedCount) {
            results.addAll(dispatcher.drainAsyncCompletedResults());
            if (results.size() < expectedCount) {
                Thread.sleep(10);
            }
        }

        assertEquals(expectedCount, results.size(), "async results did not finish in time");
        return List.copyOf(results);
    }

    private static final class RecordingExecutor implements HookActionExecutor {
        private final AtomicInteger calls = new AtomicInteger();
        private final List<String> executedIds = new CopyOnWriteArrayList<>();
        private final BiFunction<HookDefinition, HookContext, HookExecutionResult> behavior;

        private RecordingExecutor(
                BiFunction<HookDefinition, HookContext, HookExecutionResult> behavior
        ) {
            this.behavior = behavior;
        }

        private static RecordingExecutor successful() {
            return new RecordingExecutor((definition, context) ->
                    HookExecutionResult.success("done-" + definition.id(), 1));
        }

        @Override
        public HookActionType type() {
            return HookActionType.PROMPT;
        }

        @Override
        public HookExecutionResult execute(
                HookDefinition definition,
                HookContext context
        ) {
            calls.incrementAndGet();
            executedIds.add(definition.id());
            return behavior.apply(definition, context);
        }
    }
}
