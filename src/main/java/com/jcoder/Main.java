package com.jcoder;



import com.jcoder.config.ConfigManager;
import com.jcoder.config.ProviderConfig;
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

import java.net.http.HttpClient;
import java.util.ArrayList;
import java.util.List;
import java.util.Scanner;
import java.util.concurrent.BlockingQueue;

/**
 * 作者：亥子曜
 * -后之览者，亦将有感于斯文
 */
public class Main {

    static final Integer MAX_AUTO_PROCEED = 20;

    public static void main(String[] args) {

        ProviderConfig providerConfig = ConfigManager.appConfig.providers().get(0);
        String systemPrompt = ConfigManager.appConfig.prompt().system_prompt();

        LLMClient client = LLMClient.create(HttpClient.newHttpClient(),providerConfig,systemPrompt);

        if (client == null) {
            System.out.println("LLMClient创建失败");
            return;
        }

        ToolRegister toolRegister = ToolRegister.createDefault();

        ConversationManager conversationManager = new ConversationManager();
        try(Scanner scanner = new Scanner(System.in)) {
            System.out.println("AI 对话启动");
            System.out.println("输出 exit 或者 quit 退出");
            System.out.println();

            while (true) {
                System.out.print("> ");
                if (!scanner.hasNextLine()) {
                    break;
                }
                String prompt = scanner.nextLine().trim();
                if (prompt.equalsIgnoreCase("exit") || prompt.equalsIgnoreCase("quit")) {
                    break;
                }
                if (prompt.isBlank()) {
                    continue;
                }

                // 处理逻辑
                conversationManager.addUserMsg(prompt);


                RequestBodyHelper requestBodyHelper = new RequestBodyHelper(
                        conversationManager, systemPrompt, toolRegister.listDefinitions()
                );

                int goal = 1;
                for (int i = 0; i < goal; i++) {
                    if (goal >= MAX_AUTO_PROCEED) {
                        break;
                    }

                    TurnResult result = executeOneTurn(client, requestBodyHelper);

                    // TODO: 适当使用断言,之前我们的逻辑保证了只会传过来空字符串,绝对不会是null
                    // 我们在这里断言一下,避免以后代码更改破坏这条规则
                    assert result.content() != null;
                    Message assistantMessage = new Message("assistant", result.content());


                    if (!result.toolCalls().isEmpty()) {
                        assistantMessage.setToolCalls(result.toolCalls());
                        conversationManager.addMessage(assistantMessage);
                        List<ToolResult> toolResults = new ArrayList<>();

                        for (ToolCallBlock call : result.toolCalls()) {
                            // TODO 打个日志
                            System.err.println("[tool] call " + call.toolName() + " args=" + call.params());

                            Tool tool = toolRegister.get(call.toolName());
                            ToolExecuteResult executeResult;
                            if (tool == null) {
                                executeResult = ToolExecuteResult.error("Unknown tool: " + call.toolName());
                            } else {
                                executeResult = tool.execute(call.params());
                            }
                            // TODO: 再打个日志
                            System.err.println("[tool] finished " + call.toolName() + " error="
                                            + executeResult.isError() + " outputChars=" + executeResult.output().length());

                            toolResults.add(new ToolResult(call.toolId(), executeResult.output(),
                                    executeResult.isError())
                            );
                        }

                        conversationManager.addToolResultsMsg(toolResults);
                        // TODO: 这里需要打上日志
                        System.err.println("[tool] results added, requesting model again. Now is "
                                +goal+" , Next is "+(goal+1)+" . Running...  ");
                        goal++;
                    }else{
                        conversationManager.addMessage(assistantMessage);
                    }
                    System.out.println();
                }
            }
        }catch (Exception e){
            e.printStackTrace();
        }
    }


    private static TurnResult executeOneTurn(LLMClient client, RequestBodyHelper requestBodyHelper) throws InterruptedException {
        BlockingQueue<StreamBlock> queue  = client.stream(requestBodyHelper);
        StringBuilder answer = new StringBuilder();
        List<ToolCallBlock> toolCalls = new ArrayList<>();
        while (true) {
            StreamBlock block = queue.take();

            switch (block) {
                case StreamBlock.ContentDelta contentDelta -> {
                    String text = contentDelta.content();

                    if (text != null && !text.isEmpty()) {
                        System.out.print(text);
                        System.out.flush();
                        answer.append(text);
                    }
                }

                case StreamBlock.ToolCallComplete toolCallComplete -> {
                    toolCalls.add(
                            new ToolCallBlock(
                                    toolCallComplete.toolId(),
                                    toolCallComplete.type(),
                                    toolCallComplete.toolName(),
                                    toolCallComplete.arguments()
                            )
                    );
                }

                case StreamBlock.StreamError error -> {
                    System.err.println("Error: " + error.msg());
                    return new TurnResult("", List.of());
                }

                case StreamBlock.StreamEnd end -> {
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
}

