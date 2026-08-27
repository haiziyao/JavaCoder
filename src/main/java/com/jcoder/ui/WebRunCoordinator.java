package com.jcoder.ui;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jcoder.agent.Agent;
import com.jcoder.agent.AgentEvent;
import com.jcoder.agent.AgentEventQueue;
import com.jcoder.context.ContextCompactor;
import com.jcoder.memory.MemoryRecall;
import com.jcoder.memory.MemoryService;
import com.jcoder.message.ConversationManager;
import com.jcoder.message.Message;
import com.jcoder.message.ToolCallBlock;
import com.jcoder.message.ToolResult;
import com.jcoder.permission.PermissionChecker;
import com.jcoder.permission.PermissionResponse;
import com.jcoder.session.SessionManager;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

import java.io.IOException;

final class WebRunCoordinator {

    static final int MAX_EVENTS = 1_000;
    static final int MAX_EVENT_BYTES = 4 * 1024 * 1024;

    private final SessionManager sessionManager;
    private final MemoryService memoryService;


    enum RunState {
        IDLE,
        RUNNING,
        WAITING_PERMISSION,
        COMPACTING,
        COMPLETED,
        FAILED;

        boolean active() {
            return this == RUNNING || this == WAITING_PERMISSION || this == COMPACTING;
        }
    }

    record WebEvent(long sequence, String type, String json) {
    }

    record RunSnapshot(String runId, RunState state, long lastEventId) {
    }

    private record PendingPermission(
            String requestId,
            Run run,
            CompletableFuture<PermissionResponse> future
    ) {
    }

    private final Agent agent;
    private final ConversationManager conversation;
    private final ObjectMapper mapper;
    private final Map<String, PendingPermission> pendingPermissions =
            new ConcurrentHashMap<>();
    private final Object lifecycleLock = new Object();

    private volatile Run currentRun;
    private volatile Map<String, Object> lastContext;

    WebRunCoordinator(
            Agent agent,
            ConversationManager conversation,
            ObjectMapper mapper
    ) {
        this(agent, conversation, mapper, null, null);
    }

    public WebRunCoordinator(
            Agent agent,
            ConversationManager conversation,
            ObjectMapper mapper,
            SessionManager sessionManager,
            MemoryService memoryService
    ) {
        this.agent = agent;
        this.conversation = conversation;
        this.mapper = mapper;
        this.sessionManager = sessionManager;
        this.memoryService = memoryService;
    }

    RunSnapshot startUserRun(String message) {
        String actualMessage = message == null ? "" : message.strip();
        if (actualMessage.isEmpty()) {
            throw new IllegalArgumentException("消息不能为空");
        }

        Run run;
        synchronized (lifecycleLock) {
            ensureIdle();
            prepareMemoryReminder(actualMessage);

            Message userMessage = new Message("user", actualMessage);
            conversation.addMessage(userMessage);

            run = createRun(
                    RunState.RUNNING,
                    "chat",
                    userMessage,
                    true
            );
        }

        Thread.startVirtualThread(() -> consumeAgentEvents(run));
        return run.snapshot();
    }

    RunSnapshot retryLastRun() {
        Run run;
        synchronized (lifecycleLock) {
            ensureIdle();
            if (conversation.size() == 0) {
                throw new IllegalStateException("没有可以重试的对话");
            }
            run = createRun(RunState.RUNNING, "retry");
        }

        Thread.startVirtualThread(() -> consumeAgentEvents(run));
        return run.snapshot();
    }

    RunSnapshot startManualCompaction() {
        Run run;
        synchronized (lifecycleLock) {
            ensureIdle();
            if (conversation.size() == 0) {
                throw new IllegalStateException("当前对话为空，无需压缩");
            }
            run = createRun(RunState.COMPACTING, "compact");
        }

        Thread.startVirtualThread(() -> compact(run));
        return run.snapshot();
    }

    private Run createRun(RunState state, String operation) {
        return createRun(state, operation, null, false);
    }

    private Run createRun(
            RunState state,
            String operation,
            Message turnStartMessage,
            boolean extractMemory
    ) {
        Run run = new Run(
                UUID.randomUUID().toString(),
                state,
                turnStartMessage,
                extractMemory
        );
        currentRun = run;
        run.append("run_started", Map.of(
                "operation", operation,
                "turn", 0
        ), false);
        return run;
    }

    private void consumeAgentEvents(Run run) {
        AgentEventQueue events = agent.run(conversation);
        int turn = 1;
        try {
            while (true) {
                AgentEvent event = events.take();
                int eventTurn = turn;

                switch (event) {
                    case AgentEvent.Text value ->
                            run.append("text", Map.of(
                                    "delta", safe(value.delta()),
                                    "turn", eventTurn
                            ), false);

                    case AgentEvent.ToolCall value ->
                            run.append("tool_call", mapOf(
                                    "id", value.id(),
                                    "name", value.name(),
                                    "args", value.args(),
                                    "turn", eventTurn
                            ), false);

                    case AgentEvent.ToolResult value ->
                            run.append("tool_result", mapOf(
                                    "id", value.id(),
                                    "output", value.output(),
                                    "error", value.error(),
                                    "turn", eventTurn
                            ), false);

                    case AgentEvent.TurnComplete value -> {
                        run.append("turn_complete", Map.of(
                                "turn", value.turn()
                        ), false);
                        turn = value.turn() + 1;
                    }

                    case AgentEvent.LoopComplete value -> {
                        run.append("loop_complete", Map.of(
                                "turns", value.turns(),
                                "turn", value.turns()
                        ), true);
                        persistAfterRun(run, true);
                        run.finish(RunState.COMPLETED);
                        return;
                    }

                    case AgentEvent.Error value -> {
                        run.append("error", Map.of(
                                "message", safe(value.message()),
                                "retryable", true,
                                "turn", eventTurn
                        ), true);
                        persistAfterRun(run, false);
                        run.finish(RunState.FAILED);
                        return;
                    }

                    case AgentEvent.Log value ->
                            run.append("log", Map.of(
                                    "message", safe(value.message()),
                                    "turn", eventTurn
                            ), false);

                    case AgentEvent.PermissionRequest value ->
                            registerPermission(run, value, eventTurn);

                    case AgentEvent.ContextUsage value -> {
                        Map<String, Object> context = mapOf(
                                "estimatedInputTokens", value.estimatedInputTokens(),
                                "inputLimit", value.inputLimit(),
                                "remainingInputTokens", value.remainingInputTokens(),
                                "shouldCompact", value.shouldCompact(),
                                "turn", eventTurn
                        );
                        lastContext = Map.copyOf(context);
                        run.append("context_usage", context, false);
                    }

                    case AgentEvent.ContextCompacted value ->
                            run.append("context_compacted", mapOf(
                                    "beforeMessages", value.beforeMessages(),
                                    "afterMessages", value.afterMessages(),
                                    "beforeTokens", value.beforeTokens(),
                                    "afterTokens", value.afterTokens(),
                                    "turn", eventTurn
                            ), false);

                    case AgentEvent.ContextCompactionStarted value -> {
                        run.setState(RunState.COMPACTING);
                        run.append("compaction_started", Map.of(
                                "previousFailures", value.previousFailures(),
                                "turn", eventTurn
                        ), false);
                    }

                    case AgentEvent.ContextCompactionFailed value -> {
                        run.append("compaction_failed", mapOf(
                                "message", value.message(),
                                "consecutiveFailures", value.consecutiveFailures(),
                                "turn", eventTurn
                        ), false);
                        run.setState(RunState.RUNNING);
                    }

                    case AgentEvent.ContextCompactionCircuitOpen value ->
                            run.append("compaction_circuit_opened", Map.of(
                                    "maxFailures", value.maxFailures(),
                                    "turn", eventTurn
                            ), false);

                    case AgentEvent.ToolResultOffloaded value ->
                            run.append("tool_result_offloaded", mapOf(
                                    "offloadedResults", value.offloadedResults(),
                                    "removedCharacters", value.removedCharacters(),
                                    "files", value.files(),
                                    "artifactPath", value.files().isEmpty()
                                            ? "" : value.files().getFirst(),
                                    "turn", eventTurn
                            ), false);
                }
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            run.append("error", Map.of(
                    "message", "Web run interrupted",
                    "retryable", true,
                    "turn", turn
            ), true);
            run.finish(RunState.FAILED);
        } catch (RuntimeException failure) {
            run.append("error", Map.of(
                    "message", rootMessage(failure),
                    "retryable", true,
                    "turn", turn
            ), true);
            persistAfterRun(run, false);
            run.finish(RunState.FAILED);
        }
    }

    private void compact(Run run) {
        run.append("compaction_started", Map.of("turn", 0), false);
        try {
            ContextCompactor.CompactionResult result = agent.compactNow(conversation);
            run.append("context_compacted", mapOf(
                    "beforeMessages", result.beforeMessages(),
                    "afterMessages", result.afterMessages(),
                    "beforeTokens", result.beforeTokens(),
                    "afterTokens", result.afterTokens(),
                    "turn", 0
            ), false);
            run.append("loop_complete", Map.of(
                    "turns", 0,
                    "turn", 0
            ), true);
            persistSessionBestEffort(run);
            run.finish(RunState.COMPLETED);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            failCompaction(run, "Context compaction interrupted");
        } catch (RuntimeException failure) {
            failCompaction(run, rootMessage(failure));
        }
    }

    private void failCompaction(Run run, String message) {
        run.append("compaction_failed", Map.of(
                "message", safe(message),
                "turn", 0
        ), false);
        run.append("error", Map.of(
                "message", safe(message),
                "retryable", false,
                "turn", 0
        ), true);
        run.finish(RunState.FAILED);
    }

    private void registerPermission(
            Run run,
            AgentEvent.PermissionRequest request,
            int turn
    ) {
        String requestId = UUID.randomUUID().toString();
        PendingPermission pending = new PendingPermission(
                requestId,
                run,
                request.future()
        );
        pendingPermissions.put(requestId, pending);
        run.setState(RunState.WAITING_PERMISSION);
        run.append("permission_request", mapOf(
                "requestId", requestId,
                "toolName", request.toolName(),
                "description", request.description(),
                "turn", turn
        ), true);
        request.future().whenComplete((ignored, error) ->
                pendingPermissions.remove(requestId));
    }

    void resolvePermission(String requestId, PermissionResponse response) {
        PendingPermission pending = pendingPermissions.remove(requestId);
        if (pending == null) {
            throw new IllegalArgumentException("权限请求已失效");
        }
        boolean completed = pending.future().complete(response);
        if (!completed) {
            throw new IllegalArgumentException("权限请求已处理");
        }
        pending.run().append("permission_resolved", Map.of(
                "requestId", requestId,
                "decision", response.name(),
                "turn", 0
        ), true);
        pending.run().setState(RunState.RUNNING);
    }

    int resetConversation() {
        synchronized (lifecycleLock) {
            ensureIdle();
            int removed = conversation.clear();
            agent.resetContextManagement();
            PermissionChecker checker = agent.getChecker();
            if (checker != null) {
                checker.clearAllowAlwaysRules();
            }
            pendingPermissions.clear();
            currentRun = null;
            lastContext = null;
            persistSessionBestEffort(null);
            return removed;
        }
    }

    RunSnapshot currentSnapshot() {
        Run run = currentRun;
        return run == null
                ? new RunSnapshot(null, RunState.IDLE, 0)
                : run.snapshot();
    }

    Map<String, Object> lastContext() {
        return lastContext;
    }

    List<WebEvent> eventsAfter(String runId, long sequence) {
        Run run = requireRun(runId);
        return run.eventsAfter(sequence);
    }

    boolean awaitEvent(String runId, long sequence, Duration timeout)
            throws InterruptedException {
        return requireRun(runId).awaitAfter(sequence, timeout);
    }

    boolean isTerminal(String runId) {
        return !requireRun(runId).state().active();
    }

    List<Map<String, Object>> conversationSnapshot() {
        List<Map<String, Object>> items = new ArrayList<>();
        for (Message message : conversation.getHistoryCopy()) {
            if (message == null) {
                continue;
            }
            if (message.getContent() != null && !message.getContent().isBlank()) {
                items.add(mapOf(
                        "type", safe(message.getRole()),
                        "content", message.getContent()
                ));
            }
            List<ToolCallBlock> calls = message.getToolCalls();
            if (calls != null) {
                for (ToolCallBlock call : calls) {
                    if (call != null) {
                        items.add(mapOf(
                                "type", "tool_call",
                                "id", call.toolId(),
                                "name", call.toolName(),
                                "args", call.params(),
                                "turn", 1
                        ));
                    }
                }
            }
            List<ToolResult> results = message.getToolResults();
            if (results != null) {
                for (ToolResult result : results) {
                    if (result != null) {
                        items.add(mapOf(
                                "type", "tool_result",
                                "id", result.toolId(),
                                "output", result.content(),
                                "error", result.isError(),
                                "turn", 1
                        ));
                    }
                }
            }
        }
        return List.copyOf(items);
    }

    private Run requireRun(String runId) {
        Run run = currentRun;
        if (run == null || runId == null || !run.id().equals(runId)) {
            throw new IllegalArgumentException("运行不存在或已过期");
        }
        return run;
    }

    private void ensureIdle() {
        Run run = currentRun;
        if (run != null && run.state().active()) {
            throw new IllegalStateException("上一项任务仍在运行");
        }
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }

    private static String rootMessage(Throwable error) {
        Throwable cause = error;
        while (cause.getCause() != null) {
            cause = cause.getCause();
        }
        return cause.getMessage() == null ? cause.toString() : cause.getMessage();
    }

    private static Map<String, Object> mapOf(Object... entries) {
        LinkedHashMap<String, Object> result = new LinkedHashMap<>();
        for (int index = 0; index < entries.length; index += 2) {
            String key = String.valueOf(entries[index]);
            Object value = entries[index + 1];
            result.put(key, value);
        }
        return result;
    }

    private final class Run {
        private final String id;
        private final List<WebEvent> events = new ArrayList<>();
        private RunState state;
        private long nextSequence = 1;
        private int eventBytes;
        private final Message turnStartMessage;
        private final boolean extractMemory;

        private Run(
                String id,
                RunState state,
                Message turnStartMessage,
                boolean extractMemory
        ) {
            this.id = id;
            this.state = state;
            this.turnStartMessage = turnStartMessage;
            this.extractMemory = extractMemory;
        }

        String id() {
            return id;
        }

        synchronized RunState state() {
            return state;
        }

        synchronized void setState(RunState state) {
            if (this.state.active()) {
                this.state = state;
                notifyAll();
            }
        }

        synchronized void finish(RunState terminalState) {
            state = terminalState;
            notifyAll();
        }

        synchronized RunSnapshot snapshot() {
            return new RunSnapshot(
                    id,
                    state,
                    events.isEmpty() ? 0 : events.getLast().sequence()
            );
        }

        synchronized void append(
                String type,
                Map<String, Object> fields,
                boolean protectedEvent
        ) {
            LinkedHashMap<String, Object> payload = new LinkedHashMap<>();
            payload.put("type", type);
            payload.put("runId", id);
            payload.put("sequence", nextSequence);
            payload.put("timestamp", Instant.now().toString());
            if (fields != null) {
                payload.putAll(fields);
            }

            String json;
            try {
                json = mapper.writeValueAsString(payload);
            } catch (JsonProcessingException error) {
                throw new IllegalStateException("无法序列化 Web 事件", error);
            }

            WebEvent event = new WebEvent(nextSequence++, type, json);
            events.add(event);
            eventBytes += json.length();
            trim(protectedEvent ? event.sequence() : -1);
            notifyAll();
        }

        private void trim(long justProtectedSequence) {
            while ((events.size() > MAX_EVENTS || eventBytes > MAX_EVENT_BYTES)
                    && events.size() > 1) {
                int removable = -1;
                for (int index = 0; index < events.size(); index++) {
                    WebEvent candidate = events.get(index);
                    if (candidate.sequence() == justProtectedSequence
                            || isProtectedType(candidate.type())) {
                        continue;
                    }
                    removable = index;
                    break;
                }
                if (removable < 0) {
                    return;
                }
                WebEvent removed = events.remove(removable);
                eventBytes -= removed.json().length();
            }
        }

        private boolean isProtectedType(String type) {
            return "permission_request".equals(type)
                    || "permission_resolved".equals(type)
                    || "loop_complete".equals(type)
                    || "error".equals(type);
        }

        synchronized List<WebEvent> eventsAfter(long sequence) {
            return events.stream()
                    .filter(event -> event.sequence() > sequence)
                    .toList();
        }

        synchronized boolean awaitAfter(long sequence, Duration timeout)
                throws InterruptedException {
            if (events.stream().anyMatch(event -> event.sequence() > sequence)
                    || !state.active()) {
                return true;
            }
            wait(timeout.toMillis());
            return events.stream().anyMatch(event -> event.sequence() > sequence)
                    || !state.active();
        }
    }

    private void prepareMemoryReminder(String query) {
        if (memoryService == null) {
            agent.setLongTermMemoryReminder("");
            return;
        }

        try {
            MemoryRecall.RecallResult recall = memoryService.recall(query);
            agent.setLongTermMemoryReminder(recall.reminder());
        } catch (IOException e) {
            agent.setLongTermMemoryReminder("");
            System.err.println("[Memory] Web 召回失败: " + e.getMessage());
        }
    }

    private void persistAfterRun(Run run, boolean successful) {
        persistSessionBestEffort(run);

        if (!successful
                || memoryService == null
                || run == null
                || !run.extractMemory
                || run.turnStartMessage == null) {
            return;
        }

        List<Message> turnMessages = messagesFromCurrentTurn(
                run.turnStartMessage
        );

        if (turnMessages.size() < 2) {
            return;
        }

        String sourceSessionId = sessionManager == null
                ? agent.getSessionId()
                : sessionManager.currentSessionId();

        memoryService.extractAsync(sourceSessionId, turnMessages);
    }

    private List<Message> messagesFromCurrentTurn(Message startMessage) {
        List<Message> history = conversation.getHistoryCopy();

        for (int index = 0; index < history.size(); index++) {
            if (history.get(index) == startMessage) {
                return List.copyOf(history.subList(index, history.size()));
            }
        }
        return List.of();
    }

    private void persistSessionBestEffort(Run run) {
        if (sessionManager == null) {
            return;
        }

        try {
            sessionManager.save();
        } catch (IOException | RuntimeException e) {
            String message = "Session 自动保存失败: " + rootMessage(e);
            System.err.println("[Session] " + message);

            if (run != null) {
                run.append("log", Map.of(
                        "message", message,
                        "turn", 0
                ), false);
            }
        }
    }
}
