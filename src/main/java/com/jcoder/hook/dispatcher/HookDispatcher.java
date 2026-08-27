package com.jcoder.hook.dispatcher;

import com.jcoder.hook.HookContext;
import com.jcoder.hook.HookDefinition;
import com.jcoder.hook.HookExecutionResult;
import com.jcoder.hook.executor.HookExecutorRegistry;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Hook 的匹配和执行调度中心。
 *
 * Dispatcher 负责 Event/Selector 匹配、once 原子占位，
 * 以及同步或异步执行；它不负责修改 Conversation，
 * 也不决定 Agent 主流程是否继续。
 */
public final class HookDispatcher
        implements AutoCloseable {

    private final HookExecutorRegistry executorRegistry;
    private final ExecutorService asyncExecutor;

    private final Set<String> firedOnce =
            ConcurrentHashMap.newKeySet();

    private final ConcurrentLinkedQueue<
            HookInvocationResult
            > asyncCompletedResults =
            new ConcurrentLinkedQueue<>();

    private final AtomicBoolean closed =
            new AtomicBoolean(false);

    private volatile List<HookDefinition> definitions =
            List.of();

    public HookDispatcher(
            HookExecutorRegistry executorRegistry,
            List<HookDefinition> definitions
    ) {
        this.executorRegistry =
                Objects.requireNonNull(
                        executorRegistry,
                        "executorRegistry"
                );

        /*
         * 先完成纯配置校验，再创建异步 Executor。
         * 如果 definitions 非法，构造直接失败且不会留下
         * 一个无法由调用方 close 的 Executor 对象。
         */
        List<HookDefinition> validated =
                validateDefinitions(definitions);

        this.asyncExecutor =
                Executors
                        .newVirtualThreadPerTaskExecutor();

        this.definitions = validated;
    }

    /**
     * 原子替换 Hook 配置快照。
     *
     * 相同 ID 的 once 状态继续保留；已经从配置删除的
     * ID 会同时从 firedOnce 中移除。
     */
    public synchronized void replaceDefinitions(
            List<HookDefinition> newDefinitions
    ) {
        ensureOpen();

        List<HookDefinition> validated =
                validateDefinitions(newDefinitions);

        Set<String> currentIds =
                new HashSet<>();

        for (HookDefinition definition : validated) {
            currentIds.add(definition.id());
        }

        firedOnce.retainAll(currentIds);
        definitions = validated;
    }

    public List<HookDefinition> definitions() {
        return definitions;
    }

    /**
     * 调度一个事件。Selector 不匹配时直接跳过，
     * 不会调用空 Executor。
     */
    public HookDispatchReport dispatch(
            HookContext context
    ) {
        ensureOpen();

        Objects.requireNonNull(
                context,
                "context"
        );

        List<HookDefinition> snapshot =
                definitions;

        List<HookInvocationResult> results =
                new ArrayList<>();

        for (HookDefinition definition : snapshot) {
            if (definition.event()
                    != context.event()) {
                continue;
            }

            if (!definition.selector()
                    .matches(context)) {
                continue;
            }

            /*
             * once 在准备执行或调度时原子占位。
             * 即使 Executor 失败，也不自动重试。
             */
            if (definition.once()
                    && !firedOnce.add(
                    definition.id()
            )) {
                continue;
            }

            if (definition.async()) {
                results.add(
                        scheduleAsync(
                                definition,
                                context
                        )
                );
            } else {
                results.add(
                        executeSynchronously(
                                definition,
                                context
                        )
                );
            }
        }

        return new HookDispatchReport(
                context.event(),
                results
        );
    }

    private HookInvocationResult executeSynchronously(
            HookDefinition definition,
            HookContext context
    ) {
        HookExecutionResult executionResult =
                executorRegistry.execute(
                        definition,
                        context
                );

        return HookInvocationResult.completed(
                definition,
                executionResult
        );
    }

    private HookInvocationResult scheduleAsync(
            HookDefinition definition,
            HookContext context
    ) {
        try {
            asyncExecutor.submit(
                    () -> {
                        HookExecutionResult executionResult =
                                executorRegistry.execute(
                                        definition,
                                        context
                                );

                        asyncCompletedResults.offer(
                                HookInvocationResult.completed(
                                        definition,
                                        executionResult
                                )
                        );
                    }
            );

            return HookInvocationResult.scheduled(
                    definition
            );
        } catch (RejectedExecutionException exception) {
            HookExecutionResult executionResult =
                    HookExecutionResult.failure(
                            "async hook executor rejected the task",
                            "",
                            0
                    );

            return HookInvocationResult.completed(
                    definition,
                    executionResult
            );
        }
    }

    /**
     * 取出并清空已经完成的异步 Hook 结果。
     */
    public List<HookInvocationResult>
    drainAsyncCompletedResults() {
        List<HookInvocationResult> drained =
                new ArrayList<>();

        HookInvocationResult result;

        while ((result =
                asyncCompletedResults.poll()) != null) {
            drained.add(result);
        }

        return List.copyOf(drained);
    }

    public void resetOnceState() {
        firedOnce.clear();
    }

    private List<HookDefinition> validateDefinitions(
            List<HookDefinition> newDefinitions
    ) {
        if (newDefinitions == null) {
            return List.of();
        }

        List<HookDefinition> copied =
                List.copyOf(newDefinitions);

        Set<String> ids =
                new HashSet<>();

        for (HookDefinition definition : copied) {
            Objects.requireNonNull(
                    definition,
                    "hook definition"
            );

            if (!ids.add(definition.id())) {
                throw new IllegalArgumentException(
                        "duplicate hook id: "
                                + definition.id()
                );
            }

            if (!executorRegistry.contains(
                    definition.action().type()
            )) {
                throw new IllegalArgumentException(
                        "no executor registered for hook "
                                + definition.id()
                                + ": "
                                + definition.action().type()
                );
            }
        }

        return copied;
    }

    private void ensureOpen() {
        if (closed.get()) {
            throw new IllegalStateException(
                    "hook dispatcher is closed"
            );
        }
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(
                false,
                true
        )) {
            return;
        }

        asyncExecutor.shutdown();

        try {
            if (!asyncExecutor.awaitTermination(
                    1,
                    TimeUnit.SECONDS
            )) {
                asyncExecutor.shutdownNow();
            }
        } catch (InterruptedException exception) {
            asyncExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
