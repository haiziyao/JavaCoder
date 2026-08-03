package com.jcoder.agent;

import java.util.Map;

public sealed interface AgentEvent {
    record Text(String delta) implements AgentEvent {}
    record ToolCall(String id, String name, Map<String, Object> args) implements AgentEvent {}
    record ToolResult(String id, String output, boolean error) implements AgentEvent {}
    record TurnComplete(int turn) implements AgentEvent {}
    record LoopComplete(int turns) implements AgentEvent {}
    record Error(String message) implements AgentEvent {}
    record Log(String message) implements AgentEvent {}
}