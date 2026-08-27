package com.jcoder.hook.dispatcher;

import com.jcoder.hook.HookDefinition;
import com.jcoder.hook.HookExecutionResult;

import java.util.Objects;

/**
 * 单个 Hook 的调度结果。
 */
public record HookInvocationResult(
        HookDefinition definition,
        State state,
        HookExecutionResult executionResult
) {

    public HookInvocationResult {
        Objects.requireNonNull(
                definition,
                "definition"
        );

        Objects.requireNonNull(
                state,
                "state"
        );

        if (state == State.COMPLETED
                && executionResult == null) {
            throw new IllegalArgumentException(
                    "completed hook invocation "
                            + "requires an execution result"
            );
        }

        if (state == State.SCHEDULED
                && executionResult != null) {
            throw new IllegalArgumentException(
                    "scheduled hook invocation "
                            + "cannot already contain a result"
            );
        }
    }

    public static HookInvocationResult completed(
            HookDefinition definition,
            HookExecutionResult executionResult
    ) {
        return new HookInvocationResult(
                definition,
                State.COMPLETED,
                Objects.requireNonNull(
                        executionResult,
                        "executionResult"
                )
        );
    }

    public static HookInvocationResult scheduled(
            HookDefinition definition
    ) {
        return new HookInvocationResult(
                definition,
                State.SCHEDULED,
                null
        );
    }

    public boolean isCompleted() {
        return state == State.COMPLETED;
    }

    public boolean isScheduled() {
        return state == State.SCHEDULED;
    }

    public boolean isFailure() {
        return isCompleted()
                && !executionResult.success();
    }

    public enum State {
        COMPLETED,
        SCHEDULED
    }
}
