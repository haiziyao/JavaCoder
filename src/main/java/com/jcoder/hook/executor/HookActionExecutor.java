package com.jcoder.hook.executor;

import com.jcoder.hook.HookActionType;
import com.jcoder.hook.HookContext;
import com.jcoder.hook.HookDefinition;
import com.jcoder.hook.HookExecutionResult;

/**
 * 执行一种 Hook Action。
 *
 * Executor 只负责执行附加动作，
 * 不负责决定 Agent 主流程是否继续。
 */
public interface HookActionExecutor {

    /**
     * 当前 Executor 支持的动作类型。
     */
    HookActionType type();

    /**
     * 执行 Hook 动作。
     *
     * 失败应当返回 HookExecutionResult.failure，
     * 不应直接修改 Conversation 或 Agent 状态。
     */
    HookExecutionResult execute(
            HookDefinition definition,
            HookContext context
    );
}