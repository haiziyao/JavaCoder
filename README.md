# MyCoder

MyCoder 是一个用于学习 AI Coding Agent 工作原理的 Java 项目。

当前阶段关注三个核心问题：

1. 如何组织 System Prompt。
2. 如何把对话和工具转换成 API 请求。
3. Agent 如何在多轮工具调用中持续工作，并在执行工具前做权限拦截。

项目以简单、可读、可运行作为主要目标，不提前实现复杂的平台能力。

## 技术栈

- Java 21+（作者用 JDK 23 验证，编译目标 21）。
- Maven 构建，`pom.xml`。
- 依赖很少：Jackson Databind（JSON）、JUnit Jupiter（测试）。
- 使用 JDK 自带 `HttpClient` + 虚拟线程进行流式请求，不依赖第三方 LLM SDK。

## 运行

```powershell
mvn test
```

启动入口：

```text
src/main/java/com/jcoder/Main.java
```

JDK 23 不在系统 PATH 时，可在当前会话临时指定：

```powershell
$env:JAVA_HOME='C:\Users\17542\.jdks\openjdk-23'
$env:Path="$env:JAVA_HOME\bin;$env:Path"
```

## 配置

配置文件位于 `src/main/resources/application.json`：

```json
{
  "providers": [
    {
      "name": "ds-provider",
      "protocol": "deepseek",
      "baseUrl": "https://api.deepseek.com",
      "apiKey": " ",
      "model": "deepseek-v4-flash",
      "thinking": false,
      "contextWindow": 128000,
      "maxOutputTokens": 8192
    }
  ]
}
```

- `protocol`：`gpt`（`OpenAIClient`）或 `deepseek`（`DeepseekClient`）。
- **不要把真实 API Key 提交到 Git**，本地运行时填回 `apiKey` 即可。

## 核心结构

| 包 / 类 | 职责 |
|---|---|
| `Main` | 入口，装配各组件 |
| `com.jcoder.agent` | `Agent`（多轮主循环）、`AgentEvent`、`AgentEventQueue` |
| `com.jcoder.config` | `ConfigManager`（application.json + 7 个 prompt）、`ProviderConfig` 等 |
| `com.jcoder.llm` | `LLMClient` 接口及工厂、`OpenAIClient`、`DeepseekClient`、`RequestBodyHelper` |
| `com.jcoder.message` | `Message`、`ConversationManager`、`ToolCallBlock`、`ToolResult` |
| `com.jcoder.permission` | 权限系统：`PermissionMode`、`PermissionChecker`、`PermissionResponse` |
| `com.jcoder.prompt` | `PromptContent`、`PromptBuilder`、`AgentMode`、环境上下文等 |
| `com.jcoder.tool` | `Tool` 接口、`ToolRegister`、工具 Schema 与参数定义 |
| `com.jcoder.tool.impl` | `ReadFile`、`WriteFile`、`EditFile`、`Bash`、`Glob`、`Grep` |
| `com.jcoder.ui` | `UI` 接口、`CmdUI`（Scanner 交互） |

## 执行链路

```text
Main
 ├─ 读取 application.json → ProviderConfig
 ├─ LLMClient.create(...)            // gpt / deepseek
 ├─ ToolRegister.createDefault()     // 六个工具
 ├─ new ConversationManager()
 ├─ new Agent(...)
 ├─ agent.setChecker(PermissionChecker)  // 权限检查
 └─ new CmdUI().run(...)

Agent 每轮：
 组装 Prompt → 流式请求 → 收集文本 + 工具调用
   → 没有工具调用：结束本轮
   → 有工具调用：逐个 executeWithPermission(tool, args)
        ├─ 权限检查通过 → tool.execute()
        └─ 权限拒绝 → 写回错误结果
   → 工具结果写回会话 → 下一轮
```

## System Prompt

七个静态模块位于 `src/main/resources/sys_prompt`，按 `priority` 排序拼接：

| 优先级 | 文件 | 职责 |
|---:|---|---|
| 0 | `identity.md` | 身份和工作范围 |
| 10 | `behavior.md` | 沟通和行动方式 |
| 20 | `tool-usage.md` | 工具选择、顺序和配合 |
| 30 | `code-quality.md` | 代码质量和修改范围 |
| 40 | `security.md` | 提示词层面安全约束 |
| 50 | `task-pattern.md` | Bug/功能/重构/解释任务策略 |
| 60 | `output-style.md` | 最终回答格式和长度 |

- `PromptBuilder` 是唯一完整 Prompt 组装入口，产出 `PromptContent(system, messages, tools)`。
- 环境上下文和模式提醒作为 `<system-reminder>` 动态注入，不写入会话历史。
- `RequestBodyHelper` 只做序列化，把对话和工具转成 OpenAI 兼容 JSON。

## Agent 模式

```java
public enum AgentMode { NORMAL, PLAN, EXECUTE_PLAN }
```

- `NORMAL`：正常分析和执行。
- `PLAN`：只分析生成计划，不修改项目（目前靠 Prompt 约束）。
- `EXECUTE_PLAN`：按已确认计划执行。

## 权限系统

位于 `com.jcoder.permission`，用于在工具执行前做拦截。

三个类：

| 类 | 职责 |
|---|---|
| `PermissionMode` | 权限模式 + 决策矩阵 `decide(ToolCategory)` |
| `PermissionChecker` | 分层检查，返回 `CheckResult(decision, reason)` |
| `PermissionResponse` | 用户对询问的回答：`ALLOW` / `ALLOW_ALWAYS` / `DENY` |

### 权限矩阵

| 模式 | READ | WRITE | COMMAND |
|---|---|---|---|
| `DEFAULT` | ALLOW | ASK | ASK |
| `ACCEPT_EDITS` | ALLOW | ALLOW | ASK |
| `PLAN` | 按 `DEFAULT` | 按 `DEFAULT` | 按 `DEFAULT` |
| `BYPASS` | ALLOW | ALLOW | ALLOW |

### 检查分层

`PermissionChecker.check(tool, args)` 命中即返回：

1. 危险命令（`rm -rf /`、`mkfs.`、`curl | sh` 等）→ `DENY`。
2. 路径沙箱：文件类工具目标必须在项目根目录内，否则 `ASK`（`BYPASS` 除外）。
3. 会话级「总是允许」规则 → `ALLOW`。
4. 模式矩阵兜底 → `ALLOW` / `ASK` / `DENY`。

按工具名抽取检查字段：

| 工具 | 检查字段 |
|---|---|
| `Bash` | `command` |
| `ReadFile` / `WriteFile` / `EditFile` | `file_path` |
| `Glob` / `Grep` | `pattern` |

### 权限询问闭环

决策为 `ASK` 时，`Agent` 通过 `AgentEvent.PermissionRequest` 把请求发给 UI，阻塞等待回答：

```text
check(...) → ASK
   → PermissionRequest 事件 → CmdUI 打印询问
   → 用户输入 y / a / n
   → ALLOW / ALLOW_ALWAYS / DENY
   → 放行 / 记住规则并放行 / 拒绝
```

`ALLOW_ALWAYS` 会把「工具名 + 内容」写入会话级 `allowAlwaysRules`。

### 动态切换模式

`PermissionChecker.mode` 是 `volatile` 字段，Agent 线程读、UI/外部线程写即可即时生效：

```java
agent.getChecker().setMode(PermissionMode.ACCEPT_EDITS);
```

命令行下输入 `/permission` 循环切换：

```text
> /permission
[权限] 当前模式 -> ACCEPT_EDITS
```

## 流式客户端

- `DeepseekClient` 解析 SSE：`data:` 行 → 文本 / 工具调用，`[DONE]` → `StreamEnd`。
- 增加了结束兜底：若流在未收到 `[DONE]` 时中断，补发 `StreamError`，避免 `Agent` 阻塞在队列读取上卡死。
- `OpenAIClient` 有 `finish_reason` 结束分支；两者差异可对照阅读。

## TODO

- `ConversationManager.injectLongTermMemory()` 目前是空实现。
- 上下文自动压缩、max_tokens 恢复、错误恢复尚未接入。
- 权限规则目前是会话级内存，未持久化到文件。
- 无 MCP、Skill、子 Agent、会话持久化等高级能力。
