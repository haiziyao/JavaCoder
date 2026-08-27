package com.jcoder.hook.executor;

import com.jcoder.hook.HookActionType;
import com.jcoder.hook.HookContext;
import com.jcoder.hook.HookDefinition;
import com.jcoder.hook.HookExecutionResult;
import com.jcoder.hook.action.PromptHookAction;

import java.util.Objects;

/**
 * 渲染 PROMPT Hook。
 *
 * 它只生成一段文本：
 * - 不调用 LLM
 * - 不修改 Conversation
 * - 不决定是否拒绝主流程
 *
 * 文本最终如何使用，由 Controller 决定。
 */
public final class PromptHookExecutor
        implements HookActionExecutor {

    private static final int DEFAULT_MAX_PROMPT_CHARS =
            20_000;

    private final HookTemplateRenderer renderer;
    private final int maxPromptChars;

    public PromptHookExecutor(
            HookTemplateRenderer renderer
    ) {
        this(
                renderer,
                DEFAULT_MAX_PROMPT_CHARS
        );
    }

    public PromptHookExecutor(
            HookTemplateRenderer renderer,
            int maxPromptChars
    ) {
        this.renderer =
                Objects.requireNonNull(
                        renderer,
                        "renderer"
                );

        if (maxPromptChars <= 0) {
            throw new IllegalArgumentException(
                    "maxPromptChars must be positive"
            );
        }

        this.maxPromptChars =
                maxPromptChars;
    }

    @Override
    public HookActionType type() {
        return HookActionType.PROMPT;
    }

    @Override
    public HookExecutionResult execute(
            HookDefinition definition,
            HookContext context
    ) {
        long startedAt =
                System.nanoTime();

        if (!(definition.action()
                instanceof PromptHookAction action)) {
            return HookExecutionResult.failure(
                    "PROMPT executor received "
                            + definition.action().type(),
                    "",
                    elapsedMillis(startedAt)
            );
        }

        final String rendered;

        try {
            rendered = renderer.render(
                    action.template(),
                    context
            );
        } catch (IllegalArgumentException exception) {
            return HookExecutionResult.failure(
                    "failed to render prompt hook: "
                            + exceptionMessage(exception),
                    "",
                    elapsedMillis(startedAt)
            );
        }

        if (rendered.isBlank()) {
            return HookExecutionResult.failure(
                    "prompt hook rendered an empty message",
                    "",
                    elapsedMillis(startedAt)
            );
        }

        if (rendered.length()
                > maxPromptChars) {
            return HookExecutionResult.failure(
                    "prompt hook output is too large: "
                            + rendered.length()
                            + " characters; maximum is "
                            + maxPromptChars,
                    "",
                    elapsedMillis(startedAt)
            );
        }

        return HookExecutionResult.success(
                rendered,
                elapsedMillis(startedAt)
        );
    }

    private static long elapsedMillis(
            long startedAt
    ) {
        return Math.max(
                0,
                (System.nanoTime() - startedAt)
                        / 1_000_000L
        );
    }

    private static String exceptionMessage(
            Throwable throwable
    ) {
        String message =
                throwable.getMessage();

        if (message == null
                || message.isBlank()) {
            return throwable
                    .getClass()
                    .getSimpleName();
        }

        return message;
    }
}