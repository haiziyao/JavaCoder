package com.jcoder.config;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class McpConfigTest {

    @Test
    void loadsMcpServerConfig() {
        List<McpServerConfig> servers = ConfigManager.appConfig.mcpServers();

        assertNotNull(servers);
        assertFalse(servers.isEmpty());

        McpServerConfig context7 = servers.getFirst();

        assertEquals("context7", context7.name());
        assertEquals("npx", context7.command());
        assertEquals(List.of("-y", "@upstash/context7-mcp"), context7.args());
    }
}
