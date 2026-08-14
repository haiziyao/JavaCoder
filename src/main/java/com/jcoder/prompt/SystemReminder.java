package com.jcoder.prompt;

import com.jcoder.message.Message;

public final class SystemReminder {

    private SystemReminder() {
    }

    public static Message message(String content) {
        return new Message("user", wrap(content));
    }

    public static String wrap(String content) {
        return "<system-reminder>\n" + content.strip() + "\n</system-reminder>";
    }
}
