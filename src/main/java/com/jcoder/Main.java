package com.jcoder;



import com.jcoder.agent.Agent;
import com.jcoder.agent.AgentEvent;
import com.jcoder.agent.AgentEventQueue;
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
import com.jcoder.ui.CmdUI;

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

    public static void main(String[] args) {
        ProviderConfig providerConfig = ConfigManager.appConfig.providers().get(0);
        String systemPrompt = ConfigManager.appConfig.prompt().system_prompt();

        LLMClient client = LLMClient.create(HttpClient.newHttpClient(),providerConfig);

        if (client == null) {
            System.out.println("LLMClient创建失败");
            return;
        }

        ToolRegister toolRegister = ToolRegister.createDefault();

        ConversationManager conversationManager = new ConversationManager();

        Agent agent = new Agent(client,toolRegister,systemPrompt,providerConfig.contextWindow(),providerConfig.maxOutputTokens());

        new CmdUI().run(agent,conversationManager);

    }
}

