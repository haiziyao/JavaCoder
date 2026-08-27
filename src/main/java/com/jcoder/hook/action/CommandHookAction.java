package com.jcoder.hook.action;

import com.jcoder.hook.HookActionType;

/**
 * 执行本地系统命令。
 *
 * command 是来自用户可信 Hook 配置的静态命令。
 *
 * 为了避免模型生成的工具参数造成命令注入，
 * 不把 HookContext 中的数据直接拼接进 command。
 *
 * CommandHookExecutor 会通过环境变量，
 * 把受限的事件数据传给子进程。
 */
public record CommandHookAction(
        String command
) implements HookAction {

    public CommandHookAction {
        if (command == null || command.isBlank()) {
            throw new IllegalArgumentException(
                    "command hook command is required"
            );
        }

        command = command.strip();
    }

    @Override
    public HookActionType type() {
        return HookActionType.COMMAND;
    }
}