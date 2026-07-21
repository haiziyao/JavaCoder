package com.hzy.llm;

import com.hzy.config.ProviderConfig;

import java.net.http.HttpClient;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * 作者：亥子曜
 * -后之览者，亦将有感于斯文
 */
public interface LLMClient {


    void doStream(LLMRequestBody requestBody, BlockingQueue<StreamEvent> queue) throws Exception;

    default BlockingQueue<StreamEvent> stream(LLMRequestBody requestBody){
        var queue = new LinkedBlockingQueue<StreamEvent>();
        Thread.startVirtualThread(()->{
            try {
                doStream(requestBody,queue);
            } catch (Exception e) {
                queue.add(new StreamEvent.Error(e.getMessage()));
            }
        });
        return queue;
    };


    static LLMClient create(HttpClient httpClient, ProviderConfig providerConfig, String systemPrompt) {
        if (providerConfig == null) {
            return null;
        }

        return switch (providerConfig.protocol()){
                    case "gpt"-> new OpenAIClient(httpClient,providerConfig,systemPrompt);
                    default -> throw new RuntimeException("unsupported");
                };
    }

}
