package com.jcoder.hook.action;


import com.jcoder.hook.HookActionType;

/**
 * Hook 要执行的具体动作配置。
 *
 * Executor 根据 type() 选择对应的实现。
 */
public sealed interface HookAction
        permits PromptHookAction,
        CommandHookAction,
        HttpHookAction {

    HookActionType type();
}