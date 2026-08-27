package com.jcoder.hook.executor;

import com.jcoder.hook.*;

import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Hook Executor 注册中心。
 *
 * Dispatcher 不需要知道具体 Executor 类，
 * 只需要根据 HookActionType 进行查找。
 */
public final class HookExecutorRegistry {

    private final ConcurrentMap<HookActionType, HookActionExecutor> executors =
            new ConcurrentHashMap<>();

    /**
     * 创建第一版默认 Executor 集合。
     */
    public static HookExecutorRegistry createDefault() {
        HookTemplateRenderer renderer =
                new HookTemplateRenderer();

        return new HookExecutorRegistry()
                .register(
                        new PromptHookExecutor(
                                renderer
                        )
                )
                .register(
                        new CommandHookExecutor()
                )
                .register(
                        new HttpHookExecutor(
                                renderer
                        )
                );
    }

    /**
     * 注册 Executor。
     *
     * 同一种动作类型只能注册一次，
     * 避免启动顺序悄悄覆盖已有实现。
     */
    public HookExecutorRegistry register(
            HookActionExecutor executor
    ) {
        Objects.requireNonNull(
                executor,
                "executor"
        );

        HookActionExecutor previous =
                executors.putIfAbsent(
                        executor.type(),
                        executor
                );

        if (previous != null) {
            throw new IllegalStateException(
                    "hook executor already registered: "
                            + executor.type()
            );
        }

        return this;
    }

    public boolean contains(
            HookActionType type
    ) {
        Objects.requireNonNull(
                type,
                "type"
        );

        return executors.containsKey(type);
    }

    public Set<HookActionType> registeredTypes() {
        return Set.copyOf(
                executors.keySet()
        );
    }

    /**
     * 路由并执行一个 Hook。
     *
     * Registry 在这里建立异常边界：
     * 单个 Executor 的 RuntimeException
     * 不应直接炸掉 Agent 主循环。
     */
    public HookExecutionResult execute(
            HookDefinition definition,
            HookContext context
    ) {
        Objects.requireNonNull(
                definition,
                "definition"
        );

        Objects.requireNonNull(
                context,
                "context"
        );

        long startedAt = System.nanoTime();

        if (definition.event() != context.event()) {
            return HookExecutionResult.failure(
                    "hook event does not match context: "
                            + definition.event()
                            + " != "
                            + context.event(),
                    "",
                    elapsedMillis(startedAt)
            );
        }

        HookActionType type =
                definition.action().type();

        HookActionExecutor executor =
                executors.get(type);

        if (executor == null) {
            return HookExecutionResult.failure(
                    "no hook executor registered for "
                            + type,
                    "",
                    elapsedMillis(startedAt)
            );
        }

        try {
            HookExecutionResult result =
                    executor.execute(
                            definition,
                            context
                    );

            if (result == null) {
                return HookExecutionResult.failure(
                        "hook executor returned null: "
                                + type,
                        "",
                        elapsedMillis(startedAt)
                );
            }

            return result;
        } catch (RuntimeException exception) {
            return HookExecutionResult.failure(
                    "hook executor failed: "
                            + exceptionMessage(exception),
                    "",
                    elapsedMillis(startedAt)
            );
        }
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
            RuntimeException exception
    ) {
        String message = exception.getMessage();

        if (message == null || message.isBlank()) {
            return exception
                    .getClass()
                    .getSimpleName();
        }

        return message;
    }
}