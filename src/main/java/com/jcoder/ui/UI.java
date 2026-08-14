package com.jcoder.ui;

import com.jcoder.agent.Agent;
import com.jcoder.message.ConversationManager;

public interface UI {

    void run(Agent agent, ConversationManager conversationManager);
}
