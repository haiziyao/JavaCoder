package com.jcoder.command;

import com.jcoder.agent.Agent;
import com.jcoder.message.ConversationManager;

import java.util.Objects;

/**
 * 本地命令可以访问的最小运行状态。
 */
public record CommandContext(
        Agent agent,
        ConversationManager conversation
) {

    public CommandContext {
        Objects.requireNonNull(
                agent,
                "agent"
        );

        Objects.requireNonNull(
                conversation,
                "conversation"
        );
    }
}