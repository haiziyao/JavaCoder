package com.jcoder.tool;

import java.util.Map;

/**
 * 作者：亥子曜
 * -后之览者，亦将有感于斯文
 */
public record ToolReturnDefinition(
        String type,

        // "param" : { "type" : "number"}
        Map<String,String> properties
) {
}
