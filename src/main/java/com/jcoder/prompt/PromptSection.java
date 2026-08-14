package com.jcoder.prompt;

public record PromptSection(
        String name,
        int priority,
        String content
) {
}
