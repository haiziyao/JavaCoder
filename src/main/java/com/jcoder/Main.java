package com.jcoder;



import com.jcoder.config.ConfigManager;
import com.jcoder.config.ProviderConfig;
import com.jcoder.llm.LLMClient;
import com.jcoder.llm.model.RequestBodyHelper;
import com.jcoder.llm.model.StreamBlock;
import com.jcoder.message.ConversationManager;

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

        ProviderConfig providerConfig = ConfigManager.appConfig.providers().get(0);
        String systemPrompt = ConfigManager.appConfig.prompt().system_prompt();

        LLMClient client = LLMClient.create(HttpClient.newHttpClient(),providerConfig,systemPrompt);

        if (client == null) {
            System.out.println("LLMClient创建失败");
            return;
        }

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
                if (prompt.isBlank()) {continue;}

                // 处理逻辑
                conversationManager.addUserMsg(prompt);

                RequestBodyHelper requestBodyHelper = new RequestBodyHelper(
                        conversationManager,systemPrompt,List.of()
                );


                String answer = executeOneTurn(client, requestBodyHelper);

                if (answer != null) {
                    conversationManager.addAssistantMsg(answer);
                }

                System.out.println();

            }
        }catch (Exception e){
            e.printStackTrace();
        }
    }


    private static String executeOneTurn(LLMClient client, RequestBodyHelper requestBodyHelper) throws InterruptedException {
        BlockingQueue<StreamBlock> queue  = client.stream(requestBodyHelper);
        StringBuilder answer = new StringBuilder();
        while (true) {
            StreamBlock block = queue.take();

            if (block instanceof StreamBlock.ContentDelta contentDelta) {
                String text = contentDelta.content();

                if (text != null && !text.isEmpty()) {
                    System.out.print(text);
                    System.out.flush();
                    answer.append(text);
                }

            } else if (block instanceof StreamBlock.StreamError error) {
                System.err.println();
                System.err.println(
                        "Error: " + error.msg()
                );
                return null;

            } else if (block instanceof StreamBlock.StreamEnd end) {
                return answer.toString();
            }
        }

    }
}

