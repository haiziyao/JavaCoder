package com.jcoder.command;

import java.util.Objects;

/**
 * Slash Command 的统一执行结果。
 *
 * LOCAL 由 UI 本地显示；
 * PROMPT 会作为真正用户消息提交给 Agent。
 */
public record CommandResult(
        boolean success,
        String output,
        Delivery delivery
) {

    public enum Delivery {
        LOCAL,
        PROMPT
    }

    public CommandResult {
        output =
                output == null
                        ? ""
                        : output;

        delivery =
                Objects.requireNonNull(
                        delivery,
                        "delivery"
                );

        if (delivery == Delivery.PROMPT) {
            if (!success) {
                throw new IllegalArgumentException(
                        "failed command result cannot submit a prompt"
                );
            }

            if (output.isBlank()) {
                throw new IllegalArgumentException(
                        "command prompt must not be blank"
                );
            }
        }
    }

    /**
     * 保留原来的两参数构造语义。
     */
    public CommandResult(
            boolean success,
            String output
    ) {
        this(
                success,
                output,
                Delivery.LOCAL
        );
    }

    public static CommandResult success(
            String output
    ) {
        return new CommandResult(
                true,
                output,
                Delivery.LOCAL
        );
    }

    public static CommandResult error(
            String message
    ) {
        return new CommandResult(
                false,
                message,
                Delivery.LOCAL
        );
    }

    public static CommandResult prompt(
            String prompt
    ) {
        return new CommandResult(
                true,
                prompt,
                Delivery.PROMPT
        );
    }

    public boolean shouldSubmitPrompt() {
        return success
                && delivery == Delivery.PROMPT;
    }
}