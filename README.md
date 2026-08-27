# Day 7：上下文管理

Day 6 已经把 MCP 工具接入 MyCoder 的统一 `Tool` 调用链。Day 7 不再继续增加工具，而是处理 Agent 长时间运行时一定会遇到的问题：上下文会越来越大，最终超过模型的 context window。

本阶段保持学习项目的精简原则，只实现四层必要能力：上下文测量、工具结果落盘、LLM 摘要、失败恢复。不引入复杂的 Session、长期记忆、文件快照或多 Agent 状态。

## 执行流程

```text
Conversation 中产生新的消息和工具结果
  → 大工具结果写入磁盘，历史中只保留预览与文件路径
  → PromptBuilder 构造本轮完整 Prompt
  → 估算 system、messages、tool calls、tool results、tools schema 的 token
  → 为本轮模型输出预留 maxOutputTokens
  → 达到输入预算 85% 时，调用一次 LLM 摘要旧消息
  → 保留摘要以及最近 6 条原始消息
  → 重新构造 Prompt、重新计算预算
  → 未超过硬上限才发送给主模型
```

## 1. 上下文测量

`ContextTokenEstimator` 对完整 `PromptContent` 做启发式估算，覆盖：

- system prompt；
- user、assistant 和 tool 消息；
- assistant tool call 的名称与参数；
- tool result；
- 工具定义及 JSON Schema。

`ContextBudget` 使用下面的关系计算本轮预算：

```text
inputLimit = contextWindow - maxOutputTokens
compactThreshold = inputLimit × 85%
```

这里必须给输出预留空间。只判断输入是否小于 `contextWindow`，仍可能导致模型没有空间生成回答。

每轮计算后，CLI 会输出估算输入、输入上限和剩余 token。这个数值用于稳定控制 Harness，不代表供应商返回的精确 token usage。

## 2. 大工具结果落盘

`ToolResultOffloader` 在构造 Prompt 前检查历史工具结果：

| 条件 | 当前值 | 行为 |
|---|---:|---|
| 单个工具结果超过限制 | 50,000 字符 | 将完整结果写入磁盘 |
| 同一条 tool message 聚合超过限制 | 200,000 字符 | 优先将较大的结果写入磁盘，直到低于限制 |
| 历史中保留的预览 | 2,000 字符 | 保留预览、文件路径和原 tool call ID |

完整内容保存在：

```text
.mycoder/context/tool-results/
```

写盘失败时会保留原始工具结果，避免上下文管理反过来造成数据丢失。已经落盘的结果不会被重复处理。

运行时文件不应提交到 Git，请在 `.gitignore` 中加入：

```gitignore
.mycoder/
```

## 3. LLM 摘要压缩

达到软阈值后，`ContextCompactor` 使用现有 `LLMClient.stream()` 发起一轮独立摘要请求。

摘要请求具有以下约束：

- `tools` 传空列表，摘要模型不能调用工具；
- 只发送准备删除的旧消息，不重复发送最近消息；
- 要求先整理信息，最终只输出 `<summary>...</summary>`；
- 保留用户目标、明确约束、技术决定、相关文件、测试结果、错误修复和下一步；
- 默认保留最近 6 条原始消息；
- 不拆开 assistant tool call 与对应的 tool result；
- 至少存在 4 条可摘要的旧消息才调用 LLM；
- 摘要完整成功前不修改原 Conversation。

摘要成功后，历史结构变为：

```text
一条 context-summary 消息
+ 最近 6 条原始消息
```

Agent 随后重新构造真实 Prompt 并重新计算预算。当前策略一轮最多执行一次摘要；如果可摘要的旧前缀不足，会保持原历史。

## 4. 超时与熔断

摘要流使用 90 秒事件等待超时。如果队列在 90 秒内没有产生下一个事件，会抛出 `context summary timed out`，原始历史保持不变。

`CompactionCircuitBreaker` 防止摘要服务异常时每轮都额外调用一次 LLM：

```text
摘要成功 → 连续失败次数清零
摘要失败 → 连续失败次数加一
连续失败 3 次 → 禁止自动摘要
```

熔断只会停止自动摘要，不会绕过硬上限检查。如果 Prompt 已超过输入上限，Agent 仍会停止本轮请求。

当前 90 秒是每次 `BlockingQueue.poll` 的空闲等待时间，不是整个摘要请求的总 deadline。只要模型持续返回 delta，摘要总耗时可以超过 90 秒。

## 5. 手动 `/compact`

在 CLI 输入：

```text
/compact
```

该命令不会作为 user message 发给模型，而是直接调用 `Agent.compactNow()`：

1. 重置自动摘要熔断器；
2. 尝试摘要可压缩的旧消息；
3. 成功时显示消息数和历史 token 估算的前后变化；
4. 消息不足时提示没有可压缩的旧前缀；
5. 失败时保留原历史，并从一次失败重新累计。

手动压缩后不需要立即缓存 Prompt。下一次正常运行 Agent 时会重新执行 `PromptBuilder.build()`。

## 主要实现文件

```text
src/main/java/com/jcoder/context/
├── ContextTokenEstimator.java
├── ContextBudget.java
├── ToolResultOffloader.java
├── ContextCompactor.java
└── CompactionCircuitBreaker.java

src/main/java/com/jcoder/agent/Agent.java
src/main/java/com/jcoder/agent/AgentEvent.java
src/main/java/com/jcoder/ui/CmdUI.java
```

## 构建与测试

项目目标版本是 Java 21。不要使用系统默认 JDK 17 编译。

PowerShell：

```powershell
$env:JAVA_HOME = 'C:\Users\17542\.jdks\ms-21.0.11'
$env:Path = "$env:JAVA_HOME\bin;$env:Path"

java -version
& 'D:\apache-maven-3.6.3\bin\mvn.cmd' clean test
```

2026-08-26 的完整验证结果：

```text
Java: Microsoft OpenJDK 21.0.11
生产源码: 55
测试源码: 12
Tests run: 36
Failures: 0
Errors: 0
Skipped: 1（MCP stdio 集成测试默认跳过）
BUILD SUCCESS
```

上下文测试覆盖：

- 完整 Prompt token 估算和输出预算预留；
- 单结果与聚合工具结果落盘；
- 写盘失败回退、预览、幂等和路径隔离；
- 摘要请求禁用工具、只摘要旧前缀；
- 最近消息及 tool call/result 边界保留；
- 摘要失败或超时时历史不变；
- 三次失败熔断、成功清零和手动 reset；
- 手动压缩成功及失败重新累计。

真实 stdio MCP 集成测试默认不启动。如需单独验证，需要显式启用对应的 `mcp.integration` 测试参数。

## 当前边界

Day 7 解决的是单进程内的上下文控制，目前没有实现：

- 精确的供应商 token usage；
- 压缩后仍超过软阈值时的同轮二次压缩；
- 整个摘要请求的总 deadline；
- Conversation 跨进程保存和恢复；
- 长期记忆检索与合并；
- 完整 Session、RecoveryState 和文件快照。

这些能力只有在后续学习目标真正需要时再增加，避免把上下文管理变成难以理解的框架。

---

# Day 8：Slash Command

Day 8 将原本散落在 `CmdUI` 中的 `/permission`、`/compact` 判断改造成统一的 Slash Command 分发链路。LOCAL 命令只在本地执行；PROMPT 命令会展开成真正的用户请求，再进入正常 Agent Harness。

## 执行链路

```text
CmdUI 读取输入
  → 以 / 开头时交给 SlashCommandRegistry
  → CommandInvocation 拆分命令名与参数
  → 按规范名称或别名查找 SlashCommand
  → Handler 使用 CommandContext 执行
  → CommandResult 返回 LOCAL 或 PROMPT
      ├─ LOCAL：CmdUI 本地显示并 continue
      └─ PROMPT：展开文本写入 Conversation，再调用 Agent
```

因此未知 Slash Command 也不会误进入 Conversation：

```text
/not-exists
→ Unknown command 本地错误
→ 不调用 LLM
→ 不增加对话历史
```

## 命令内核

```text
src/main/java/com/jcoder/command/
├── CommandInvocation.java
├── CommandContext.java
├── CommandResult.java
├── SlashCommand.java
├── SlashCommandRegistry.java
└── DefaultCommands.java
```

- `CommandInvocation`：解析 `/name arguments`，规范化命令名，并保留参数内部文本。
- `CommandContext`：只向本地命令提供 `Agent` 与 `ConversationManager`。
- `CommandResult`：统一成功、错误、输出文本和 LOCAL/PROMPT 交付方式；canonical constructor 保证失败或空白结果不能作为 PROMPT 提交。
- `SlashCommand`：保存规范名称、说明、别名和 Handler。
- `SlashCommandRegistry`：负责注册、冲突检查、查找、排序和执行。
- `DefaultCommands`：集中注册 MyCoder 内置命令。

Registry 会在修改内部索引前完成全部冲突检查，防止一次失败注册留下半条名称或别名。

## 当前内置命令

| 命令 | 别名 | 行为 |
|---|---|---|
| `/help [command]` | `/h`、`/?` | 显示命令列表或单个命令详情 |
| `/permission` | `/perm` | 循环切换工具权限模式 |
| `/compact` | `/c` | 手动摘要旧对话并重置摘要熔断状态 |
| `/clear` | 无 | 清空当前内存历史、旧 Prompt 和摘要熔断状态 |
| `/status` | `/s` | 显示 Agent、权限、消息、历史 token、工具和窗口状态 |
| `/review [focus]` | 无 | 展开为代码审查 Prompt，并进入正常 Agent/LLM 链路 |

`/clear` 只清理当前进程中的 Conversation，不删除 `.mycoder/context/tool-results/` 文件，也不执行其他磁盘删除。

`/status` 中的 `History tokens` 只估算历史消息，不包含 system prompt、环境上下文和工具 Schema，因此使用 `~` 标记，而不是把它描述成完整请求 token。

## UI 分发原则

```text
Slash Command → 本地执行，不进入 Conversation
PROMPT Command→ 展开后进入 Conversation → Agent → LLM
exit          → 退出 CLI
空输入        → 忽略
普通文本      → Conversation → Agent → LLM
```

`CmdUI` 支持注入不同的 `SlashCommandRegistry`，测试可以使用自定义命令验证分发，而不需要连接真实 LLM。

## Day 8 测试状态

2026-08-27 使用 Microsoft OpenJDK 21.0.11 执行：

```powershell
& 'D:\apache-maven-3.6.3\bin\mvn.cmd' clean test
```

结果：

```text
生产源码: 61
测试源码: 18
Tests run: 68
Failures: 0
Errors: 0
Skipped: 1（MCP stdio 集成测试默认跳过）
BUILD SUCCESS
```

命令测试覆盖解析、大小写规范化、参数保留、别名、四类注册冲突、失败注册不污染、帮助输出、权限切换、手动压缩、清空历史、旧 Prompt 与熔断重置、状态输出、LOCAL/PROMPT 类型不变量、`/review` 中文关注点保留，以及 UI 将展开 Prompt 写入历史并提交给 LLM。

## 当前边界

Day 8 已完成内置 LOCAL 命令和一个 PROMPT 命令的完整分发闭环。当前没有实现参考项目中的 Markdown/YAML 动态命令加载器、用户级命令覆盖、热加载和 TUI 自动补全；这些能力不是验证 Slash Command Harness 思想的前置条件，只有出现真实自定义命令需求时再增加。
