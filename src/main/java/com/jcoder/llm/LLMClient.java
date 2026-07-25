package com.jcoder.llm;

import com.jcoder.config.ConfigManager;
import com.jcoder.config.ProviderConfig;
import com.jcoder.llm.model.RequestBodyHelper;
import com.jcoder.llm.model.ResponseBody;
import com.jcoder.llm.model.StreamBlock;

import java.net.http.HttpClient;
import java.util.concurrent.BlockingQueue;

/**
 * 作者：亥子曜
 * -后之览者，亦将有感于斯文
 */
public interface LLMClient {

    public BlockingQueue<StreamBlock> stream(RequestBodyHelper requestBodyHelper);
    public ResponseBody request(RequestBodyHelper requestBodyHelper);


    static LLMClient create(HttpClient httpClient, ProviderConfig providerConfig, String systemPrompt){
        String protocol = providerConfig.protocol();
        return switch (protocol){
            case "gpt" ->new OpenAIClient(httpClient, providerConfig, systemPrompt);
            default -> throw new IllegalStateException("Unexpected value: " + protocol);
        };
    }

}
