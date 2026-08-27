package com.jcoder.command;

import com.jcoder.agent.Agent;
import com.jcoder.memory.MemoryService;
import com.jcoder.message.ConversationManager;
import com.jcoder.session.SessionManager;

import java.util.Objects;

public record CommandContext(
        Agent agent,
        ConversationManager conversation,
        SessionManager sessionManager,
        MemoryService memoryService
) {
    public CommandContext {
        Objects.requireNonNull(agent, "agent");
        Objects.requireNonNull(conversation, "conversation");
    }

    /** 保留 Day 8 测试和简单调用方的兼容构造器。 */
    public CommandContext(
            Agent agent,
            ConversationManager conversation
    ) {
        this(agent, conversation, null, null);
    }
}