package com.jcoder.run;

import com.jcoder.config.ConfigManager;
import com.jcoder.config.ProviderConfig;
import com.jcoder.llm.LLMClient;
import com.jcoder.llm.RequestBodyHelper;
import com.jcoder.llm.model.StreamBlock;
import com.jcoder.message.ConversationManager;
import com.jcoder.message.Message;
import com.jcoder.message.ToolCallBlock;
import com.jcoder.message.ToolResult;
import com.jcoder.tool.Tool;
import com.jcoder.tool.ToolExecuteResult;
import com.jcoder.tool.ToolRegister;

import java.net.http.HttpClient;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;

/** 可被命令行和 Web UI 复用的 Agent 执行核心。 */
public final class CodingAgent {
    private static final int MAX_AUTO_PROCEED = 20;

    private final String systemPrompt;
    private final LLMClient client;
    private final ToolRegister tools;
    private final ConversationManager conversation = new ConversationManager();

    public CodingAgent() {
        ProviderConfig provider = ConfigManager.appConfig.providers().get(0);
        this.systemPrompt = ConfigManager.appConfig.prompt().system_prompt();
        this.client = LLMClient.create(HttpClient.newHttpClient(), provider, systemPrompt);
        this.tools = ToolRegister.createDefault();
    }

    /** 同一实例串行处理请求，避免一段会话的消息互相穿插。 */
    public synchronized void chat(String prompt, AgentEventListener listener) throws InterruptedException {
        if (prompt == null || prompt.isBlank()) {
            throw new IllegalArgumentException("消息不能为空");
        }
        conversation.addUserMsg(prompt.trim());
        RequestBodyHelper body = new RequestBodyHelper(conversation, systemPrompt, tools.listDefinitions());

        for (int round = 0; round < MAX_AUTO_PROCEED; round++) {
            listener.onStatus(round == 0 ? "正在思考" : "正在根据工具结果继续处理");
            TurnResult result = executeOneTurn(body, listener);
            Message assistant = new Message("assistant", result.content());

            if (result.toolCalls().isEmpty()) {
                conversation.addMessage(assistant);
                listener.onDone();
                return;
            }

            assistant.setToolCalls(result.toolCalls());
            conversation.addMessage(assistant);
            List<ToolResult> results = new ArrayList<>();
            for (ToolCallBlock call : result.toolCalls()) {
                listener.onToolStart(call.toolName(), call.params());
                Tool tool = tools.get(call.toolName());
                ToolExecuteResult executed = tool == null
                        ? ToolExecuteResult.error("Unknown tool: " + call.toolName())
                        : tool.execute(call.params());
                listener.onToolEnd(call.toolName(), executed.output(), executed.isError());
                results.add(new ToolResult(call.toolId(), executed.output(), executed.isError()));
            }
            conversation.addToolResultsMsg(results);
        }
        throw new IllegalStateException("已达到最大自动执行轮数 " + MAX_AUTO_PROCEED);
    }

    public synchronized void clear() {
        conversation.clear();
    }

    private TurnResult executeOneTurn(RequestBodyHelper body, AgentEventListener listener) throws InterruptedException {
        BlockingQueue<StreamBlock> queue = client.stream(body);
        StringBuilder answer = new StringBuilder();
        List<ToolCallBlock> calls = new ArrayList<>();
        while (true) {
            StreamBlock block = queue.take();
            if (block instanceof StreamBlock.ContentDelta delta) {
                if (delta.content() != null && !delta.content().isEmpty()) {
                    answer.append(delta.content());
                    listener.onContent(delta.content());
                }
            } else if (block instanceof StreamBlock.ToolCallComplete call) {
                calls.add(new ToolCallBlock(call.toolId(), call.type(), call.toolName(), call.arguments()));
            } else if (block instanceof StreamBlock.StreamError error) {
                throw new IllegalStateException(error.msg());
            } else if (block instanceof StreamBlock.StreamEnd) {
                return new TurnResult(answer.toString(), calls);
            }
        }
    }
}
