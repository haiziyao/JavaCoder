package com.jcoder.message;

/**
 * 作者：亥子曜
 * -后之览者，亦将有感于斯文
 */
public record ToolResult(
        String toolId,
        String content,
        boolean isError
) {
}
