package com.jcoder.command;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Slash Command 的注册、查找和执行入口。
 */
public final class SlashCommandRegistry {

    /**
     * 名称和别名都指向同一个命令。
     */
    private final Map<String, SlashCommand> commandIndex =
            new LinkedHashMap<>();

    /**
     * 这里只保存规范名称对应的命令，
     * 防止 listCommands() 重复显示别名。
     */
    private final List<SlashCommand> commands =
            new ArrayList<>();

    public void register(
            SlashCommand command
    ) {
        Objects.requireNonNull(
                command,
                "command"
        );

        List<String> names =
                new ArrayList<>();

        names.add(command.name());
        names.addAll(command.aliases());

        /*
         * 必须先完成全部冲突检查，
         * 再修改 Registry，避免注册到一半失败。
         */
        for (String name : names) {
            SlashCommand existing =
                    commandIndex.get(name);

            if (existing != null) {
                throw new IllegalArgumentException(
                        "command name or alias '"
                                + name
                                + "' is already owned by /"
                                + existing.name()
                );
            }
        }

        commands.add(command);

        for (String name : names) {
            commandIndex.put(
                    name,
                    command
            );
        }
    }

    public Optional<SlashCommand> find(
            String nameOrAlias
    ) {
        if (nameOrAlias == null
                || nameOrAlias.isBlank()) {
            return Optional.empty();
        }

        String normalized =
                nameOrAlias
                        .strip()
                        .toLowerCase(Locale.ROOT);

        if (normalized.startsWith("/")) {
            normalized =
                    normalized.substring(1);
        }

        return Optional.ofNullable(
                commandIndex.get(normalized)
        );
    }

    public CommandResult execute(
            String rawInput,
            CommandContext context
    ) throws InterruptedException {

        Objects.requireNonNull(
                context,
                "context"
        );

        Optional<CommandInvocation> parsed =
                CommandInvocation.parse(
                        rawInput
                );

        if (parsed.isEmpty()) {
            return CommandResult.error(
                    "Invalid slash command"
            );
        }

        CommandInvocation invocation =
                parsed.get();

        Optional<SlashCommand> command =
                find(invocation.name());

        if (command.isEmpty()) {
            return CommandResult.error(
                    "Unknown command: /"
                            + invocation.name()
                            + ". Type /help to list commands."
            );
        }

        CommandResult result =
                command.get()
                        .handler()
                        .execute(
                                context,
                                invocation.arguments()
                        );

        if (result == null) {
            return CommandResult.error(
                    "Command /"
                            + command.get().name()
                            + " returned no result"
            );
        }

        return result;
    }

    public List<SlashCommand> listCommands() {
        return commands
                .stream()
                .sorted(
                        Comparator.comparing(
                                SlashCommand::name
                        )
                )
                .toList();
    }
}