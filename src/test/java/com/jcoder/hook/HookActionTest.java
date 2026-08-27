package com.jcoder.hook;

import com.jcoder.hook.action.CommandHookAction;
import com.jcoder.hook.action.HttpHookAction;
import com.jcoder.hook.action.PromptHookAction;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class HookActionTest {

    @Test
    void promptActionNormalizesTemplateAndReportsType() {
        PromptHookAction action = new PromptHookAction("  hello {{tool.name}}  ");

        assertEquals("hello {{tool.name}}", action.template());
        assertEquals(HookActionType.PROMPT, action.type());
    }

    @Test
    void promptActionRejectsMissingTemplate() {
        assertThrows(IllegalArgumentException.class, () -> new PromptHookAction(null));
        assertThrows(IllegalArgumentException.class, () -> new PromptHookAction(" \t\n "));
    }

    @Test
    void commandActionNormalizesCommandAndReportsType() {
        CommandHookAction action = new CommandHookAction("  echo meow  ");

        assertEquals("echo meow", action.command());
        assertEquals(HookActionType.COMMAND, action.type());
    }

    @Test
    void commandActionRejectsMissingCommand() {
        assertThrows(IllegalArgumentException.class, () -> new CommandHookAction(null));
        assertThrows(IllegalArgumentException.class, () -> new CommandHookAction(" \r\n "));
    }

    @Test
    void httpActionNormalizesDefaultsAndReportsType() {
        HttpHookAction action = new HttpHookAction(
                URI.create("https://localhost:8080/hook"),
                "  patch ",
                null,
                null
        );

        assertEquals(HookActionType.HTTP, action.type());
        assertEquals("PATCH", action.method());
        assertEquals(Map.of(), action.headers());
        assertEquals("", action.bodyTemplate());

        HttpHookAction defaultMethod = new HttpHookAction(
                URI.create("http://localhost/hook"),
                "  ",
                Map.of(),
                "body"
        );
        assertEquals("POST", defaultMethod.method());
    }

    @Test
    void httpActionDefensivelyCopiesHeaders() {
        Map<String, String> source = new LinkedHashMap<>();
        source.put("  X-Hook  ", "meow");

        HttpHookAction action = new HttpHookAction(
                URI.create("http://localhost/hook"),
                "POST",
                source,
                "{}"
        );
        source.put("X-Late", "change");

        assertEquals(Map.of("X-Hook", "meow"), action.headers());
        assertThrows(UnsupportedOperationException.class,
                () -> action.headers().put("X-New", "value"));
    }

    @Test
    void httpActionRejectsInvalidUrisAndMethods() {
        assertThrows(IllegalArgumentException.class,
                () -> new HttpHookAction(null, "POST", Map.of(), ""));
        assertThrows(IllegalArgumentException.class,
                () -> new HttpHookAction(URI.create("/relative"), "POST", Map.of(), ""));
        assertThrows(IllegalArgumentException.class,
                () -> new HttpHookAction(URI.create("file:///tmp/hook"), "POST", Map.of(), ""));
        assertThrows(IllegalArgumentException.class,
                () -> new HttpHookAction(URI.create("http://localhost"), "TRACE", Map.of(), ""));
    }

    @Test
    void httpActionRejectsInvalidHeaders() {
        Map<String, String> nullName = new HashMap<>();
        nullName.put(null, "value");
        Map<String, String> blankName = Map.of("  ", "value");
        Map<String, String> nullValue = new HashMap<>();
        nullValue.put("X-Hook", null);

        for (Map<String, String> headers : List.of(
                nullName,
                blankName,
                nullValue,
                Map.of("X-Hook\nInjected", "value"),
                Map.of("X-Hook", "value\r\nInjected: true")
        )) {
            assertThrows(IllegalArgumentException.class,
                    () -> new HttpHookAction(
                            URI.create("http://localhost/hook"),
                            "POST",
                            headers,
                            ""
                    ));
        }
    }
}
