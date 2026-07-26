package com.jcoder.message;

import java.util.Map;

/**
 * 作者：亥子曜
 * -后之览者，亦将有感于斯文
 */
public record ToolCallBlock(
        String toolId,
        String type,
        String toolName,
        Map<String,Object> params
) {
}
