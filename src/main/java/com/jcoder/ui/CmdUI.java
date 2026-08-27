package com.jcoder.ui;

import com.jcoder.agent.Agent;
import com.jcoder.agent.AgentEvent;
import com.jcoder.agent.AgentEventQueue;
import com.jcoder.command.CommandContext;
import com.jcoder.command.CommandResult;
import com.jcoder.command.DefaultCommands;
import com.jcoder.command.SlashCommandRegistry;
import com.jcoder.memory.MemoryRecall;
import com.jcoder.memory.MemoryService;
import com.jcoder.message.ConversationManager;
import com.jcoder.message.Message;
import com.jcoder.permission.PermissionResponse;
import com.jcoder.session.SessionManager;

import java.io.IOException;
import java.util.List;
import java.util.Objects;
import java.util.Scanner;

public class CmdUI implements UI {

    private final SlashCommandRegistry commandRegistry;
    private final SessionManager sessionManager;
    private final MemoryService memoryService;

    public CmdUI() {
        this(DefaultCommands.create(), null, null);
    }

    public CmdUI(SlashCommandRegistry commandRegistry) {
        this(commandRegistry, null, null);
    }

    public CmdUI(
            SessionManager sessionManager,
            MemoryService memoryService
    ) {
        this(DefaultCommands.create(), sessionManager, memoryService);
    }

    public CmdUI(
            SlashCommandRegistry commandRegistry,
            SessionManager sessionManager,
            MemoryService memoryService
    ) {
        this.commandRegistry = Objects.requireNonNull(
                commandRegistry,
                "commandRegistry"
        );
        this.sessionManager = sessionManager;
        this.memoryService = memoryService;
    }

    @Override
    public void run(
            Agent agent,
            ConversationManager conversationManager
    ) {
        try (Scanner scanner = new Scanner(System.in)) {
            while (true) {
                System.out.print("> ");

                if (!scanner.hasNextLine()) {
                    persistSessionBestEffort();
                    return;
                }

                String prompt = scanner.nextLine();

                if (prompt.strip().startsWith("/")) {
                    try {
                        CommandResult result = commandRegistry.execute(
                                prompt,
                                new CommandContext(
                                        agent,
                                        conversationManager,
                                        sessionManager,
                                        memoryService
                                )
                        );

                        if (!result.success()) {
                            if (!result.output().isBlank()) {
                                System.err.println(
                                        "[命令错误] " + result.output()
                                );
                            }
                            continue;
                        }

                        if (!result.shouldSubmitPrompt()) {
                            if (!result.output().isBlank()) {
                                System.out.println(result.output());
                            }

                            // /clear 和 /session new 等本地命令也可能改变会话。
                            persistSessionBestEffort();
                            continue;
                        }

                        prompt = result.output();
                        if (prompt.isBlank()) {
                            System.err.println("[命令错误] 命令生成了空 Prompt");
                            continue;
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        persistSessionBestEffort();
                        return;
                    } catch (RuntimeException e) {
                        System.err.println(
                                "[命令执行失败] " + e.getMessage()
                        );
                        continue;
                    }
                }

                if ("exit".equalsIgnoreCase(prompt)) {
                    persistSessionBestEffort();
                    break;
                }

                if (prompt.isBlank()) {
                    continue;
                }

                prepareMemoryReminder(agent, prompt);

                // 保存对象引用，压缩改变历史下标后仍能定位本轮起点。
                Message currentUserMessage = new Message("user", prompt);
                conversationManager.addMessage(currentUserMessage);

                AgentEventQueue events = agent.run(conversationManager);
                boolean completedSuccessfully = false;

                while (true) {
                    AgentEvent event;
                    try {
                        event = events.take();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        persistSessionBestEffort();
                        return;
                    }

                    switch (event) {
                        case AgentEvent.Text e -> {
                            System.out.print(e.delta());
                            System.out.flush();
                        }

                        case AgentEvent.ToolCall e ->
                                System.out.println("\n[调用工具] " + e.name());

                        case AgentEvent.ToolResult e ->
                                System.out.println(
                                        "\n[工具完成] error=" + e.error()
                                );

                        case AgentEvent.TurnComplete e ->
                                System.out.println(
                                        "\n[第 " + e.turn() + " 轮完成]"
                                );

                        case AgentEvent.LoopComplete e -> {
                            completedSuccessfully = true;
                            System.out.println(
                                    "本轮对话结束: 共计"
                                            + e.turns()
                                            + " 轮."
                            );
                        }

                        case AgentEvent.Error e ->
                                System.err.println(
                                        "\n[Agent 错误] " + e.message()
                                );

                        case AgentEvent.Log e ->
                                System.err.println(
                                        "\n[Agent Log] " + e.message()
                                );

                        case AgentEvent.PermissionRequest e -> {
                            System.out.println(
                                    "\n[权限询问] " + e.description()
                            );
                            System.out.print(
                                    "y=允许一次 / a=总是允许 / n=拒绝: "
                            );

                            String answer = scanner.nextLine()
                                    .trim()
                                    .toLowerCase();

                            PermissionResponse response = switch (answer) {
                                case "a" -> PermissionResponse.ALLOW_ALWAYS;
                                case "n" -> PermissionResponse.DENY;
                                default -> PermissionResponse.ALLOW;
                            };
                            e.future().complete(response);
                        }

                        case AgentEvent.ContextUsage e -> {
                            System.out.println(
                                    "\n[上下文] 估算输入 "
                                            + e.estimatedInputTokens()
                                            + " / "
                                            + e.inputLimit()
                                            + " tokens，剩余 "
                                            + e.remainingInputTokens()
                            );

                            if (e.shouldCompact()) {
                                System.out.println(
                                        "[上下文] 已达到自动压缩阈值，准备生成摘要"
                                );
                            }
                        }

                        case AgentEvent.ContextCompacted e ->
                                System.out.println(
                                        "\n[上下文压缩] 消息 "
                                                + e.beforeMessages()
                                                + " → "
                                                + e.afterMessages()
                                                + "，估算 tokens "
                                                + e.beforeTokens()
                                                + " → "
                                                + e.afterTokens()
                                );

                        case AgentEvent.ContextCompactionStarted ignored ->
                                System.out.println(
                                        "\n[上下文压缩] 正在生成摘要..."
                                );

                        case AgentEvent.ContextCompactionFailed e ->
                                System.err.println(
                                        "\n[上下文压缩] 失败: " + e.message()
                                );

                        case AgentEvent.ContextCompactionCircuitOpen e ->
                                System.err.println(
                                        "\n[上下文压缩] 自动压缩已熔断，失败上限="
                                                + e.maxFailures()
                                );

                        case AgentEvent.ToolResultOffloaded e ->
                                System.out.println(
                                        "\n[上下文] 已将 "
                                                + e.offloadedResults()
                                                + " 个大工具结果保存到磁盘"
                                );
                    }

                    if (event instanceof AgentEvent.LoopComplete
                            || event instanceof AgentEvent.Error) {
                        break;
                    }
                }

                // 即使 Agent 失败，也保留用户输入和已经产生的工具轨迹。
                persistSessionBestEffort();

                if (completedSuccessfully && memoryService != null) {
                    List<Message> turnMessages = messagesFromCurrentTurn(
                            conversationManager,
                            currentUserMessage
                    );

                    if (turnMessages.size() >= 2) {
                        String sourceSessionId = sessionManager == null
                                ? agent.getSessionId()
                                : sessionManager.currentSessionId();

                        memoryService.extractAsync(
                                sourceSessionId,
                                turnMessages
                        );
                    }
                }
            }
        }
    }

    private void prepareMemoryReminder(Agent agent, String query) {
        if (memoryService == null) {
            agent.setLongTermMemoryReminder("");
            return;
        }

        try {
            MemoryRecall.RecallResult recall = memoryService.recall(query);
            agent.setLongTermMemoryReminder(recall.reminder());

            if (!recall.memoryIds().isEmpty()) {
                System.out.println(
                        "[Memory] 本轮召回 "
                                + recall.memoryIds().size()
                                + " 条，约 "
                                + recall.estimatedTokens()
                                + " tokens"
                );
            }
        } catch (IOException e) {
            agent.setLongTermMemoryReminder("");
            System.err.println("[Memory] 召回失败: " + e.getMessage());
        }
    }

    private List<Message> messagesFromCurrentTurn(
            ConversationManager conversation,
            Message currentUserMessage
    ) {
        List<Message> history = conversation.getHistoryCopy();

        for (int index = 0; index < history.size(); index++) {
            if (history.get(index) == currentUserMessage) {
                return List.copyOf(history.subList(index, history.size()));
            }
        }

        // 正常压缩会保留最近消息；找不到说明状态发生了异常变化，放弃提取。
        return List.of();
    }

    private void persistSessionBestEffort() {
        if (sessionManager == null) {
            return;
        }

        try {
            sessionManager.save();
        } catch (IOException | RuntimeException e) {
            System.err.println("[Session] 自动保存失败: " + e.getMessage());
        }
    }
}