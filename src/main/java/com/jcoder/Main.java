package com.jcoder;


import com.jcoder.agent.Agent;
import com.jcoder.config.ConfigManager;
import com.jcoder.config.McpServerConfig;
import com.jcoder.config.ProviderConfig;
import com.jcoder.llm.LLMClient;
import com.jcoder.mcp.McpManager;
import com.jcoder.message.ConversationManager;
import com.jcoder.permission.PermissionChecker;
import com.jcoder.permission.PermissionMode;
import com.jcoder.tool.ToolRegister;
import com.jcoder.ui.WebUI;
import com.jcoder.ui.UI;

import java.net.http.HttpClient;
import java.nio.file.Path;
import java.util.List;

/**
 * 作者：亥子曜
 * -后之览者，亦将有感于斯文
 */
public class Main {
    public static void main(String[] args) {
        ProviderConfig providerConfig = ConfigManager.appConfig.providers().get(0);

        LLMClient client = LLMClient.create(HttpClient.newHttpClient(),providerConfig);

        if (client == null) {
            System.out.println("LLMClient创建失败");
            return;
        }

        ToolRegister toolRegister = ToolRegister.createDefault();
        int builtInToolCount =
                toolRegister.listTools().size();

        List<McpServerConfig> mcpConfigs =
                ConfigManager.appConfig.mcpServers() == null ? List.of() : ConfigManager.appConfig.mcpServers();
        McpManager mcpManager = new McpManager(mcpConfigs);
        List<String> mcpErrors = mcpManager.connectAndRegister(toolRegister);

        int registeredMcpToolCount = toolRegister.listTools().size() - builtInToolCount;

        if (registeredMcpToolCount > 0) {
            System.out.println("[MCP] 已连接 Server: " + mcpManager.connectedServerCount());

            System.out.println("[MCP] 已注册工具: " + registeredMcpToolCount);
        }

        if (!mcpErrors.isEmpty()) {
            for (String error : mcpErrors) {
                System.err.println("[MCP] " + error);
            }
        }
        // JVM 退出时优雅关闭 stdio 子进程
        Runtime.getRuntime().addShutdownHook(
                new Thread(mcpManager::shutdown,"mcp-shutdown-thread"));

        ConversationManager conversationManager = new ConversationManager();

        Agent agent = new Agent(
                client,
                toolRegister,
                providerConfig.contextWindow(),
                providerConfig.maxOutputTokens()
        );

        agent.setChecker(new PermissionChecker(
                PermissionMode.DEFAULT,
                Path.of("").toAbsolutePath()   // 项目根 = 当前工作目录
        ));

        UI ui = new WebUI();
        ui.run(agent, conversationManager);

    }
}

