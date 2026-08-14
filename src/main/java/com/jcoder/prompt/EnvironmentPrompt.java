package com.jcoder.prompt;

import com.jcoder.message.Message;

public final class EnvironmentPrompt {

    private EnvironmentPrompt() {
    }

    public static Message build(EnvironmentContext context) {
        String content = """
                # Environment

                - Working directory: %s
                - Operating system: %s
                - Architecture: %s
                - Shell: %s
                - Current date: %s
                """.formatted(
                context.workDir(),
                context.os(),
                context.arch(),
                context.shell(),
                context.date()
        );

        return SystemReminder.message(content);
    }
}
