package com.jcoder.llm.model;

import com.jcoder.message.ToolCall;

import java.util.List;

/**
 * 作者：亥子曜
 * -后之览者，亦将有感于斯文
 */
public record ResponseBody(
        String requestId,
        String content,
        List<ToolCall> toolCalls,
        Usage usage,
        String finishReason
) {}
