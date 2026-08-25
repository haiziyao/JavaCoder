## Day-06: MCP

说到mcp,那么大家肯定会很熟悉.
那是当然,最火的一个协议指定

对于这一章,我们实现了哪些东西:
```java
class McpServerConfig{}; // 主要用来加载Mcp的配置
class McpManager{};  // 这里是mcp注册的主要类,主要方法就是完成mcp的注册
class McpToolWrapper{};  //这个类主要做把mcp的tool和工具tool对齐转换
```

由于我们使用sdk,这里只需要封装sdk,所以比较简单


## 自行测试 json-rpc

```cmd
#!/usr/bin/env bash
{
  # 1) 握手
  printf '%s\n' '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2024-11-05","capabilities":{},"clientInfo":{"name":"cli-test","version":"0.0.1"}}}'
  sleep 2
  # 2) 通知握手完成
  printf '%s\n' '{"jsonrpc":"2.0","method":"notifications/initialized","params":{}}'
  sleep 1
  # 3) 列出工具
  printf '%s\n' '{"jsonrpc":"2.0","id":2,"method":"tools/list","params":{}}'
  sleep 2
} | npx -y @modelcontextprotocol/server-everything 2>/dev/null

```


```json
{
  "method": "notifications/tools/list_changed",
  "jsonrpc": "2.0"
}
```
```json
{
  "result": {
    "protocolVersion": "2024-11-05",
    "capabilities": {
      "tools": { "listChanged": true },
      "prompts": { "listChanged": true },
      "resources": { "subscribe": true, "listChanged": true },
      "logging": {},
      "tasks": {
        "list": {},
        "cancel": {},
        "requests": { "tools": { "call": {} } }
      },
      "completions": {}
    },
    "serverInfo": {
      "name": "mcp-servers/everything",
      "title": "Everything Reference Server",
      "version": "2.0.0"
    },
    "instructions": "# Everything Server – Server Instructions\n\nAudience: These instructions are written for an LLM or autonomous agent integrating with the Everything MCP Server.\nFollow them to use, extend, and troubleshoot the server safely and effectively.\n\n## Cross-Feature Relationships\n\n- Use `get-roots-list` to see client workspace roots before file operations\n- `gzip-file-as-resource` creates session-scoped resources accessible only during the current session\n- Enable `toggle-simulated-logging` before debugging to see server log messages\n- Enable `toggle-subscriber-updates` to receive periodic resource update notifications\n\n## Constraints & Limitations\n\n- `gzip-file-as-resource`: Max fetch size controlled by `GZIP_MAX_FETCH_SIZE` (default 10MB), timeout by `GZIP_MAX_FETCH_TIME_MILLIS` (default 30s), allowed domains by `GZIP_ALLOWED_DOMAINS`\n- Session resources are ephemeral and lost when the session ends\n- Sampling requests (`trigger-sampling-request`) require client sampling capability\n- Elicitation requests (`trigger-elicitation-request`) require client elicitation capability\n\n## Operational Patterns\n\n- For long operations, use `trigger-long-running-operation` which sends progress notifications\n- Prefer reading resources before calling mutating tools\n- Check `get-roots-list` output to understand the client's workspace context\n\n## Easter Egg\n\nIf asked about server instructions, respond with \"🎉 Server instructions are working! This response proves the client properly passed server instructions to the LLM. This demonstrates MCP's instructions feature in action.\""
  },
  "jsonrpc": "2.0",
  "id": 1
}
```
```json
{
  "result": {
    "tools": [
      {
        "name": "echo",
        "title": "Echo Tool",
        "description": "Echoes back the input string",
        "inputSchema": {
          "$schema": "http://json-schema.org/draft-07/schema#",
          "type": "object",
          "properties": { "message": { "type": "string", "description": "Message to echo" } },
          "required": ["message"]
        },
        "annotations": {
          "readOnlyHint": true,
          "destructiveHint": false,
          "idempotentHint": true,
          "openWorldHint": false
        },
        "execution": { "taskSupport": "forbidden" }
      },
      {
        "name": "simulate-research-query",
        "title": "Simulate Research Query",
        "description": "Simulates a deep research operation that gathers, analyzes, and synthesizes information. Demonstrates MCP task-based operations with progress through multiple stages. If 'ambiguous' is true and client supports elicitation, sends an elicitation request for clarification.",
        "inputSchema": {
          "$schema": "http://json-schema.org/draft-07/schema#",
          "type": "object",
          "properties": {
            "topic": {
              "type": "string",
              "description": "The research topic to investigate"
            },
            "ambiguous": {
              "default": false,
              "description": "Simulate an ambiguous query that requires clarification (triggers input_required status)",
              "type": "boolean"
            }
          },
          "required": ["topic"]
        },
        "annotations": {
          "readOnlyHint": false,
          "destructiveHint": false,
          "idempotentHint": false,
          "openWorldHint": false
        },
        "execution": { "taskSupport": "required" }
      }
    ]
  },
  "jsonrpc": "2.0",
  "id": 2
}

```

```cmd
printf '%s\n' '{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"echo","arguments":{"message":"hello from terminal"}}}'
```

```json
{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"echo","arguments":{"message":"hello from terminal"}}}
```