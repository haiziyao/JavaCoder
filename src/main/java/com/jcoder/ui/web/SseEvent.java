package com.jcoder.ui.web;

import com.jcoder.agent.AgentEvent;

/**
 * 把 AgentEvent 序列化为 SSE 事件的桥接层。
 *
 * 作者：亥子曜
 * -后之览者，亦将有感于斯文
 */
public final class SseEvent {

    private SseEvent() {
    }

    /**
     * 将 AgentEvent 转换成 SSE 的 data 负载字符串（JSON）。
     * 前端根据 type 字段分发渲染。
     */
    public static String toJson(AgentEvent event) {
        return switch (event) {
            case AgentEvent.Text e -> json(
                    "type", "text",
                    "delta", e.delta()
            );
            case AgentEvent.ToolCall e -> json(
                    "type", "tool_call",
                    "id", e.id(),
                    "name", e.name(),
                    "args", argsToJson(e.args())
            );
            case AgentEvent.ToolResult e -> json(
                    "type", "tool_result",
                    "id", e.id(),
                    "error", String.valueOf(e.error()),
                    "output", e.output()
            );
            case AgentEvent.TurnComplete e -> json(
                    "type", "turn_complete",
                    "turn", String.valueOf(e.turn())
            );
            case AgentEvent.LoopComplete e -> json(
                    "type", "loop_complete",
                    "turns", String.valueOf(e.turns())
            );
            case AgentEvent.Error e -> json(
                    "type", "error",
                    "message", e.message()
            );
            case AgentEvent.Log e -> json(
                    "type", "log",
                    "message", e.message()
            );
        };
    }

    private static String argsToJson(java.util.Map<String, Object> args) {
        if (args == null || args.isEmpty()) {
            return "{}";
        }
        try {
            return Json.write(args);
        } catch (Exception e) {
            return "{}";
        }
    }

    /**
     * 手写 JSON 序列化，避免嵌套转义问题，同时保证任意值可打印。
     */
    private static String json(Object... kv) {
        StringBuilder sb = new StringBuilder();
        sb.append('{');
        for (int i = 0; i < kv.length; i += 2) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append('"').append(kv[i]).append("\":");
            sb.append(str((String) kv[i + 1]));
        }
        sb.append('}');
        return sb.toString();
    }

    /**
     * 把字符串转成带转义的 JSON 字符串字面量。
     */
    public static String str(String value) {
        if (value == null) {
            return "null";
        }
        StringBuilder sb = new StringBuilder(value.length() + 16);
        sb.append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                case '\b' -> sb.append("\\b");
                case '\f' -> sb.append("\\f");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        sb.append('"');
        return sb.toString();
    }
}
