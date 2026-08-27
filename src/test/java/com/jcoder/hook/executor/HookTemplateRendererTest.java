package com.jcoder.hook.executor;

import com.jcoder.hook.HookContext;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HookTemplateRendererTest {

    private final HookTemplateRenderer renderer = new HookTemplateRenderer();

    @Test
    void rendersEveryFixedToolVariable() {
        HookContext context = HookContext.postTool(
                "session-42", Path.of("."), "WriteFile", Map.of(),
                "saved", true, 27);

        String rendered = renderer.render(
                "{{ event }}|{{session_id}}|{{ working_directory }}|"
                        + "{{message}}|{{tool_name}}|{{tool_output}}|"
                        + "{{tool_error}}|{{tool_duration_ms}}",
                context
        );

        assertEquals(
                "post_tool_use|session-42|" + context.workingDirectory()
                        + "||WriteFile|saved|true|27",
                rendered
        );
    }

    @Test
    void rendersLifecycleMessageAndEmptyToolName() {
        HookContext context = HookContext.turnStart(
                "session-1", Path.of("."), "review this project");

        assertEquals("review this project|",
                renderer.render("{{message}}|{{tool_name}}", context));
    }

    @Test
    void rendersNamedToolArgumentsAndQuotesReplacementCharacters() {
        Map<String, Object> arguments = new LinkedHashMap<>();
        arguments.put("file-path", "application.json");
        arguments.put("count", 2);
        arguments.put("nullable", null);
        arguments.put("literal", "$1\\path");
        HookContext context = HookContext.preTool(
                "session-1", Path.of("."), "WriteFile", arguments);

        assertEquals(
                "application.json|2||$1\\path",
                renderer.render(
                        "{{args.file-path}}|{{ args.count }}|"
                                + "{{args.nullable}}|{{args.literal}}",
                        context
                )
        );
    }

    @Test
    void preservesPlainTextEmptyTextAndNonTemplateBraces() {
        HookContext context = HookContext.turnStart("", Path.of("."), "");

        assertEquals("plain {value} text", renderer.render("plain {value} text", context));
        assertEquals("", renderer.render("", context));
        assertEquals("}}", renderer.render("}}", context));
        assertEquals("event }}", renderer.render("event }}", context));
    }

    @Test
    void preservesNestedJsonThatIsNotATemplateVariable() {
        HookContext context = HookContext.turnStart("", Path.of("."), "");
        String json = "{\"outer\":{\"x\":1}}";

        assertEquals(json, renderer.render(json, context));
    }

    @Test
    void rejectsUnknownFixedVariableAndMissingArgument() {
        HookContext context = HookContext.preTool(
                "", Path.of("."), "ReadFile", Map.of("path", "README.md"));

        IllegalArgumentException unknown = assertThrows(
                IllegalArgumentException.class,
                () -> renderer.render("{{unknown}}", context));
        assertTrue(unknown.getMessage().contains("unknown hook template variable"));

        IllegalArgumentException missingArgument = assertThrows(
                IllegalArgumentException.class,
                () -> renderer.render("{{args.missing}}", context));
        assertTrue(missingArgument.getMessage().contains("unknown hook tool argument"));
    }

    @Test
    void rejectsMalformedTemplateVariables() {
        HookContext context = HookContext.turnStart("", Path.of("."), "");

        for (String malformed : new String[]{
                "{{", "{{ event", "{{ args. }}",
                "{{event!}}", "{{event}}{{"
        }) {
            assertThrows(
                    IllegalArgumentException.class,
                    () -> renderer.render(malformed, context),
                    malformed
            );
        }
    }

    @Test
    void requiresTemplateAndContext() {
        HookContext context = HookContext.turnStart("", Path.of("."), "");

        assertThrows(IllegalArgumentException.class, () -> renderer.render(null, context));
        assertThrows(NullPointerException.class, () -> renderer.render("text", null));
    }
}
