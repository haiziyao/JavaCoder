package com.jcoder.mcp;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jcoder.tool.Tool;
import com.jcoder.tool.ToolCategory;
import com.jcoder.tool.ToolDefinition;
import com.jcoder.tool.ToolExecuteResult;
import com.jcoder.tool.ToolParamDefinition;
import com.jcoder.tool.ToolReturnDefinition;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

public final class McpToolWrapper implements Tool {

    private static final Pattern NON_ALNUM =
            Pattern.compile("[^a-zA-Z0-9_]");

    private final ObjectMapper objectMapper = new ObjectMapper();

    private final String serverName;
    private final McpSchema.Tool sdkTool;
    private final McpSyncClient client;

    public McpToolWrapper(
            String serverName,
            McpSchema.Tool sdkTool,
            McpSyncClient client
    ) {
        this.serverName = serverName;
        this.sdkTool = sdkTool;
        this.client = client;
    }

    @Override
    public String name() {
        return "mcp__"
                + sanitize(serverName)
                + "__"
                + sanitize(sdkTool.name());
    }

    @Override
    public String description() {
        return sdkTool.description() == null
                ? ""
                : sdkTool.description();
    }

    @Override
    public ToolCategory category() {
        return ToolCategory.COMMAND;
    }

    @Override
    public ToolDefinition definition() {
        Map<String, ToolParamDefinition> properties =
                new LinkedHashMap<>();

        List<String> required = List.of();

        McpSchema.JsonSchema inputSchema =
                sdkTool.inputSchema();

        if (inputSchema != null) {
            if (inputSchema.properties() != null) {
                for (Map.Entry<String, Object> entry
                        : inputSchema.properties().entrySet()) {

                    properties.put(
                            entry.getKey(),
                            toParamDefinition(entry.getValue())
                    );
                }
            }

            if (inputSchema.required() != null) {
                required = List.copyOf(inputSchema.required());
            }
        }

        return new ToolDefinition(
                name(),
                description(),
                properties,
                required,
                new ToolReturnDefinition(
                        "string",
                        Map.of()
                )
        );
    }

    @Override
    public ToolExecuteResult execute(Map<String, Object> args) {
        try {
            Map<String, Object> actualArgs =
                    args == null ? Map.of() : args;

            McpSchema.CallToolRequest request =
                    new McpSchema.CallToolRequest(
                            sdkTool.name(),
                            actualArgs
                    );

            McpSchema.CallToolResult result =
                    client.callTool(request);

            String output = extractText(result);

            boolean isError =
                    Boolean.TRUE.equals(result.isError());

            return isError
                    ? ToolExecuteResult.error(output)
                    : ToolExecuteResult.success(output);

        } catch (Exception e) {
            return ToolExecuteResult.error(
                    "MCP tool call failed: " + e.getMessage()
            );
        }
    }

    private ToolParamDefinition toParamDefinition(
            Object rawSchema
    ) {
        if (rawSchema == null) {
            return new ToolParamDefinition(
                    "string",
                    "",
                    Map.of()
            );
        }

        JsonNode node =
                objectMapper.valueToTree(rawSchema);

        String type =
                node.path("type").asText("string");

        String description =
                node.path("description").asText("");

        LinkedHashMap<String, Object> others =
                objectMapper.convertValue(
                        rawSchema,
                        new TypeReference<>() {
                        }
                );

        others.remove("type");
        others.remove("description");

        return new ToolParamDefinition(
                type,
                description,
                Map.copyOf(others)
        );
    }

    private static String extractText(
            McpSchema.CallToolResult result
    ) {
        if (result == null
                || result.content() == null
                || result.content().isEmpty()) {

            return "(no output)";
        }

        StringBuilder output = new StringBuilder();

        for (McpSchema.Content content : result.content()) {
            if (content instanceof McpSchema.TextContent text) {
                if (!output.isEmpty()) {
                    output.append('\n');
                }

                output.append(text.text());
            }
        }

        return output.isEmpty()
                ? "(no output)"
                : output.toString();
    }

    private static String sanitize(String name) {
        return NON_ALNUM
                .matcher(name)
                .replaceAll("_");
    }
}