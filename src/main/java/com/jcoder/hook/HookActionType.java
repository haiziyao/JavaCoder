package com.jcoder.hook;

/**
 * Hook 匹配后要执行的动作类型。
 */
public enum HookActionType {

    /**
     * 渲染一段临时提示文本。
     * 它本身不会调用 LLM，也不会修改 Conversation。
     */
    PROMPT,

    /**
     * 执行本地系统命令。
     */
    COMMAND,

    /**
     * 发送 HTTP 请求。
     */
    HTTP
}