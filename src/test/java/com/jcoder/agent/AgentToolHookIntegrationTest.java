package com.jcoder.agent;

import com.jcoder.hook.HookActionType;
import com.jcoder.hook.HookContext;
import com.jcoder.hook.HookDefinition;
import com.jcoder.hook.HookErrorPolicy;
import com.jcoder.hook.HookEvent;
import com.jcoder.hook.HookExecutionResult;
import com.jcoder.hook.HookSelector;
import com.jcoder.hook.action.PromptHookAction;
import com.jcoder.hook.controller.ToolHookController;
import com.jcoder.hook.dispatcher.HookDispatcher;
import com.jcoder.hook.executor.HookActionExecutor;
import com.jcoder.hook.executor.HookExecutorRegistry;
import com.jcoder.llm.LLMClient;
import com.jcoder.llm.model.ResponseBody;
import com.jcoder.llm.model.StreamBlock;
import com.jcoder.message.ConversationManager;
import com.jcoder.message.Message;
import com.jcoder.message.ToolResult;
import com.jcoder.permission.PermissionChecker;
import com.jcoder.permission.PermissionMode;
import com.jcoder.prompt.PromptContent;
import com.jcoder.tool.Tool;
import com.jcoder.tool.ToolCategory;
import com.jcoder.tool.ToolDefinition;
import com.jcoder.tool.ToolExecuteResult;
import com.jcoder.tool.ToolRegister;
import com.jcoder.tool.ToolReturnDefinition;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiFunction;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentToolHookIntegrationTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void missingPermissionCheckerStillRunsPreToolAndPostToolHooks() throws Exception {
        try (Scenario scenario = scenario(
                () -> ToolExecuteResult.success("tool-ok"), defaultHooks(), successfulHooks())) {
            scenario.run();

            assertEquals(List.of("pre", "tool", "post"), scenario.order);
            assertToolResult(scenario, "tool-ok", false);
        }
    }

    @Test
    void permissionDenyDoesNotTriggerHooksOrTool() throws Exception {
        try (Scenario scenario = scenario(
                () -> ToolExecuteResult.success("must-not-run"), defaultHooks(), successfulHooks())) {
            scenario.agent.setChecker(new RecordingPermissionChecker(
                    scenario.order, PermissionChecker.CheckResult.deny("blocked")));

            scenario.run();

            assertEquals(List.of("permission"), scenario.order);
            assertToolResult(scenario, "Permission denied: blocked", true);
        }
    }

    @Test
    void allowOrderIsPermissionThenPreThenToolThenPost() throws Exception {
        try (Scenario scenario = scenario(
                () -> ToolExecuteResult.success("tool-ok"), defaultHooks(), successfulHooks())) {
            scenario.agent.setChecker(new RecordingPermissionChecker(
                    scenario.order, PermissionChecker.CheckResult.allow()));

            scenario.run();

            assertEquals(List.of("permission", "pre", "tool", "post"), scenario.order);
            assertToolResult(scenario, "tool-ok", false);
        }
    }

    @Test
    void preHookRejectionSkipsToolAndPostHook() throws Exception {
        HookDefinition reject = hook("pre-reject", HookEvent.PRE_TOOL_USE, true);
        try (Scenario scenario = scenario(
                () -> ToolExecuteResult.success("must-not-run"),
                List.of(reject, hook("post", HookEvent.POST_TOOL_USE, false)),
                (definition, context) -> HookExecutionResult.success("protected", 1))) {
            scenario.agent.setChecker(new RecordingPermissionChecker(
                    scenario.order, PermissionChecker.CheckResult.allow()));

            scenario.run();

            assertEquals(List.of("permission", "pre"), scenario.order);
            assertToolResult(scenario, "Rejected by hook: protected", true);
        }
    }

    @Test
    void toolErrorStillRunsPostHookWithRealErrorContext() throws Exception {
        AtomicReference<HookContext> postContext = new AtomicReference<>();
        try (Scenario scenario = scenario(
                () -> ToolExecuteResult.error("tool-error"),
                defaultHooks(),
                (definition, context) -> {
                    if (context.event() == HookEvent.POST_TOOL_USE) {
                        postContext.set(context);
                    }
                    return HookExecutionResult.success("observed", 1);
                })) {
            scenario.run();

            assertEquals(List.of("pre", "tool", "post"), scenario.order);
            assertToolResult(scenario, "tool-error", true);
            assertEquals("tool-error", postContext.get().toolOutput());
            assertTrue(postContext.get().toolError());
            assertTrue(postContext.get().toolDurationMillis() >= 0);
        }
    }

    @Test
    void toolRuntimeExceptionIsConvertedToErrorAndStillRunsPostHook() throws Exception {
        AtomicReference<HookContext> postContext = new AtomicReference<>();
        try (Scenario scenario = scenario(
                () -> {
                    throw new IllegalStateException("boom");
                },
                defaultHooks(),
                (definition, context) -> {
                    if (context.event() == HookEvent.POST_TOOL_USE) {
                        postContext.set(context);
                    }
                    return HookExecutionResult.success("observed", 1);
                })) {
            scenario.run();

            assertEquals(List.of("pre", "tool", "post"), scenario.order);
            assertToolResult(scenario, "Tool execution failed: boom", true);
            assertEquals("Tool execution failed: boom", postContext.get().toolOutput());
            assertTrue(postContext.get().toolError());
        }
    }

    @Test
    void postHookFailureDoesNotOverwriteSuccessfulToolResult() throws Exception {
        try (Scenario scenario = scenario(
                () -> ToolExecuteResult.success("tool-success"),
                defaultHooks(),
                (definition, context) -> context.event() == HookEvent.POST_TOOL_USE
                        ? HookExecutionResult.failure("audit failed", "", 1)
                        : HookExecutionResult.success("ok", 1))) {
            scenario.run();

            assertEquals(List.of("pre", "tool", "post"), scenario.order);
            assertToolResult(scenario, "tool-success", false);
            assertTrue(scenario.events.stream()
                    .filter(AgentEvent.Log.class::isInstance)
                    .map(AgentEvent.Log.class::cast)
                    .anyMatch(log -> log.message().contains(
                            "[hook] failed id=post error=audit failed")));
        }
    }

    @Test
    void missingControllerPreservesLegacyDirectToolExecution() throws Exception {
        Scenario scenario = scenarioWithoutController(
                () -> ToolExecuteResult.success("legacy-ok"));
        try (scenario) {
            scenario.run();

            assertEquals(List.of("tool"), scenario.order);
            assertToolResult(scenario, "legacy-ok", false);
            assertEquals(2, scenario.client.calls.get());
        }
    }

    private Scenario scenario(
            Supplier<ToolExecuteResult> toolBehavior,
            List<HookDefinition> definitions,
            BiFunction<HookDefinition, HookContext, HookExecutionResult> hookBehavior
    ) {
        Scenario scenario = baseScenario(toolBehavior);
        RecordingHookExecutor hookExecutor = new RecordingHookExecutor(scenario.order, hookBehavior);
        HookDispatcher dispatcher = new HookDispatcher(
                new HookExecutorRegistry().register(hookExecutor), definitions);
        scenario.dispatcher = dispatcher;
        scenario.agent.setToolHookController(new ToolHookController(dispatcher));
        return scenario;
    }

    private Scenario scenarioWithoutController(Supplier<ToolExecuteResult> toolBehavior) {
        return baseScenario(toolBehavior);
    }

    private Scenario baseScenario(Supplier<ToolExecuteResult> toolBehavior) {
        List<String> order = new CopyOnWriteArrayList<>();
        RecordingTool tool = new RecordingTool(order, toolBehavior);
        ToolRegister register = new ToolRegister();
        register.register(tool);
        ToolThenAnswerClient client = new ToolThenAnswerClient(tool.name());
        Agent agent = new Agent(client, register, 128_000, 8_192);
        agent.setWorkDir(temporaryDirectory.toString());
        agent.setSessionId("session-1");
        ConversationManager conversation = new ConversationManager();
        conversation.addUserMsg("run the test tool");
        return new Scenario(agent, client, conversation, order);
    }

    private static List<HookDefinition> defaultHooks() {
        return List.of(
                hook("pre", HookEvent.PRE_TOOL_USE, false),
                hook("post", HookEvent.POST_TOOL_USE, false)
        );
    }

    private static BiFunction<HookDefinition, HookContext, HookExecutionResult>
    successfulHooks() {
        return (definition, context) -> HookExecutionResult.success("ok", 1);
    }

    private static HookDefinition hook(String id, HookEvent event, boolean reject) {
        return new HookDefinition(
                id, event, HookSelector.any(), new PromptHookAction("message"),
                reject, false, false, HookErrorPolicy.CONTINUE, 1_000);
    }

    private static void assertToolResult(
            Scenario scenario,
            String expectedOutput,
            boolean expectedError
    ) {
        List<ToolResult> results = scenario.conversation.getHistoryCopy().stream()
                .map(Message::getToolResults)
                .filter(java.util.Objects::nonNull)
                .flatMap(List::stream)
                .toList();

        assertEquals(1, results.size());
        assertEquals(expectedOutput, results.getFirst().content());
        assertEquals(expectedError, results.getFirst().isError());
    }

    private static final class Scenario implements AutoCloseable {
        private final Agent agent;
        private final ToolThenAnswerClient client;
        private final ConversationManager conversation;
        private final List<String> order;
        private final List<AgentEvent> events = new CopyOnWriteArrayList<>();
        private HookDispatcher dispatcher;

        private Scenario(
                Agent agent,
                ToolThenAnswerClient client,
                ConversationManager conversation,
                List<String> order
        ) {
            this.agent = agent;
            this.client = client;
            this.conversation = conversation;
            this.order = order;
        }

        private void run() throws InterruptedException {
            agent.agentLoop(conversation, new RecordingEventQueue(events));
        }

        @Override
        public void close() {
            if (dispatcher != null) {
                dispatcher.close();
            }
        }
    }

    private static final class RecordingEventQueue extends AgentEventQueue {
        private final List<AgentEvent> events;

        private RecordingEventQueue(List<AgentEvent> events) {
            super(1);
            this.events = events;
        }

        @Override
        public void putSafe(AgentEvent event) {
            events.add(event);
        }
    }

    private static final class RecordingPermissionChecker extends PermissionChecker {
        private final List<String> order;
        private final CheckResult result;

        private RecordingPermissionChecker(List<String> order, CheckResult result) {
            super(PermissionMode.BYPASS, null);
            this.order = order;
            this.result = result;
        }

        @Override
        public CheckResult check(Tool tool, Map<String, Object> args) {
            order.add("permission");
            return result;
        }
    }

    private static final class RecordingTool implements Tool {
        private final List<String> order;
        private final Supplier<ToolExecuteResult> behavior;

        private RecordingTool(List<String> order, Supplier<ToolExecuteResult> behavior) {
            this.order = order;
            this.behavior = behavior;
        }

        @Override
        public String name() {
            return "TestTool";
        }

        @Override
        public String description() {
            return "Test tool";
        }

        @Override
        public ToolCategory category() {
            return ToolCategory.READ;
        }

        @Override
        public ToolDefinition definition() {
            return new ToolDefinition(
                    name(), description(), Map.of(), List.of(),
                    new ToolReturnDefinition("string", Map.of()));
        }

        @Override
        public ToolExecuteResult execute(Map<String, Object> args) {
            order.add("tool");
            return behavior.get();
        }
    }

    private static final class RecordingHookExecutor implements HookActionExecutor {
        private final List<String> order;
        private final BiFunction<HookDefinition, HookContext, HookExecutionResult> behavior;

        private RecordingHookExecutor(
                List<String> order,
                BiFunction<HookDefinition, HookContext, HookExecutionResult> behavior
        ) {
            this.order = order;
            this.behavior = behavior;
        }

        @Override
        public HookActionType type() {
            return HookActionType.PROMPT;
        }

        @Override
        public HookExecutionResult execute(HookDefinition definition, HookContext context) {
            order.add(context.event() == HookEvent.PRE_TOOL_USE ? "pre" : "post");
            return behavior.apply(definition, context);
        }
    }

    private static final class ToolThenAnswerClient implements LLMClient {
        private final String toolName;
        private final AtomicInteger calls = new AtomicInteger();

        private ToolThenAnswerClient(String toolName) {
            this.toolName = toolName;
        }

        @Override
        public BlockingQueue<StreamBlock> stream(PromptContent promptContent) {
            int call = calls.incrementAndGet();
            if (call == 1) {
                return queue(
                        new StreamBlock.ToolCall(
                                "call-1", toolName, "function", Map.of("value", "x")),
                        new StreamBlock.StreamEnd("tool_calls")
                );
            }
            if (call == 2) {
                return queue(
                        new StreamBlock.ContentDelta("done"),
                        new StreamBlock.StreamEnd("stop")
                );
            }
            throw new AssertionError("unexpected model call " + call);
        }

        @Override
        public ResponseBody request(PromptContent promptContent) {
            throw new AssertionError("Agent must use stream()");
        }

        @Override
        public String getLastRequestJson() {
            return "";
        }

        private static BlockingQueue<StreamBlock> queue(StreamBlock... blocks) {
            BlockingQueue<StreamBlock> queue = new LinkedBlockingQueue<>();
            queue.addAll(List.of(blocks));
            return queue;
        }
    }
}
