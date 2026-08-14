# MyCoder

MyCoder 是一个用于学习 AI Coding Agent 工作原理的 Java 项目。

当前阶段关注三个核心问题：如何组织 System Prompt、如何把对话和工具转换成 API 请求，以及 Agent 如何在多轮工具调用中持续工作。项目以简单、可读、可运行作为主要目标，不提前实现复杂的平台能力。

## 当前功能

- 支持 OpenAI 兼容协议和 DeepSeek 协议。
- 支持流式文本、工具调用和工具结果回传。
- 内置 `ReadFile`、`EditFile`、`WriteFile`、`Bash`、`Glob`、`Grep` 六个工具。
- 使用七个独立文件维护 System Prompt。
- 统一组装 `system / messages / tools` 三部分请求内容。
- 将环境上下文和模式提醒作为 `user + <system-reminder>` 动态注入。
- 支持 `NORMAL`、`PLAN`、`EXECUTE_PLAN` 三种 Agent 模式。
- UI 可以查看当前 Prompt 快照和最后一次请求 JSON。

## 运行环境

- JDK 21 或更高版本
- Maven 3.6.3 或更高版本

本项目使用 JDK 23 验证，编译目标版本为 Java 21。

不修改系统环境变量时，可以在当前 PowerShell 会话临时指定 JDK：

```powershell
$env:JAVA_HOME='C:\Users\17542\.jdks\openjdk-23'
$env:Path="$env:JAVA_HOME\bin;$env:Path"
```

运行测试：

```powershell
mvn test
```

启动项目时，可以直接在 IDEA 中运行：

```text
src/main/java/com/jcoder/Main.java
```

## Provider 配置

配置文件位于 `src/main/resources/application.json`。

```json
{
  "providers": [
    {
      "name": "my-provider",
      "protocol": "deepseek",
      "baseUrl": "https://api.example.com",
      "apiKey": "your-api-key",
      "model": "your-model",
      "thinking": false,
      "contextWindow": 64000,
      "maxOutputTokens": 8192
    }
  ]
}
```

`protocol` 当前支持：

- `gpt`：使用 `OpenAIClient`。
- `deepseek`：使用 `DeepseekClient`。

不要把真实 API Key 提交到 Git。

## Prompt 组装结构

Prompt 的核心数据结构是 `PromptContent`：

```java
public record PromptContent(
        String system,
        List<Message> messages,
        List<ToolDefinition> tools
) {}
```

组装流程：

```text
sys_prompt/*.md
       ↓
ConfigManager → PromptConfig
       ↓
PromptBuilder
       ├── system: 七个静态 Prompt 模块
       ├── messages: 环境提醒 + 会话历史 + 模式提醒
       └── tools: 工具 JSON Schema
       ↓
PromptContent → LLMClient → API JSON
```

`PromptBuilder` 是唯一的完整 Prompt 组装入口。`RequestBodyHelper` 只负责把已经组装好的 `PromptContent` 序列化成 API 请求，不再保存会话、System Prompt 或工具状态。

## 七个 System Prompt 模块

文件位于 `src/main/resources/sys_prompt`：

| 优先级 | 文件 | 职责 |
|---:|---|---|
| 0 | `identity.md` | 定义 MyCoder 的身份和工作范围 |
| 10 | `behavior.md` | 约束与用户沟通和行动的方式 |
| 20 | `tool-usage.md` | 指导工具选择、顺序和配合关系 |
| 30 | `code-quality.md` | 控制代码质量和修改范围 |
| 40 | `security.md` | 提供提示词层面的安全约束 |
| 50 | `task-pattern.md` | 区分 Bug、功能、重构和解释任务的策略 |
| 60 | `output-style.md` | 约束最终回答格式和长度 |

这些内容属于稳定信息，统一进入 System Prompt。排序由 `PromptSection.priority` 决定。

Prompt 可以通过 `ConfigManager` 重新加载或修改：

```java
ConfigManager.reloadPrompts();

ConfigManager.savePrompt(
        PromptConfig.Section.BEHAVIOR,
        newContent
);
```

当前写回功能面向 IDEA 开发环境中的 classpath 资源。打包成 JAR 后，暂不支持直接修改 JAR 内部的 Prompt 文件。

## 动态上下文与 system-reminder

动态内容不拼接进 System Prompt，避免环境变化导致整个静态 Prompt 不稳定。

每次请求的消息顺序为：

```text
1. role=user：环境 system-reminder
2. ConversationManager 中的会话历史
3. role=user：当前模式 system-reminder（非 NORMAL 模式）
```

提醒统一使用以下格式：

```xml
<system-reminder>
动态上下文或运行时指令
</system-reminder>
```

环境上下文目前包含：

- 工作目录
- 操作系统
- CPU 架构
- Shell
- 当前日期

这些提醒只存在于本次 `PromptContent`，不会写入 `ConversationManager`。

## Agent 模式

```java
public enum AgentMode {
    NORMAL,
    PLAN,
    EXECUTE_PLAN
}
```

- `NORMAL`：正常分析和执行任务。
- `PLAN`：要求 Agent 只分析并生成计划，不修改项目。
- `EXECUTE_PLAN`：要求 Agent 按已确认的计划执行。

UI 可以通过以下接口切换模式：

```java
agent.setMode(AgentMode.PLAN);
AgentMode currentMode = agent.getMode();
```

在同一次 Agent Loop 中，第 1、6、11……轮注入完整模式提醒，其余轮次注入精简提醒。

目前模式只通过 Prompt 约束行为，尚未通过权限系统限制工具调用。

## UI 接口

UI 层通过统一接口启动 Agent：

```java
public interface UI {
    void run(Agent agent, ConversationManager conversationManager);
}
```

当前实现为 `CmdUI`。后续增加图形界面时，不需要修改 Agent 的运行入口。

提供给 UI 的状态接口：

```java
PromptContent prompt = agent.getCurrentPromptContent();
String requestJson = agent.getLastRequestJson();
AgentMode mode = agent.getMode();
```

