package com.jcoder.run;

import com.jcoder.message.ToolCallBlock;

import java.util.List;

/**
 * 作者：亥子曜
 * -后之览者，亦将有感于斯文
 */
public record TurnResult(
        String content,
        List<ToolCallBlock> toolCalls
) {
}
