package com.jcoder;


import com.jcoder.agent.Agent;
import com.jcoder.config.ConfigManager;
import com.jcoder.config.ProviderConfig;
import com.jcoder.llm.LLMClient;
import com.jcoder.message.ConversationManager;
import com.jcoder.tool.ToolRegister;
import com.jcoder.ui.UI;
import com.jcoder.ui.CmdUI;
import com.jcoder.ui.web.WebUI;

import java.net.http.HttpClient;

/**
 * 作者：亥子曜
 * -后之览者，亦将有感于斯文
 */
public class Main {
    public static void main(String[] args) {
        ProviderConfig providerConfig = ConfigManager.appConfig.providers().get(0);

        LLMClient client = LLMClient.create(HttpClient.newHttpClient(), providerConfig);

        if (client == null) {
            System.out.println("LLMClient创建失败");
            return;
        }

        ToolRegister toolRegister = ToolRegister.createDefault();

        ConversationManager conversationManager = new ConversationManager();

        Agent agent = new Agent(
                client,
                toolRegister,
                providerConfig.contextWindow(),
                providerConfig.maxOutputTokens()
        );
        agent.setWorkDir(System.getProperty("user.dir"));

        UI ui = chooseUi(args);
        ui.run(agent, conversationManager);
    }

    private static UI chooseUi(String[] args) {
        return new WebUI();
    }
}
