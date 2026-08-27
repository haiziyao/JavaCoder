package com.jcoder.hook.controller;

import com.jcoder.hook.HookContext;
import com.jcoder.hook.HookDecision;
import com.jcoder.hook.HookErrorPolicy;
import com.jcoder.hook.HookEvent;
import com.jcoder.hook.HookExecutionResult;
import com.jcoder.hook.dispatcher.HookDispatchReport;
import com.jcoder.hook.dispatcher.HookDispatcher;
import com.jcoder.hook.dispatcher.HookInvocationResult;
import com.jcoder.tool.ToolExecuteResult;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Tool 主流程与 HookDispatcher 之间的适配器。
 *
 * Controller 负责解释 Hook 结果对工具调用的影响：
 * PRE_TOOL_USE 可以继续或拒绝；POST_TOOL_USE 只观察。
 */
public final class ToolHookController {

    private final HookDispatcher dispatcher;

    public ToolHookController(
            HookDispatcher dispatcher
    ) {
        this.dispatcher =
                Objects.requireNonNull(
                        dispatcher,
                        "dispatcher"
                );
    }

    public BeforeToolResult beforeTool(
            String sessionId,
            Path workingDirectory,
            String toolName,
            Map<String, Object> toolArguments
    ) {
        HookContext context =
                HookContext.preTool(
                        sessionId,
                        workingDirectory,
                        toolName,
                        toolArguments
                );

        HookDispatchReport report =
                dispatcher.dispatch(context);

        return new BeforeToolResult(
                decideBeforeTool(report),
                report
        );
    }

    public HookDispatchReport afterTool(
            String sessionId,
            Path workingDirectory,
            String toolName,
            Map<String, Object> toolArguments,
            ToolExecuteResult toolResult,
            long durationMillis
    ) {
        Objects.requireNonNull(
                toolResult,
                "toolResult"
        );

        HookContext context =
                HookContext.postTool(
                        sessionId,
                        workingDirectory,
                        toolName,
                        toolArguments,
                        toolResult.output(),
                        toolResult.isError(),
                        durationMillis
                );

        return dispatcher.dispatch(context);
    }

    public List<HookInvocationResult>
    drainAsyncCompletedResults() {
        return dispatcher
                .drainAsyncCompletedResults();
    }

    private static HookDecision decideBeforeTool(
            HookDispatchReport report
    ) {
        for (HookInvocationResult invocation
                : report.invocations()) {

            /*
             * async Hook 在 HookDefinition 构造时已经被禁止
             * reject，因此 SCHEDULED 不影响当前工具调用。
             */
            if (!invocation.isCompleted()) {
                continue;
            }

            HookExecutionResult execution =
                    invocation.executionResult();

            if (invocation.definition().reject()) {
                return HookDecision.reject(
                        rejectionMessage(
                                invocation,
                                execution
                        )
                );
            }

            if (!execution.success()
                    && invocation.definition().onError()
                    == HookErrorPolicy.REJECT) {
                return HookDecision.reject(
                        rejectionMessage(
                                invocation,
                                execution
                        )
                );
            }
        }

        return HookDecision.continueExecution();
    }

    private static String rejectionMessage(
            HookInvocationResult invocation,
            HookExecutionResult execution
    ) {
        if (execution.success()
                && !execution.output().isBlank()) {
            return execution.output();
        }

        if (!execution.success()
                && !execution.errorMessage().isBlank()) {
            return "hook "
                    + invocation.definition().id()
                    + " failed: "
                    + execution.errorMessage();
        }

        return "blocked by hook "
                + invocation.definition().id();
    }

    public record BeforeToolResult(
            HookDecision decision,
            HookDispatchReport report
    ) {
        public BeforeToolResult {
            Objects.requireNonNull(
                    decision,
                    "decision"
            );

            Objects.requireNonNull(
                    report,
                    "report"
            );

            if (report.event()
                    != HookEvent.PRE_TOOL_USE) {
                throw new IllegalArgumentException(
                        "before-tool report must use PRE_TOOL_USE"
                );
            }
        }
    }
}
