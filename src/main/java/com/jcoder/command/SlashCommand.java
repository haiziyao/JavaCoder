package com.jcoder.command;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/**
 * Slash Command 的定义和处理器。
 */
public record SlashCommand(
        String name,
        String description,
        List<String> aliases,
        Handler handler
) {

    public SlashCommand {
        name = normalizeName(
                name,
                "command name"
        );

        description =
                description == null
                        ? ""
                        : description.strip();

        List<String> sourceAliases =
                aliases == null
                        ? List.of()
                        : aliases;

        Set<String> uniqueAliases =
                new LinkedHashSet<>();

        for (String sourceAlias :
                sourceAliases) {

            String alias =
                    normalizeName(
                            sourceAlias,
                            "command alias"
                    );

            if (alias.equals(name)) {
                throw new IllegalArgumentException(
                        "command alias duplicates command name: "
                                + alias
                );
            }

            if (!uniqueAliases.add(alias)) {
                throw new IllegalArgumentException(
                        "duplicate command alias: "
                                + alias
                );
            }
        }

        aliases = List.copyOf(
                new ArrayList<>(
                        uniqueAliases
                )
        );

        Objects.requireNonNull(
                handler,
                "handler"
        );
    }

    public boolean matches(String candidate) {
        if (candidate == null) {
            return false;
        }

        String normalized =
                candidate
                        .strip()
                        .toLowerCase(Locale.ROOT);

        return name.equals(normalized)
                || aliases.contains(normalized);
    }

    private static String normalizeName(
            String value,
            String label
    ) {
        if (value == null
                || value.isBlank()) {
            throw new IllegalArgumentException(
                    label + " is required"
            );
        }

        String normalized =
                value
                        .strip()
                        .toLowerCase(Locale.ROOT);

        for (int index = 0;
             index < normalized.length();
             index++) {

            char character =
                    normalized.charAt(index);

            if (character == '/'
                    || Character.isWhitespace(
                            character
                    )) {
                throw new IllegalArgumentException(
                        label
                                + " must not contain '/' or whitespace: "
                                + value
                );
            }
        }

        return normalized;
    }

    @FunctionalInterface
    public interface Handler {

        CommandResult execute(
                CommandContext context,
                String arguments
        ) throws InterruptedException;
    }
}