package com.jcoder.hook;

import com.jcoder.hook.action.HookAction;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * 一份完整的 Hook 定义。
 */
public record HookDefinition(
        String id,
        HookEvent event,
        HookSelector selector,
        HookAction action,
        boolean reject,
        boolean once,
        boolean async,
        HookErrorPolicy onError,
        long timeoutMillis
) {

    private static final Pattern VALID_ID =
            Pattern.compile(
                    "[a-z0-9][a-z0-9_-]{0,63}"
            );

    public static final long DEFAULT_TIMEOUT_MILLIS =
            10_000L;

    public static final long MAX_TIMEOUT_MILLIS =
            300_000L;

    public HookDefinition {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException(
                    "hook id is required"
            );
        }

        id = id.strip();

        if (!VALID_ID.matcher(id).matches()) {
            throw new IllegalArgumentException(
                    "invalid hook id: "
                            + id
                            + "; expected lowercase letters, "
                            + "numbers, hyphens or underscores"
            );
        }

        Objects.requireNonNull(
                event,
                "event"
        );

        if (selector == null) {
            selector = HookSelector.any();
        }

        Objects.requireNonNull(
                action,
                "action"
        );

        if (onError == null) {
            onError = HookErrorPolicy.CONTINUE;
        }

        if (timeoutMillis == 0) {
            timeoutMillis =
                    DEFAULT_TIMEOUT_MILLIS;
        }

        if (timeoutMillis < 0
                || timeoutMillis
                > MAX_TIMEOUT_MILLIS) {
            throw new IllegalArgumentException(
                    "hook timeout must be between 1 and "
                            + MAX_TIMEOUT_MILLIS
                            + " milliseconds"
            );
        }

        if (!event.isToolEvent()
                && selector.usesToolData()) {
            throw new IllegalArgumentException(
                    event
                            + " hook cannot use tool selector fields"
            );
        }

        if (selector.toolError() != null
                && event != HookEvent.POST_TOOL_USE) {
            throw new IllegalArgumentException(
                    "toolError selector is only valid "
                            + "for POST_TOOL_USE"
            );
        }

        if (reject
                && !event.allowsRejection()) {
            throw new IllegalArgumentException(
                    event
                            + " hook cannot reject the main flow"
            );
        }

        if (onError == HookErrorPolicy.REJECT
                && !event.allowsRejection()) {
            throw new IllegalArgumentException(
                    event
                            + " hook cannot use onError=REJECT"
            );
        }

        if (async
                && (reject
                || onError
                == HookErrorPolicy.REJECT)) {
            throw new IllegalArgumentException(
                    "an asynchronous hook cannot reject "
                            + "the main flow"
            );
        }
    }
}