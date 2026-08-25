package com.jcoder.mcp;

import com.jcoder.config.McpServerConfig;
import com.jcoder.tool.Tool;
import com.jcoder.tool.ToolExecuteResult;
import com.jcoder.tool.ToolRegister;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@EnabledIfSystemProperty(named = "mcp.integration", matches = "true")
class McpStdioIntegrationTest {

    @Test
    @Timeout(value = 90, unit = TimeUnit.SECONDS)
    void connectsListsAndCallsEchoTool() {
        McpServerConfig config = new McpServerConfig(
                "everything",
                "npx",
                List.of("-y", "@modelcontextprotocol/server-everything"),
                null,
                Map.of(),
                Map.of()
        );

        ToolRegister registry = ToolRegister.createDefault();
        McpManager manager = new McpManager(List.of(config));

        try {
            List<String> errors = manager.connectAndRegister(registry);

            assertTrue(errors.isEmpty(), () -> String.join(System.lineSeparator(), errors));
            assertEquals(1, manager.connectedServerCount());

            Tool echo = registry.get("mcp__everything__echo");
            assertNotNull(
                    echo,
                    () -> "已注册工具：" + registry.listTools().stream().map(Tool::name).toList()
            );

            ToolExecuteResult result = echo.execute(
                    Map.of("message", "hello from MyCoder")
            );

            assertFalse(result.isError(), result.output());
            assertTrue(result.output().contains("hello from MyCoder"), result.output());
        } finally {
            manager.shutdown();
        }
    }
}
