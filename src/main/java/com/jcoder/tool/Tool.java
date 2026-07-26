package com.jcoder.tool;

import java.util.Map;

/**
 * 作者：亥子曜
 * -后之览者，亦将有感于斯文
 */
public interface Tool {

    String name();
    String description();
    ToolCategory category();

    ToolDefinition definition();

    ToolExecuteResult execute(Map<String,Object> args);
    default boolean shouldDefer(){return false;}

}
