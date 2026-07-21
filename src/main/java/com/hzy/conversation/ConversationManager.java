package com.hzy.conversation;

import java.util.ArrayList;
import java.util.List;

/**
 * 作者：亥子曜
 * -后之览者，亦将有感于斯文
 */


public class ConversationManager {
    private final List<Message> history = new ArrayList<>();

    // TODO: 加入长期记忆


    public List<Message> getHistoryCopy() {
        return List.copyOf(history);
    }
    public List<Message> getHistoryMut() {
        return history;
    }

    public void addUserMessage(String content){
        history.add(new Message("user", content));
    }
    public void addAssistantMessage(String content){
        history.add(new Message("assistant", content));
    }

    public void addAddAssistantMessageFull(String content,List<ThinkingBlock> thinkingBlocks,
                                 List<ToolUseBlock> toolUses){
        var msg = new Message("assistant", content);
        msg.setThinkingBlocks(thinkingBlocks);
        msg.setToolUses(toolUses);
        history.add(msg);
    }

    public void addMessageToolUses(String content,List<ToolUseBlock> toolUses){
        var msg = new Message("assistant", content);
        msg.setToolUses(toolUses);
        history.add(msg);
    }

    public void addMessageToolResults(List<ToolResultBlock> toolResults){
        var msg = new Message("role","");
        msg.setToolResults(toolResults);
        history.add(msg);
    }
}

