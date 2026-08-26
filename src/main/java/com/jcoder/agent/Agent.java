package com.jcoder.agent;

import com.jcoder.context.CompactionCircuitBreaker;
import com.jcoder.context.ContextBudget;
import com.jcoder.context.ContextCompactor;
import com.jcoder.context.ToolResultOffloader;
import com.jcoder.llm.LLMClient;
import com.jcoder.llm.model.StreamBlock;
import com.jcoder.message.ConversationManager;
import com.jcoder.message.Message;
import com.jcoder.message.ToolCallBlock;
import com.jcoder.message.ToolResult;
import com.jcoder.permission.PermissionChecker;
import com.jcoder.permission.PermissionResponse;
import com.jcoder.prompt.AgentMode;
import com.jcoder.prompt.EnvironmentContext;
import com.jcoder.prompt.PromptBuilder;
import com.jcoder.prompt.PromptContent;
import com.jcoder.run.TurnResult;
import com.jcoder.tool.Tool;
import com.jcoder.tool.ToolExecuteResult;
import com.jcoder.tool.ToolRegister;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * 作者：亥子曜
 * -后之览者，亦将有感于斯文
 */
public class Agent {

    private  LLMClient client;
    private final ToolRegister toolRegister;
    private final PromptBuilder promptBuilder = new PromptBuilder();
    private volatile PromptContent currentPromptContent;
    private volatile AgentMode mode = AgentMode.NORMAL;
    // UNUSED: 目前算是冗余字段
    private String protocol;

    private String workDir;
    private String sessionId;

    private final int contextWindow;
    private final int maxOutput;

    private int maxIterations;

    //TODO: 权限管理+Hook回调
    private PermissionChecker checker;
    // 记忆摘要压缩熔断器
    private final CompactionCircuitBreaker compactionCircuitBreaker =
            new CompactionCircuitBreaker();


    public Agent(LLMClient client, ToolRegister toolRegister,
                 int contextWindow, int maxOutput) {
        this.client = client;
        this.toolRegister = toolRegister;
        this.contextWindow = contextWindow;
        this.maxOutput = maxOutput;

        // CONST: 默认兜底
        this.maxIterations = 100;

    }



    public AgentEventQueue run(ConversationManager conversationManager) {

        // CONST: 这里目前是常量
        var queue = new AgentEventQueue(64);
        Thread.startVirtualThread(()->{
            try {
                agentLoop(conversationManager,queue);
            } catch (Exception e) {
                queue.putSafe(new AgentEvent.Error("Agent error"+ e.getMessage()));
            }
        });
        return queue;
    }

    public void agentLoop(ConversationManager conversationManager,AgentEventQueue queue) throws InterruptedException {
        // 注入记忆,还没写
        conversationManager.injectLongTermMemory();

        int totalInput = 0;
        int totalOutput = 0;


        for (int turn = 1; turn < this.maxIterations; turn++) {
            // 检查迭代上限 就在for里面检查,逻辑在for外


            //检查线程中断

            //消费通知队列

            //注入工具清单
            //plan model 注入

            // 单个结果压缩: 工具超50k压缩,一条结果超200k压缩
            Path contextProjectRoot = Path.of(workDir == null || workDir.isBlank()
                                        ? "." : workDir);
            ToolResultOffloader.OffloadReport offloadReport =
                    ToolResultOffloader.apply(conversationManager, contextProjectRoot);
            if (offloadReport.changed()) {
                queue.putSafe(new AgentEvent.Log(
                                "[context] offloaded "
                                        + offloadReport.offloadedResults()
                                        + " tool result(s), removed "
                                        + offloadReport.removedCharacters()
                                        + " characters, files="
                                        + offloadReport.files()
                        )
                );
            }

            //组装本轮完整 Prompt
            currentPromptContent = promptBuilder.build(
                    conversationManager,
                    toolRegister.listDefinitions(),
                    EnvironmentContext.detect(workDir),
                    mode,
                    turn
            );
            // 上下文计算
            ContextBudget contextBudget = ContextBudget.calculate(
                            currentPromptContent, contextWindow, maxOutput);

            queue.putSafe(new AgentEvent.ContextUsage(
                    contextBudget.estimatedInputTokens(), contextBudget.inputLimit(),
                    contextBudget.remainingInputTokens(), contextBudget.shouldCompact()));

            if (contextBudget.shouldCompact()) {

                if (!compactionCircuitBreaker.allowAutomaticAttempt()) {
                    queue.putSafe(
                            new AgentEvent.Log(
                                    "[context] automatic compaction is disabled "
                                            + "after "
                                            + compactionCircuitBreaker.maxFailures()
                                            + " consecutive failures; "
                                            + "use /compact to retry manually"
                            )
                    );
                }else {

                    try {
                        ContextCompactor.CompactionResult compactResult =
                                ContextCompactor.compact(conversationManager, client);

                        compactionCircuitBreaker.recordSuccess();

                        if (compactResult.compacted()) {
                            queue.putSafe(
                                    new AgentEvent.ContextCompacted(
                                            compactResult.beforeMessages(),
                                            compactResult.afterMessages(),
                                            compactResult.beforeTokens(),
                                            compactResult.afterTokens()
                                    )
                            );

                            /*
                             * Conversation 已经被替换，
                             * 必须重新构造本轮真实 Prompt。
                             */
                            currentPromptContent =
                                    promptBuilder.build(
                                            conversationManager,
                                            toolRegister.listDefinitions(),
                                            EnvironmentContext.detect(workDir),
                                            mode,
                                            turn
                                    );

                            contextBudget =
                                    ContextBudget.calculate(
                                            currentPromptContent,
                                            contextWindow,
                                            maxOutput
                                    );

                            queue.putSafe(
                                    new AgentEvent.ContextUsage(
                                            contextBudget.estimatedInputTokens(),
                                            contextBudget.inputLimit(),
                                            contextBudget.remainingInputTokens(),
                                            contextBudget.shouldCompact()
                                    )
                            );
                        }

                    } catch (InterruptedException e) {
                        throw e;
                    } catch (Exception e) {
                        compactionCircuitBreaker.recordFailure();

                        queue.putSafe(
                                new AgentEvent.Log(
                                        "[context] compaction failed ("
                                                + compactionCircuitBreaker
                                                .consecutiveFailures()
                                                + "/"
                                                + compactionCircuitBreaker
                                                .maxFailures()
                                                + "): "
                                                + e.getMessage()
                                )
                        );


                        if (compactionCircuitBreaker.isOpen()) {
                            queue.putSafe(
                                    new AgentEvent.Log(
                                            "[context] automatic compaction has been "
                                                    + "disabled; use /compact to retry"
                                    )
                            );
                        }
                    }
                }
            }

            // 当前上下文使用量
            if (contextBudget.exceedsWindow()) {
                queue.putSafe(
                        new AgentEvent.Error(
                                "Context window exhausted: "
                                        + "estimated input="
                                        + contextBudget.estimatedInputTokens()
                                        + ", input limit="
                                        + contextBudget.inputLimit()
                        )
                );
                return;
            }
            //消费式流响应
            TurnResult result = executeOneTurn(
                    client, currentPromptContent, queue, turn);
            // NOTE: 适当使用断言,之前我们的逻辑保证了只会传过来空字符串,绝对不会是null
            // 我们在这里断言一下,避免以后代码更改破坏这条规则
            assert result.content() != null;
            Message assistantMessage = new Message("assistant", result.content());
            //错误恢复

            // max_tokens恢复

            // 保存 assistant消息

            // 没有工具调用->结束
            if(result.toolCalls().isEmpty()) {
                if (result.content() != null&& !result.content().isEmpty()) {
                    conversationManager.addMessage(assistantMessage);
                }
                queue.putSafe(new AgentEvent.LoopComplete(turn));
                return;
            }
            //有工具,执行工具
            assistantMessage.setToolCalls(result.toolCalls());
            conversationManager.addMessage(assistantMessage);
            List<ToolResult> toolResults = new ArrayList<>();

            for (ToolCallBlock call : result.toolCalls()) {

                queue.putSafe(new AgentEvent.Log("[tool] call " + call.toolName() + " args=" + call.params()));

                Tool tool = toolRegister.get(call.toolName());
                ToolExecuteResult executeResult;
                if (tool == null) {
                    executeResult = ToolExecuteResult.error("Unknown tool: " + call.toolName());
                } else {
                    executeResult = executeWithPermission(tool, call.params(), queue);
                }

                queue.putSafe(new AgentEvent.Log("[tool] finished " + call.toolName() + " error="
                        + executeResult.isError() + " outputChars=" + executeResult.output().length()));

                toolResults.add(new ToolResult(call.toolId(), executeResult.output(),
                        executeResult.isError())
                );
                queue.putSafe(new AgentEvent.ToolResult(call.toolId(), executeResult.output(),
                        executeResult.isError()));
            }
            conversationManager.addToolResultsMsg(toolResults);
            // TODO: 这里需要打上日志
            queue.putSafe(new AgentEvent.Log("[tool] results added, requesting model again. Now is "
                    +turn+" , Next is "+(turn+1)+" . Running...  "));


        }
        queue.putSafe(new AgentEvent.Error("Agent reach maximum iterations (%d)".formatted(maxIterations)));

    }

    private static TurnResult executeOneTurn(LLMClient client, PromptContent promptContent,
                                             AgentEventQueue eventQueue,int turn) throws InterruptedException {
        BlockingQueue<StreamBlock> queue = client.stream(promptContent);
        StringBuilder answer = new StringBuilder();
        List<ToolCallBlock> toolCalls = new ArrayList<>();
        while (true) {
            StreamBlock block = queue.take();

            switch (block) {
                case StreamBlock.ContentDelta contentDelta -> {
                    String text = contentDelta.content();

                    if (text != null && !text.isEmpty()) {
                        eventQueue.putSafe(new AgentEvent.Text(text));
                        answer.append(text);
                    }
                }

                case StreamBlock.ToolCall toolCall -> {
                    var call = new ToolCallBlock(
                            toolCall.toolId(),
                            toolCall.type(),
                            toolCall.toolName(),
                            toolCall.arguments()
                    );

                    toolCalls.add(call);

                    eventQueue.putSafe(new AgentEvent.ToolCall(call.toolId(),
                            call.toolName(),call.params()));
                }

                case StreamBlock.StreamError error -> {
                    eventQueue.putSafe(new AgentEvent.Error("error in turn:"+turn+". "+error.msg()));
                    return new TurnResult("", List.of());
                }

                case StreamBlock.StreamEnd end -> {
                    eventQueue.putSafe(new AgentEvent.TurnComplete(turn));
                    return new TurnResult(
                            answer.toString(),
                            toolCalls
                    );
                }

                default -> {
                    // 当前暂时忽略 ToolCallDelta 等事件
                }
            }
        }

    }
    private ToolExecuteResult executeWithPermission(Tool tool, Map<String, Object> args,
                                                    AgentEventQueue queue) {
        if (checker == null) {                       // 没装配 checker 就直通（向后兼容）
            return tool.execute(args);
        }

        var check = checker.check(tool, args);
        switch (check.decision()) {
            case DENY -> {
                return ToolExecuteResult.error("Permission denied: " + check.reason());
            }
            case ALLOW -> {
                return tool.execute(args);
            }
            case ASK -> {
                // 发事件给 UI，并阻塞等用户回答（Agent 跑在虚拟线程，不卡 UI）
                var future = new CompletableFuture<PermissionResponse>();
                String desc = checker.describeToolAction(tool.name(), args);
                queue.putSafe(new AgentEvent.PermissionRequest(tool.name(), desc, future));

                PermissionResponse resp;
                try {
                    resp = future.get(5, TimeUnit.MINUTES);   // 超时默认拒绝
                } catch (Exception e) {
                    resp = PermissionResponse.DENY;
                }

                if (resp == PermissionResponse.DENY) {
                    return ToolExecuteResult.error("User denied permission");
                }
                if (resp == PermissionResponse.ALLOW_ALWAYS) {
                    checker.addAllowAlwaysRule(tool, args);
                }
                return tool.execute(args);
            }
        }
        return tool.execute(args); // 编译器兜底，实际不会走到
    }


    /**
     * 用户主动执行压缩。
     *
     * 手动命令表示用户明确要求重试，
     * 因此先重置自动摘要熔断器。
     */
    public ContextCompactor.CompactionResult compactNow(
            ConversationManager conversationManager
    ) throws InterruptedException {

        if (conversationManager == null) {
            throw new IllegalArgumentException(
                    "conversationManager must not be null"
            );
        }

        compactionCircuitBreaker.reset();

        try {
            ContextCompactor.CompactionResult result =
                    ContextCompactor.compact(
                            conversationManager,
                            client
                    );

            compactionCircuitBreaker.recordSuccess();
            return result;

        } catch (InterruptedException | RuntimeException e) {
            /*
             * reset 后本次仍然失败，
             * 新一轮连续失败次数从 1 开始。
             */
            compactionCircuitBreaker.recordFailure();
            throw e;
        }
    }

    /**
     * 只给同包测试观察状态，不作为 CLI 业务接口。
     */
    CompactionCircuitBreaker compactionCircuitBreaker() {
        return compactionCircuitBreaker;
    }

    public void setChecker(PermissionChecker checker) { this.checker = checker; }
    public PermissionChecker getChecker() { return checker; }

    public String getWorkDir() {
        return workDir;
    }

    public void setWorkDir(String workDir) {
        this.workDir = workDir;
    }

    public String getSessionId() {
        return sessionId;
    }

    public void setSessionId(String sessionId) {
        this.sessionId = sessionId;
    }

    public int getMaxIterations() {
        return maxIterations;
    }

    public PromptContent getCurrentPromptContent() {
        return currentPromptContent;
    }

    public String getLastRequestJson() {
        return client.getLastRequestJson();
    }

    public AgentMode getMode() {
        return mode;
    }

    public void setMode(AgentMode mode) {
        this.mode = mode;
    }

    public void setMaxIterations(int maxIterations) {
        if (maxIterations < 1 ) {
            // CONST: default值
            this.maxIterations = 30;
             return;
        }
        this.maxIterations = maxIterations;
    }
}
