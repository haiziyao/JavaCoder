package com.jcoder.hook;

/**
 * Hook 自身执行失败时的处理策略。
 */
public enum HookErrorPolicy {

    /**
     * 记录 Hook 错误，但继续原来的 Agent 流程。
     */
    CONTINUE,

    /**
     * 拒绝即将发生的行为。
     *
     * 第一版只允许 PRE_TOOL_USE 使用。
     */
    REJECT
}