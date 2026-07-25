package com.jcoder.message;

import java.util.List;

/**
 * 作者：亥子曜
 * -后之览者，亦将有感于斯文
 */
public class Message {
    private String role;
    private String content;

    private List<ToolCall> toolCalls;
    private List<ToolResult> toolResults;

    public Message(String role, String content) {
        this.role = role;
        this.content = content;
    }

    public String getRole() {
        return role;
    }

    public void setRole(String role) {
        this.role = role;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public List<ToolCall> getToolCalls() {
        return toolCalls;
    }

    public void setToolCalls(List<ToolCall> toolCalls) {
        this.toolCalls = toolCalls;
    }

    public List<ToolResult> getToolResults() {
        return toolResults;
    }

    public void setToolResults(List<ToolResult> toolResults) {
        this.toolResults = toolResults;
    }
}
