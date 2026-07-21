package com.hzy;

import com.hzy.config.AppConfig;
import com.hzy.config.ConfigManager;
import com.hzy.config.ProviderConfig;
import com.hzy.conversation.ConversationManager;
import com.hzy.llm.LLMClient;
import com.hzy.llm.LLMRequestBody;
import com.hzy.llm.StreamEvent;

import java.net.http.HttpClient;
import java.util.List;
import java.util.Scanner;
import java.util.concurrent.BlockingQueue;

/**
 * 作者：亥子曜
 * -后之览者，亦将有感于斯文
 */
public class Main {
    public static void main(String[] args) {

        ProviderConfig provider = providerConfig();
        String systemPrompt = systemPrompt();
        LLMClient client = LLMClient.create(HttpClient.newHttpClient(), provider, systemPrompt);

        if (client == null) {
            System.err.println("创建 LLMClient 失败");
            return;
        }
        int maxOutputTokens = provider.maxOutputTokens() == null ? 4096 : provider.maxOutputTokens();

        ConversationManager conversation = new ConversationManager();

        try(Scanner scanner = new Scanner(System.in)) {
            System.out.println("AI 对话已启动");
            System.out.println("输入 exit 或 quit 退出");
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
                if (prompt.isBlank()) {continue;}
                conversation.addUserMessage(prompt);
                LLMRequestBody requestBody = new LLMRequestBody(
                                systemPrompt,
                                conversation,
                                List.of(),
                                maxOutputTokens,
                                provider.thinking());

                String answer = executeOneTurn(client, requestBody);

                if (answer != null) {
                    conversation.addAssistantMessage(answer);
                }

                System.out.println();

            }
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }

    }



    private static String executeOneTurn(LLMClient client,LLMRequestBody requestBody) throws InterruptedException {
        BlockingQueue<StreamEvent> queue  = client.stream(requestBody);
        StringBuilder answer = new StringBuilder();
        while (true) {
            StreamEvent event = queue.take();

            if (event instanceof StreamEvent.TextDelta textDelta) {
                String text = textDelta.text();

                if (text != null && !text.isEmpty()) {
                    System.out.print(text);
                    System.out.flush();
                    answer.append(text);
                }

            } else if (event instanceof StreamEvent.Error error) {
                System.err.println();
                System.err.println(
                        "Error: " + error.message()
                );
                return null;

            } else if (event instanceof StreamEvent.StreamEnd) {
                return answer.toString();
            }
        }

    }

    public static ProviderConfig providerConfig(){
        AppConfig appConfig = ConfigManager.appConfig;
        if (appConfig == null || appConfig.providers() == null || appConfig.providers().isEmpty()) {
            System.err.println("没有找到 provider 配置");
            return null;
        }
        ProviderConfig provider = appConfig.providers().get(0);
        if (provider.protocol() == null || provider.protocol().isBlank()) {
            System.err.println("provider.protocol 未配置，例如：gpt");
            return null;
        }
        return provider;
    }
    public static String systemPrompt(){
        return """
                你是一个有帮助的 AI 助手。
                请使用中文回答用户的问题。
                """;
    }
}