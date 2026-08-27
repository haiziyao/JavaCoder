package com.jcoder;


import com.jcoder.agent.Agent;
import com.jcoder.config.ConfigManager;
import com.jcoder.config.McpServerConfig;
import com.jcoder.config.ProviderConfig;
import com.jcoder.llm.LLMClient;
import com.jcoder.hook.controller.ToolHookController;
import com.jcoder.hook.dispatcher.HookDispatcher;
import com.jcoder.hook.executor.HookExecutorRegistry;
import com.jcoder.mcp.McpManager;
import com.jcoder.memory.MemoryExtractor;
import com.jcoder.memory.MemoryPolicy;
import com.jcoder.memory.MemoryService;
import com.jcoder.memory.MemoryStore;
import com.jcoder.message.ConversationManager;
import com.jcoder.permission.PermissionChecker;
import com.jcoder.permission.PermissionMode;
import com.jcoder.session.SessionManager;
import com.jcoder.session.SessionStore;
import com.jcoder.skill.SkillCatalog;
import com.jcoder.skill.SkillRuntime;
import com.jcoder.tool.ToolRegister;
import com.jcoder.tool.impl.LoadSkillTool;
import com.jcoder.ui.CmdUI;
import com.jcoder.ui.UI;
import com.jcoder.ui.WebUI;

import java.net.http.HttpClient;
import java.nio.file.Path;
import java.util.List;

/**
 * 作者：亥子曜
 * -后之览者，亦将有感于斯文
 */
public class Main {
    public static void main(String[] args) {
        LaunchOptions options;
        try {
            options = LaunchOptions.parse(args);
        } catch (IllegalArgumentException error) {
            System.err.println("参数错误: " + error.getMessage());
            System.err.println("用法: java -jar MyCoder.jar [--web] [--port <1-65535>] | --cli");
            return;
        }

        ProviderConfig providerConfig = ConfigManager.appConfig.providers().get(0);

        LLMClient client = LLMClient.create(HttpClient.newHttpClient(),providerConfig);

        if (client == null) {
            System.out.println("LLMClient创建失败");
            return;
        }
        Path projectRoot = Path.of("").toAbsolutePath().normalize();


        ToolRegister toolRegister = ToolRegister.createDefault();
        SkillCatalog skillCatalog = SkillCatalog.load(projectRoot);
        SkillRuntime skillRuntime = new SkillRuntime(skillCatalog);

        toolRegister.register(new LoadSkillTool(skillRuntime));
        int builtInToolCount = toolRegister.listTools().size();

        if (skillCatalog.size() > 0) {
            System.out.println("[Skill] 已发现 Skill: " + skillCatalog.size());
        }

        for (String error : skillCatalog.loadErrors()) {
            System.err.println("[Skill] " + error);
        }

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
        agent.setSkillRuntime(skillRuntime);
        agent.setWorkDir(projectRoot.toString());
        agent.setChecker(new PermissionChecker(PermissionMode.DEFAULT, projectRoot));

        HookDispatcher hookDispatcher =
                new HookDispatcher(
                        HookExecutorRegistry.createDefault(),
                        List.of()
                );

        agent.setToolHookController(
                new ToolHookController(
                        hookDispatcher
                )
        );

        MemoryService memoryService = new MemoryService(
                new MemoryStore(projectRoot),
                new MemoryPolicy(),
                new MemoryExtractor(client)
        );

        SessionManager sessionManager = new SessionManager(
                new SessionStore(projectRoot),
                conversationManager,
                agent
        );

        UI ui = options.cli()
                ? new CmdUI(sessionManager, memoryService)
                : new WebUI(
                options.port(),
                providerConfig,
                mcpManager.connectedServerCount(),
                registeredMcpToolCount,
                mcpErrors,
                sessionManager,
                memoryService
        );

        try {
            System.out.println(
                    "[Session] 当前会话："
                            + sessionManager.currentSessionId()
            );
            ui.run(agent, conversationManager);
        } finally {
            /*
             * 等待已经启动的自动记忆提取完成，
             * 避免用户输入 exit 后最后一轮记忆丢失。
             */
            try {
                memoryService.close();
            } finally {
                hookDispatcher.close();
            }
        }

    }

    record LaunchOptions(boolean cli, int port) {
        static LaunchOptions parse(String[] args) {
            boolean cli = false;
            int port = WebUI.DEFAULT_PORT;
            String[] actualArgs = args == null ? new String[0] : args;

            for (int index = 0; index < actualArgs.length; index++) {
                String argument = actualArgs[index];
                if ("--cli".equals(argument)) {
                    cli = true;
                } else if ("--web".equals(argument)) {
                    // WebUI is the default; this flag remains as an explicit alias.
                } else if ("--port".equals(argument)) {
                    if (++index >= actualArgs.length) {
                        throw new IllegalArgumentException("--port 后需要端口号");
                    }
                    port = parsePort(actualArgs[index]);
                } else if (argument != null && argument.startsWith("--port=")) {
                    port = parsePort(argument.substring("--port=".length()));
                } else {
                    throw new IllegalArgumentException("未知参数: " + argument);
                }
            }

            if (cli && port != WebUI.DEFAULT_PORT) {
                throw new IllegalArgumentException("--cli 不能与 --port 同时使用");
            }
            return new LaunchOptions(cli, port);
        }

        private static int parsePort(String value) {
            try {
                int parsed = Integer.parseInt(value);
                if (parsed < 1 || parsed > 65_535) {
                    throw new IllegalArgumentException("端口必须在 1 到 65535 之间");
                }
                return parsed;
            } catch (NumberFormatException error) {
                throw new IllegalArgumentException("无效端口: " + value);
            }
        }
    }
}
