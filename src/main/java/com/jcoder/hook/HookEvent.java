package com.jcoder.hook;

/**
 * Harness 主流程中可以触发 Hook 的事件。
 */
public enum HookEvent {

    /**
     * 一次用户请求刚进入 Agent，
     * 还没有构建本轮 Prompt。
     */
    TURN_START(false, false),

    /**
     * 一次用户请求已经完成。
     */
    TURN_END(false, false),

    /**
     * 权限检查已经通过，
     * 但工具还没有真正执行。
     */
    PRE_TOOL_USE(true, true),

    /**
     * 工具已经执行完成，
     * 此时只能观察结果，不能撤销工具。
     */
    POST_TOOL_USE(true, false);

    private final boolean toolEvent;
    private final boolean rejectionAllowed;

    HookEvent(
            boolean toolEvent,
            boolean rejectionAllowed
    ) {
        this.toolEvent = toolEvent;
        this.rejectionAllowed = rejectionAllowed;
    }

    public boolean isToolEvent() {
        return toolEvent;
    }

    public boolean allowsRejection() {
        return rejectionAllowed;
    }
}