package com.jcoder.tool;

import java.util.List;
import java.util.Map;

/**
 * 作者：亥子曜
 * -后之览者，亦将有感于斯文
 */
public record ToolParamDefinition(
        String type,
        String description,
        // default , enum 等等信息
        Map<String,Object> others
) {
}
