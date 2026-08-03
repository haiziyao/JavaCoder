package com.jcoder.llm.model;

import java.util.Map;

/**
 * 作者：亥子曜
 * -后之览者，亦将有感于斯文
 */
public sealed interface StreamBlock {

    record ContentDelta(String content) implements StreamBlock {}

    // 主播思考之后我觉得这个可以去掉,工具信息较短,用不到SSE拼接,如果拼接的话又会很麻烦
    //record ToolCallDelta(String name,String arguments) implements StreamBlock {}

    record ToolCall(
            String toolId,
            String toolName,
            String type,
            Map<String, Object> arguments
    ) implements StreamBlock {}

    record StreamEnd(String finishReason) implements StreamBlock {}

    record StreamError(String msg) implements StreamBlock {}
}
