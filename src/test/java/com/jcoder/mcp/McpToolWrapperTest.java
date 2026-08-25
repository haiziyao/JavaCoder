package com.jcoder.mcp;

import com.jcoder.tool.ToolCategory;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class McpToolWrapperTest {

    @Test
    void adaptsMcpToolDefinition() {
        Map<String, Object> querySchema = Map.of(
                "type", "string",
                "description", "查询内容",
                "enum", List.of("java", "python")
        );

        McpSchema.JsonSchema inputSchema = new McpSchema.JsonSchema(
                "object",
                Map.of("query", querySchema),
                List.of("query"),
                false,
                Map.of(),
                Map.of()
        );

        McpSchema.Tool sdkTool = McpSchema.Tool.builder()
                .name("search-docs")
                .description("搜索文档")
                .inputSchema(inputSchema)
                .build();

        McpToolWrapper wrapper = new McpToolWrapper(
                "context-7",
                sdkTool,
                null
        );

        assertEquals("mcp__context_7__search_docs", wrapper.name());
        assertEquals(ToolCategory.COMMAND, wrapper.category());

        var definition = wrapper.definition();
        assertEquals(List.of("query"), definition.required());

        var query = definition.properties().get("query");
        assertEquals("string", query.type());
        assertEquals("查询内容", query.description());
        assertTrue(query.others().containsKey("enum"));
        assertEquals(List.of("java", "python"), query.others().get("enum"));
    }
}
