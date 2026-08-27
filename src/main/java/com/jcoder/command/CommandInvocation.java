package com.jcoder.command;

import java.util.Locale;
import java.util.Optional;

/**
 * 一次解析完成的 Slash Command 调用。
 *
 * 例如：
 * /help compact
 *
 * 会得到：
 * name = help
 * arguments = compact
 */
public record CommandInvocation(
        String name,
        String arguments
) {

    public CommandInvocation {
        if (name == null
                || name.isBlank()) {
            throw new IllegalArgumentException(
                    "command name is required"
            );
        }

        name = name
                .strip()
                .toLowerCase(Locale.ROOT);

        arguments =
                arguments == null
                        ? ""
                        : arguments.strip();
    }

    /**
     * 非 Slash Command 返回 Optional.empty()。
     */
    public static Optional<CommandInvocation> parse(
            String rawInput
    ) {
        if (rawInput == null) {
            return Optional.empty();
        }

        String input = rawInput.strip();

        if (!input.startsWith("/")) {
            return Optional.empty();
        }

        String commandLine =
                input.substring(1).strip();

        if (commandLine.isEmpty()) {
            return Optional.empty();
        }

        int argumentStart = -1;

        for (int index = 0;
             index < commandLine.length();
             index++) {

            if (Character.isWhitespace(
                    commandLine.charAt(index)
            )) {
                argumentStart = index;
                break;
            }
        }

        if (argumentStart < 0) {
            return Optional.of(
                    new CommandInvocation(
                            commandLine,
                            ""
                    )
            );
        }

        String name =
                commandLine.substring(
                        0,
                        argumentStart
                );

        String arguments =
                commandLine.substring(
                        argumentStart + 1
                ).strip();

        return Optional.of(
                new CommandInvocation(
                        name,
                        arguments
                )
        );
    }
}