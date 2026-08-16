package com.jcoder.ui;

import com.jcoder.agent.Agent;
import com.jcoder.agent.AgentEvent;
import com.jcoder.agent.AgentEventQueue;
import com.jcoder.message.ConversationManager;
import com.jcoder.permission.PermissionChecker;
import com.jcoder.permission.PermissionMode;
import com.jcoder.permission.PermissionResponse;

import java.util.Scanner;

/**
 * 作者：亥子曜
 * -后之览者，亦将有感于斯文
 */
public class CmdUI implements UI {

    @Override
    public void run(Agent agent, ConversationManager conversationManager) {
        try (Scanner scanner = new Scanner(System.in)) {
            while (true) {
                System.out.print("> ");

                String prompt = scanner.nextLine();

                // 权限模式查看/切换（拦截命令，不发给模型）
                if ("/permission".equalsIgnoreCase(prompt)) {
                    PermissionChecker checker = agent.getChecker();
                    if (checker == null) {
                        System.out.println("[权限] 未装配 checker");
                    } else {
                        PermissionMode now = checker.cycleMode();
                        System.out.println("[权限] 当前模式 → " + now);
                    }
                    continue;
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
