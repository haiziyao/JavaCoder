package com.jcoder.hook;

import java.nio.file.Path;
import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Hook 触发时的只读事件现场。
 */
public record HookContext(
        HookEvent event,
        String sessionId,
        Path workingDirectory,
        String message,
        String toolName,
        Map<String, Object> toolArguments,
        String toolOutput,
        boolean toolError,
        long toolDurationMillis,
        Instant occurredAt
) {

    public HookContext {
        Objects.requireNonNull(
                event,
                "event"
        );

        Objects.requireNonNull(
                workingDirectory,
                "workingDirectory"
        );

        workingDirectory =
                workingDirectory
                        .toAbsolutePath()
                        .normalize();

        sessionId =
                sessionId == null
                        ? ""
                        : sessionId.strip();

        message =
                message == null
                        ? ""
                        : message;

        if (toolName != null) {
            toolName = toolName.strip();

            if (toolName.isEmpty()) {
                toolName = null;
            }
        }

        Map<String, Object> copiedArguments =
                new LinkedHashMap<>();

        if (toolArguments != null) {
            copiedArguments.putAll(
                    toolArguments
            );
        }

        toolArguments =
                Collections.unmodifiableMap(
                        copiedArguments
                );

        toolOutput =
                toolOutput == null
                        ? ""
                        : toolOutput;

        if (toolDurationMillis < 0) {
            throw new IllegalArgumentException(
                    "tool duration cannot be negative"
            );
        }

        if (occurredAt == null) {
            occurredAt = Instant.now();
        }

        validateEventFields(
                event,
                toolName,
                toolArguments,
                toolOutput,
                toolError,
                toolDurationMillis
        );
    }

    public static HookContext turnStart(
            String sessionId,
            Path workingDirectory,
            String userMessage
    ) {
        return new HookContext(
                HookEvent.TURN_START,
                sessionId,
                workingDirectory,
                userMessage,
                null,
                Map.of(),
                "",
                false,
                0,
                Instant.now()
        );
    }

    public static HookContext turnEnd(
            String sessionId,
            Path workingDirectory,
            String assistantMessage
    ) {
        return new HookContext(
                HookEvent.TURN_END,
                sessionId,
                workingDirectory,
                assistantMessage,
                null,
                Map.of(),
                "",
                false,
                0,
                Instant.now()
        );
    }

    public static HookContext preTool(
            String sessionId,
            Path workingDirectory,
            String toolName,
            Map<String, Object> toolArguments
    ) {
        return new HookContext(
                HookEvent.PRE_TOOL_USE,
                sessionId,
                workingDirectory,
                "",
                toolName,
                toolArguments,
                "",
                false,
                0,
                Instant.now()
        );
    }

    public static HookContext postTool(
            String sessionId,
            Path workingDirectory,
            String toolName,
            Map<String, Object> toolArguments,
            String toolOutput,
            boolean toolError,
            long toolDurationMillis
    ) {
        return new HookContext(
                HookEvent.POST_TOOL_USE,
                sessionId,
                workingDirectory,
                "",
                toolName,
                toolArguments,
                toolOutput,
                toolError,
                toolDurationMillis,
                Instant.now()
        );
    }

    private static void validateEventFields(
            HookEvent event,
            String toolName,
            Map<String, Object> toolArguments,
            String toolOutput,
            boolean toolError,
            long toolDurationMillis
    ) {
        if (event.isToolEvent()) {
            if (toolName == null) {
                throw new IllegalArgumentException(
                        event
                                + " context requires a tool name"
                );
            }
        } else {
            if (toolName != null
                    || !toolArguments.isEmpty()
                    || !toolOutput.isEmpty()
                    || toolError
                    || toolDurationMillis != 0) {
                throw new IllegalArgumentException(
                        event
                                + " context cannot contain tool data"
                );
            }
        }

        if (event == HookEvent.PRE_TOOL_USE
                && (!toolOutput.isEmpty()
                || toolError
                || toolDurationMillis != 0)) {
            throw new IllegalArgumentException(
                    "PRE_TOOL_USE context cannot contain "
                            + "a completed tool result"
            );
        }
    }
}