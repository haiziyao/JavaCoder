package com.jcoder.hook;

/**
 * Hook Controller 对 Agent 主流程作出的决定。
 */
public record HookDecision(
        boolean rejected,
        String message
) {

    public HookDecision {
        message =
                message == null
                        ? ""
                        : message;

        if (rejected && message.isBlank()) {
            throw new IllegalArgumentException(
                    "rejected hook decision "
                            + "requires a message"
            );
        }
    }

    public static HookDecision continueExecution() {
        return new HookDecision(
                false,
                ""
        );
    }

    public static HookDecision reject(
            String message
    ) {
        return new HookDecision(
                true,
                message
        );
    }
}