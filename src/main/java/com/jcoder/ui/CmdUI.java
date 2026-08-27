package com.jcoder.ui;

import com.jcoder.agent.Agent;
import com.jcoder.agent.AgentEvent;
import com.jcoder.agent.AgentEventQueue;
import com.jcoder.command.CommandContext;
import com.jcoder.command.CommandResult;
import com.jcoder.command.DefaultCommands;
import com.jcoder.command.SlashCommandRegistry;
import com.jcoder.message.ConversationManager;
import com.jcoder.permission.PermissionResponse;

import java.util.Objects;
import java.util.Scanner;

/**
 * 作者：亥子曜
 * -后之览者，亦将有感于斯文
 */
public class CmdUI implements UI {

    private final SlashCommandRegistry commandRegistry;
    public CmdUI() {
        this(DefaultCommands.create());}

    /**
     * 允许测试或其他 UI 注入不同的 Registry。
     */
    public CmdUI(
            SlashCommandRegistry commandRegistry
    ) {
        this.commandRegistry =
                Objects.requireNonNull(
                        commandRegistry,
                        "commandRegistry"
                );
    }

    @Override
    public void run(Agent agent, ConversationManager conversationManager) {
        try (Scanner scanner = new Scanner(System.in)) {
            while (true) {
                System.out.print("> ");

                String prompt = scanner.nextLine();

                if (prompt.strip().startsWith("/")) {
                    try {
                        CommandResult result =
                                commandRegistry.execute(
                                        prompt,
                                        new CommandContext(
                                                agent,
                                                conversationManager
                                        )
                                );

                        if (!result.success()) {
                            if (!result.output().isBlank()) {
                                System.err.println(
                                        "[命令错误] "
                                                + result.output()
                                );
                            }

                            continue;
                        }

                        /*
                         * LOCAL 命令只在本地显示，
                         * 不进入 Conversation。
                         */
                        if (!result.shouldSubmitPrompt()) {
                            if (!result.output().isBlank()) {
                                System.out.println(
                                        result.output()
                                );
                            }

                            continue;
                        }

                        /*
                         * PROMPT 命令展开后继续走下面的正常 Agent 链路。
                         *
                         * Conversation 中保存的是展开后的真实 Prompt，
                         * 而不是原始的 /review 文本。
                         */
                        prompt = result.output();

                        if (prompt.isBlank()) {
                            System.err.println(
                                    "[命令错误] 命令生成了空 Prompt"
                            );
                            continue;
                        }

                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    } catch (RuntimeException e) {
                        System.err.println(
                                "[命令执行失败] "
                                        + e.getMessage()
                        );
                        continue;
                    }
                }

                if ("exit".equalsIgnoreCase(prompt)) {
                    break;
                }

                if (prompt.isBlank()) {
                    continue;
                }

                // 1. 给 Agent 输入用户消息
                conversationManager.addUserMsg(prompt);

                // 2. 启动 Agent
                AgentEventQueue events = agent.run(conversationManager);


                // 3. 消费 Agent 输出
                while (true) {

                    AgentEvent event = null;
                    try {
                        event = events.take();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }

                    switch (event) {
                        case AgentEvent.Text e -> {
                            System.out.print(e.delta());
                            System.out.flush();
                        }

                        case AgentEvent.ToolCall e -> {
                            System.out.println(
                                    "\n[调用工具] " + e.name()
                            );
                        }

                        case AgentEvent.ToolResult e -> {
                            System.out.println(
                                    "\n[工具完成] error=" + e.error()
                            );
                        }

                        case AgentEvent.TurnComplete e -> {
                            System.out.println(
                                    "\n[第 " + e.turn() + " 轮完成]"
                            );
                        }

                        case AgentEvent.LoopComplete e -> {
                            System.out.println(
                                    "本轮对话结束: 共计"+e.turns()+" 轮.");
                        }

                        case AgentEvent.Error e -> {
                            System.err.println(
                                    "\n[Agent 错误] " + e.message()
                            );
                        }

                        case AgentEvent.Log  log->{
                            System.err.println(
                                    "\n[Agent Log] " +log.message()
                            );
                        }

                        case AgentEvent.PermissionRequest e -> {
                            System.out.println("\n[权限询问] " + e.description());
                            System.out.print("y=允许一次 / a=总是允许 / n=拒绝: ");
                            String answer = scanner.nextLine().trim().toLowerCase();
                            PermissionResponse resp = switch (answer) {
                                case "a" -> PermissionResponse.ALLOW_ALWAYS;
                                case "n" -> PermissionResponse.DENY;
                                default  -> PermissionResponse.ALLOW;
                            };
                            e.future().complete(resp);
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

                        case AgentEvent.ContextCompacted e -> {
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
                        }
                    }

                    if (event instanceof AgentEvent.LoopComplete
                            || event instanceof AgentEvent.Error) {
                        break;
                    }
                }
            }
        }
    }
}
