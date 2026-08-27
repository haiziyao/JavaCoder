package com.jcoder.message;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 作者：亥子曜
 * -后之览者，亦将有感于斯文
 */
public class ConversationManager {
    private final List<Message> history = new ArrayList<Message>();


    public List<Message> getHistoryCopy() {
        return List.copyOf(history);
    }
    public List<Message> getHistoryMut() {
        return history;
    }

    // TODO:
    public void injectLongTermMemory(){

    }

    public void addMessage(Message message) {
        history.add(message);
    }

    public void addUserMsg(String msg) {
        history.add(new Message("user", msg));
    }

    public void addAssistantMsg(String msg) {
        history.add(new Message("assistant", msg));
    }

    public void addToolCallsMsg(List<ToolCallBlock> toolCalls) {
        Message msg = new Message("assistant", "");
        msg.setToolCalls(toolCalls);
        history.add(msg);
    }
    public void addToolResultsMsg(List<ToolResult> toolResults) {
        Message msg = new Message("tool", "");
        msg.setToolResults(toolResults);
        history.add(msg);
    }


    public int size() {
        return history.size();
    }

    public void replaceHistory(List<Message> messages) {
        Objects.requireNonNull(messages, "messages");

        List<Message> replacement = List.copyOf(messages);

        history.clear();
        history.addAll(replacement);
    }

    public int clear() {
        int removedMessages = history.size();
        history.clear();
        return removedMessages;
    }

}
