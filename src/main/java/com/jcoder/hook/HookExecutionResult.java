package com.jcoder.hook;

/**
 * 某个 Hook Executor 的原始执行结果。
 */
public record HookExecutionResult(
        boolean success,
        String output,
        String errorMessage,
        long durationMillis
) {

    public HookExecutionResult {
        output =
                output == null
                        ? ""
                        : output;

        errorMessage =
                errorMessage == null
                        ? ""
                        : errorMessage;

        if (durationMillis < 0) {
            throw new IllegalArgumentException(
                    "hook duration cannot be negative"
            );
        }

        if (success && !errorMessage.isEmpty()) {
            throw new IllegalArgumentException(
                    "successful hook result "
                            + "cannot contain an error"
            );
        }

        if (!success && errorMessage.isBlank()) {
            throw new IllegalArgumentException(
                    "failed hook result requires "
                            + "an error message"
            );
        }
    }

    public static HookExecutionResult success(
            String output,
            long durationMillis
    ) {
        return new HookExecutionResult(
                true,
                output,
                "",
                durationMillis
        );
    }

    public static HookExecutionResult failure(
            String errorMessage,
            String output,
            long durationMillis
    ) {
        return new HookExecutionResult(
                false,
                output,
                errorMessage,
                durationMillis
        );
    }
}