package com.jcoder.hook.action;

import com.jcoder.hook.HookActionType;

/**
 * 生成一段临时 Prompt。
 *
 * 该动作只负责文本渲染，
 * 不直接调用 LLM，也不直接修改历史。
 */
public record PromptHookAction(
        String template
) implements HookAction {

    public PromptHookAction {
        if (template == null || template.isBlank()) {
            throw new IllegalArgumentException(
                    "prompt hook template is required"
            );
        }

        template = template.strip();
    }

    @Override
    public HookActionType type() {
        return HookActionType.PROMPT;
    }
}