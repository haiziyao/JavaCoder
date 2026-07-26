package com.jcoder.tool;

/**
 * 作者：亥子曜
 * -后之览者，亦将有感于斯文
 */
public record ToolExecuteResult(
        String output,
        boolean isError
) {

    public static ToolExecuteResult success(String output) {
        return new ToolExecuteResult(output, false);
    }
    public static ToolExecuteResult error(String output) {
        return new ToolExecuteResult(output, true);
    }
}
