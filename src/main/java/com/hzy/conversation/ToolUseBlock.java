package com.hzy.conversation;

import java.util.Map;

/**
 * 作者：亥子曜
 * -后之览者，亦将有感于斯文
 */
public record ToolUseBlock(String toolUseId,
                           String toolName,
                           Map<String,Object> arguments) {
}
