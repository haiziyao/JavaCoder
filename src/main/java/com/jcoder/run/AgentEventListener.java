package com.jcoder.run;

import java.util.Map;

/** Agent 运行事件，UI 与命令行可以用不同方式展示这些事件。 */
public interface AgentEventListener {
    default void onStatus(String status) { }
    default void onContent(String delta) { }
    default void onToolStart(String name, Map<String, Object> arguments) { }
    default void onToolEnd(String name, String output, boolean error) { }
    default void onDone() { }
}
