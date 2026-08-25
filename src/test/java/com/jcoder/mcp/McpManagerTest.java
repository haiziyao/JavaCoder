package com.jcoder.mcp;

import com.jcoder.config.McpServerConfig;
import com.jcoder.tool.ToolRegister;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class McpManagerTest {

    @Test
    void rejectsConfigWithoutTransport() {
        McpServerConfig config = new McpServerConfig(
                "broken",
                null,
                List.of(),
                null,
                Map.of(),
                Map.of()
        );

        McpManager manager = new McpManager(List.of(config));
        List<String> errors = manager.connectAndRegister(new ToolRegister());

        assertEquals(1, errors.size());
        assertTrue(errors.getFirst().contains("exactly one"));
        assertEquals(0, manager.connectedServerCount());
    }

    @Test
    void rejectsConfigWithBothTransports() {
        McpServerConfig config = new McpServerConfig(
                "ambiguous",
                "npx",
                List.of(),
                "http://127.0.0.1/mcp",
                Map.of(),
                Map.of()
        );

        McpManager manager = new McpManager(List.of(config));
        List<String> errors = manager.connectAndRegister(new ToolRegister());

        assertEquals(1, errors.size());
        assertTrue(errors.getFirst().contains("exactly one"));
        assertEquals(0, manager.connectedServerCount());
    }

    @Test
    void keepsMissingEnvironmentPlaceholder() {
        String value = McpManager.resolveEnvVars(
                "Bearer ${MYCODER_TEST_MISSING_VALUE}"
        );

        assertEquals("Bearer ${MYCODER_TEST_MISSING_VALUE}", value);
    }

    @Test
    void adaptsWindowsCommandName() {
        boolean windows = System.getProperty("os.name", "")
                .toLowerCase()
                .contains("win");

        assertEquals(windows ? "npx.cmd" : "npx", McpManager.windowsSafe("npx"));
        assertEquals("custom-command", McpManager.windowsSafe("custom-command"));
    }

    @Test
    void acceptsEmptyConfigList() {
        McpManager manager = new McpManager(List.of());
        List<String> errors = manager.connectAndRegister(new ToolRegister());

        assertTrue(errors.isEmpty());
        assertEquals(0, manager.connectedServerCount());
    }
}
