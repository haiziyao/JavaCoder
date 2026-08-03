package com.jcoder.agent;

import com.jcoder.llm.LLMClient;
import com.jcoder.llm.RequestBodyHelper;
import com.jcoder.llm.model.StreamBlock;
import com.jcoder.message.ConversationManager;
import com.jcoder.message.Message;
import com.jcoder.message.ToolCallBlock;
import com.jcoder.message.ToolResult;
import com.jcoder.run.TurnResult;
import com.jcoder.tool.Tool;
import com.jcoder.tool.ToolExecuteResult;
import com.jcoder.tool.ToolRegister;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;

/**
 * 作者：亥子曜
 * -后之览者，亦将有感于斯文
 */
public class Agent {

    private  LLMClient client;
    private final ToolRegister toolRegister;
    private String systemPrompt;
    // UNUSED: 目前算是冗余字段
    private String protocol;

    private String workDir;
    private String sessionId;

    private final int contextWindow;
    private final int maxOutput;

    private int maxIterations;

    //TODO: 权限管理+Hook回调


    public Agent(LLMClient client, ToolRegister toolRegister,String systemPrompt,int contextWindow, int maxOutput) {
        this.client = client;
        this.toolRegister = toolRegister;
        this.systemPrompt = systemPrompt;
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
        RequestBodyHelper requestBodyHelper = new RequestBodyHelper(conversationManager,
                this.systemPrompt,null);

        int totalInput = 0;
        int totalOutput = 0;


        for (int turn = 1; turn < this.maxIterations; turn++) {
            // 检查迭代上限 就在for里面检查,逻辑在for外


            //检查线程中断

            //消费通知队列

            //自动上下文压缩

            //注入工具清单


            //plan model 注入

            //获取工具 schema,调用llm
            requestBodyHelper.setTools(toolRegister.listDefinitions());
            //消费式流响应
            TurnResult result = executeOneTurn(client, requestBodyHelper,queue,turn);
            // NOTE: 适当使用断言,之前我们的逻辑保证了只会传过来空字符串,绝对不会是null
            // 我们在这里断言一下,避免以后代码更改破坏这条规则
            assert result.content() != null;
            Message assistantMessage = new Message("assistant", result.content());
            //错误恢复

            // max_tokens恢复

            // 保存 assistant消息

            // 没有工具调用->结束
            if(result.toolCalls().isEmpty()) {
                conversationManager.addMessage(assistantMessage);
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
                    executeResult = tool.execute(call.params());
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

    private static TurnResult executeOneTurn(LLMClient client, RequestBodyHelper requestBodyHelper,
                                             AgentEventQueue eventQueue,int turn) throws InterruptedException {
        BlockingQueue<StreamBlock> queue  = client.stream(requestBodyHelper);
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

    public void setMaxIterations(int maxIterations) {
        if (maxIterations < 1 ) {
            // CONST: default值
            this.maxIterations = 30;
             return;
        }
        this.maxIterations = maxIterations;
    }
}
