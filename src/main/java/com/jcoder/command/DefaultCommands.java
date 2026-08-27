package com.jcoder.command;

import com.jcoder.context.ContextCompactor;
import com.jcoder.context.ContextTokenEstimator;
import com.jcoder.memory.MemoryEntry;
import com.jcoder.memory.MemoryService;
import com.jcoder.permission.PermissionChecker;
import com.jcoder.permission.PermissionMode;
import com.jcoder.session.SessionManager;
import com.jcoder.session.SessionStore;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.stream.Collectors;

/**
 * MyCoder 当前内置的本地 Slash Command。
 */
public final class DefaultCommands {

    private DefaultCommands() {
    }

    public static SlashCommandRegistry create() {
        SlashCommandRegistry registry =
                new SlashCommandRegistry();

        registerHelp(registry);
        registerPermission(registry);
        registerCompact(registry);
        registerClear(registry);
        registerStatus(registry);
        registerReview(registry);
        registerSession(registry);
        registerMemory(registry);

        return registry;
    }

    private static void registerHelp(
            SlashCommandRegistry registry
    ) {
        registry.register(
                new SlashCommand(
                        "help",
                        "显示可用的 Slash Command",
                        List.of("h", "?"),
                        (context, arguments) ->
                                executeHelp(
                                        registry,
                                        arguments
                                )
                )
        );
    }

    private static CommandResult executeHelp(
            SlashCommandRegistry registry,
            String arguments
    ) {
        /*
         * /help compact
         * 显示单个命令的详细信息。
         */
        if (arguments != null
                && !arguments.isBlank()) {

            return registry
                    .find(arguments)
                    .map(command -> {
                        StringBuilder output =
                                new StringBuilder();

                        output
                                .append("/")
                                .append(command.name())
                                .append(" - ")
                                .append(command.description());

                        if (!command.aliases().isEmpty()) {
                            output
                                    .append("\n别名: ")
                                    .append(
                                            command.aliases()
                                                    .stream()
                                                    .map(alias ->
                                                            "/" + alias
                                                    )
                                                    .collect(
                                                            Collectors.joining(
                                                                    ", "
                                                            )
                                                    )
                                    );
                        }

                        return CommandResult.success(
                                output.toString()
                        );
                    })
                    .orElseGet(() ->
                            CommandResult.error(
                                    "Unknown command: "
                                            + arguments
                            )
                    );
        }

        StringBuilder output =
                new StringBuilder(
                        "可用命令：\n"
                );

        for (SlashCommand command :
                registry.listCommands()) {

            output
                    .append("  /")
                    .append(command.name());

            if (!command.aliases().isEmpty()) {
                output
                        .append(" (")
                        .append(
                                command.aliases()
                                        .stream()
                                        .map(alias ->
                                                "/" + alias
                                        )
                                        .collect(
                                                Collectors.joining(
                                                        ", "
                                                )
                                        )
                        )
                        .append(")");
            }

            output
                    .append("\n    ")
                    .append(command.description())
                    .append("\n");
        }

        output.append(
                "\n使用 /help <command> 查看单个命令。"
        );

        return CommandResult.success(
                output.toString()
        );
    }

    private static void registerPermission(
            SlashCommandRegistry registry
    ) {
        registry.register(
                new SlashCommand(
                        "permission",
                        "切换当前工具权限模式",
                        List.of("perm"),
                        DefaultCommands::executePermission
                )
        );
    }

    private static CommandResult executePermission(
            CommandContext context,
            String arguments
    ) {
        if (arguments != null
                && !arguments.isBlank()) {
            return CommandResult.error(
                    "Usage: /permission"
            );
        }

        PermissionChecker checker =
                context.agent().getChecker();

        if (checker == null) {
            return CommandResult.error(
                    "PermissionChecker 未装配"
            );
        }

        PermissionMode mode =
                checker.cycleMode();

        return CommandResult.success(
                "[权限] 当前模式 → "
                        + mode
        );
    }

    private static void registerCompact(
            SlashCommandRegistry registry
    ) {
        registry.register(
                new SlashCommand(
                        "compact",
                        "手动压缩旧对话上下文",
                        List.of("c"),
                        DefaultCommands::executeCompact
                )
        );
    }

    private static CommandResult executeCompact(
            CommandContext context,
            String arguments
    ) throws InterruptedException {

        if (arguments != null
                && !arguments.isBlank()) {
            return CommandResult.error(
                    "Usage: /compact"
            );
        }

        try {
            ContextCompactor.CompactionResult result =
                    context.agent()
                            .compactNow(
                                    context.conversation()
                            );

            if (!result.compacted()) {
                return CommandResult.success(
                        "[上下文压缩] 没有足够的旧消息可压缩；"
                                + "最近消息保持不变"
                );
            }

            return CommandResult.success(
                    "[上下文压缩] 消息 "
                            + result.beforeMessages()
                            + " → "
                            + result.afterMessages()
                            + "，历史估算 tokens "
                            + result.beforeTokens()
                            + " → "
                            + result.afterTokens()
            );

        } catch (RuntimeException e) {
            return CommandResult.error(
                    "[上下文压缩失败] "
                            + e.getMessage()
            );
        }
    }

    private static void registerClear(
            SlashCommandRegistry registry
    ) {
        registry.register(
                new SlashCommand(
                        "clear",
                        "清空当前内存对话",
                        List.of(),
                        DefaultCommands::executeClear
                )
        );
    }

    private static CommandResult executeClear(
            CommandContext context,
            String arguments
    ) {
        if (arguments != null
                && !arguments.isBlank()) {
            return CommandResult.error(
                    "Usage: /clear"
            );
        }

        int removedMessages =
                context.conversation()
                        .clear();

        context.agent()
                .resetContextManagement();

        return CommandResult.success(
                "[会话] 已清空 "
                        + removedMessages
                        + " 条消息"
        );
    }

    private static void registerStatus(
            SlashCommandRegistry registry
    ) {
        registry.register(
                new SlashCommand(
                        "status",
                        "显示当前 Agent 和上下文状态",
                        List.of("s"),
                        DefaultCommands::executeStatus
                )
        );
    }

    private static CommandResult executeStatus(
            CommandContext context,
            String arguments
    ) {
        if (arguments != null
                && !arguments.isBlank()) {
            return CommandResult.error(
                    "Usage: /status"
            );
        }

        var agent =
                context.agent();

        var conversation =
                context.conversation();

        PermissionChecker checker =
                agent.getChecker();

        String permissionMode =
                checker == null
                        ? "NOT_CONFIGURED"
                        : checker.getMode().name();

        String workDirectory =
                agent.getWorkDir();

        if (workDirectory == null
                || workDirectory.isBlank()) {
            workDirectory =
                    Path.of("")
                            .toAbsolutePath()
                            .normalize()
                            .toString();
        }

        int estimatedHistoryTokens =
                ContextTokenEstimator
                        .estimateMessages(
                                conversation.getHistoryCopy()
                        );

        String output =
                """
                MyCoder Status
                ──────────────
                  Agent mode:       %s
                  Permission mode:  %s
                  Messages:         %d
                  History tokens:   ~%d
                  Tools:            %d
                  Context window:   %d
                  Max output:       %d
                  Directory:        %s
                """.formatted(
                        agent.getMode(),
                        permissionMode,
                        conversation.size(),
                        estimatedHistoryTokens,
                        agent.getToolCount(),
                        agent.getContextWindow(),
                        agent.getMaxOutputTokens(),
                        workDirectory
                ).stripTrailing();

        return CommandResult.success(
                output
        );
    }
    private static void registerReview(
            SlashCommandRegistry registry
    ) {
        registry.register(
                new SlashCommand(
                        "review",
                        "让 Agent 审查当前项目改动",
                        List.of(),
                        DefaultCommands::executeReview
                )
        );
    }

    private static CommandResult executeReview(
            CommandContext context,
            String arguments
    ) {
        String prompt =
                """
                Review the current project changes.
    
                Inspect the current git diff and relevant surrounding code.
                Focus on:
                1. logic and correctness problems;
                2. security or destructive-operation risks;
                3. missing error handling and edge cases;
                4. regressions in existing behavior;
                5. tests that are missing or no longer prove the behavior.
    
                Report concrete findings first. Include file names and explain
                why each finding matters. Do not modify files unless the user
                explicitly asks for fixes.
                """.strip();

        if (arguments != null
                && !arguments.isBlank()) {
            prompt +=
                    """
    
                    Additional review focus from the user:
                    %s
                    """.formatted(
                            arguments.strip()
                    );
        }

        return CommandResult.prompt(
                prompt
        );
    }

    private static void registerSession(SlashCommandRegistry registry) {
        registry.register(
                new SlashCommand(
                        "session",
                        "管理当前对话快照：list/save/new/resume",
                        List.of(),
                        DefaultCommands::executeSession
                )
        );
    }

    private static CommandResult executeSession(
            CommandContext context,
            String arguments
    ) {
        SessionManager manager = context.sessionManager();
        if (manager == null) {
            return CommandResult.error("SessionManager 未装配");
        }

        String value = arguments == null ? "" : arguments.strip();

        try {
            if (value.isBlank() || "info".equalsIgnoreCase(value)) {
                return CommandResult.success(
                        "[Session] 当前：" + manager.currentSessionId()
                                + "，消息：" + context.conversation().size()
                );
            }

            if ("save".equalsIgnoreCase(value)) {
                SessionStore.SessionInfo info = manager.save();
                return CommandResult.success(
                        "[Session] 已保存 " + info.id()
                                + "，消息：" + info.messageCount()
                );
            }

            if ("new".equalsIgnoreCase(value)) {
                String sessionId = manager.newSession();
                return CommandResult.success(
                        "[Session] 已创建新会话：" + sessionId
                );
            }

            if ("list".equalsIgnoreCase(value)) {
                List<SessionStore.SessionInfo> sessions = manager.listSessions();
                if (sessions.isEmpty()) {
                    return CommandResult.success("[Session] 没有已保存会话");
                }

                StringBuilder output = new StringBuilder("已保存 Session：\n");
                int limit = Math.min(20, sessions.size());

                for (int index = 0; index < limit; index++) {
                    SessionStore.SessionInfo session = sessions.get(index);
                    output.append("  ")
                            .append(session.id())
                            .append(" | messages=")
                            .append(session.messageCount())
                            .append(" | updated=")
                            .append(Instant.ofEpochMilli(session.updatedAt()))
                            .append(" | ")
                            .append(session.preview())
                            .append('\n');
                }
                if (sessions.size() > limit) {
                    output.append("  ... 还有 ")
                            .append(sessions.size() - limit)
                            .append(" 个\n");
                }
                return CommandResult.success(output.toString().stripTrailing());
            }

            String resumePrefix = "resume ";
            if (value.regionMatches(true, 0, resumePrefix, 0, resumePrefix.length())) {
                String sessionId = value.substring(resumePrefix.length()).strip();
                if (sessionId.isBlank()) {
                    return CommandResult.error(
                            "Usage: /session resume <session-id>"
                    );
                }

                int restored = manager.resume(sessionId);
                return CommandResult.success(
                        "[Session] 已恢复 " + sessionId
                                + "，消息：" + restored
                );
            }

            return CommandResult.error(
                    "Usage: /session [info|list|save|new|resume <session-id>]"
            );
        } catch (IOException | IllegalArgumentException e) {
            return CommandResult.error("[Session] " + e.getMessage());
        }
    }

    private static void registerMemory(SlashCommandRegistry registry) {
        registry.register(
                new SlashCommand(
                        "memory",
                        "查看和治理长期记忆：list/delete/clear",
                        List.of("mem"),
                        DefaultCommands::executeMemory
                )
        );
    }

    private static CommandResult executeMemory(
            CommandContext context,
            String arguments
    ) {
        MemoryService service = context.memoryService();
        if (service == null) {
            return CommandResult.error("MemoryService 未装配");
        }

        String value = arguments == null ? "" : arguments.strip();

        try {
            if (value.isBlank() || "list".equalsIgnoreCase(value)) {
                return renderMemories(service.list(false));
            }

            if ("list all".equalsIgnoreCase(value)) {
                return renderMemories(service.list(true));
            }

            String deletePrefix = "delete ";
            if (value.regionMatches(true, 0, deletePrefix, 0, deletePrefix.length())) {
                String memoryId = value.substring(deletePrefix.length()).strip();
                if (memoryId.isBlank()) {
                    return CommandResult.error("Usage: /memory delete <memory-id>");
                }

                boolean deleted = service.delete(memoryId);
                return deleted
                        ? CommandResult.success("[Memory] 已删除：" + memoryId)
                        : CommandResult.error("Memory not found: " + memoryId);
            }

            String clearPrefix = "clear";
            if (value.regionMatches(true, 0, clearPrefix, 0, clearPrefix.length())) {
                String scopeText = value.substring(clearPrefix.length()).strip();
                MemoryEntry.Scope scope;

                if (scopeText.isBlank() || "all".equalsIgnoreCase(scopeText)) {
                    scope = null;
                } else if ("user".equalsIgnoreCase(scopeText)) {
                    scope = MemoryEntry.Scope.USER;
                } else if ("project".equalsIgnoreCase(scopeText)) {
                    scope = MemoryEntry.Scope.PROJECT;
                } else {
                    return CommandResult.error(
                            "Usage: /memory clear [user|project|all]"
                    );
                }

                int deleted = service.clear(scope);
                return CommandResult.success(
                        "[Memory] 已标记删除 " + deleted + " 条"
                );
            }

            return CommandResult.error(
                    "Usage: /memory [list|list all|delete <id>|clear [user|project|all]]"
            );
        } catch (IOException e) {
            return CommandResult.error("[Memory] " + e.getMessage());
        }
    }

    private static CommandResult renderMemories(List<MemoryEntry> memories) {
        if (memories.isEmpty()) {
            return CommandResult.success("[Memory] 没有记忆");
        }

        StringBuilder output = new StringBuilder("长期记忆：\n");
        for (MemoryEntry memory : memories) {
            output.append("  ")
                    .append(memory.id())
                    .append(" | ")
                    .append(memory.status())
                    .append(" | ")
                    .append(memory.scope())
                    .append('/')
                    .append(memory.category())
                    .append(" | ")
                    .append(memory.key())
                    .append("\n    ")
                    .append(memory.content())
                    .append('\n');
        }
        return CommandResult.success(output.toString().stripTrailing());
    }

}