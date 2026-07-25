package com.jcoder.llm.model;

/**
 * 作者：亥子曜
 * -后之览者，亦将有感于斯文
 */
public sealed interface StreamBlock {

    record ContentDelta(String content) implements StreamBlock {}

    record ToolCallDelta(String name,String arguments) implements StreamBlock {}

    record StreamEnd(String finishReason) implements StreamBlock {}

    record StreamError(String msg) implements StreamBlock {}
}
