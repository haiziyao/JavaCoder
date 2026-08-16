# 权限系统

本次更新为 MyCoder 增加了工具执行前的权限拦截，代码位于 `com.jcoder.permission` 包。

## 三个类

| 类 | 职责 |
|---|---|
| `PermissionMode` | 权限模式 + 决策矩阵 `decide(ToolCategory)` |
| `PermissionChecker` | 分层权限检查，返回 `CheckResult(decision, reason)` |
| `PermissionResponse` | 用户对权限询问的回答：`ALLOW` / `ALLOW_ALWAYS` / `DENY` |

## 权限矩阵

`PermissionMode.decide(category)` 根据「模式 × 工具类别」给出 `ALLOW` / `ASK` / `DENY`：

| 模式 | READ | WRITE | COMMAND |
|---|---|---|---|
| `DEFAULT` | ALLOW | ASK | ASK |
| `ACCEPT_EDITS` | ALLOW | ALLOW | ASK |
| `PLAN` | 按 `DEFAULT` | 按 `DEFAULT` | 按 `DEFAULT` |
| `BYPASS` | ALLOW | ALLOW | ALLOW |

## 检查分层

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

## 权限询问闭环

决策为 `ASK` 时，`Agent` 通过 `AgentEvent.PermissionRequest` 把请求发给 UI，阻塞等待用户回答：

```text
check(...) → ASK
   → PermissionRequest 事件 → CmdUI 打印询问
   → 用户输入 y / a / n
   → ALLOW / ALLOW_ALWAYS / DENY
   → 放行 / 记住规则并放行 / 拒绝
```

`ALLOW_ALWAYS` 会把「工具名 + 内容」写入会话级 `allowAlwaysRules`，本次会话内再次命中直接放行。

## 动态切换模式

`PermissionChecker.mode` 是 `volatile` 字段，Agent 线程读、UI/外部线程写即可即时生效：

```java
agent.getChecker().setMode(PermissionMode.ACCEPT_EDITS);
```

命令行下输入 `/permission` 循环切换：

```text
> /permission
[权限] 当前模式 -> ACCEPT_EDITS
```

## 接入点

- `Agent.executeWithPermission(...)`：在 `tool.execute()` 之前调用 `checker.check()`。
- `AgentEvent.PermissionRequest`：新增事件，携带 `CompletableFuture<PermissionResponse>` 给 UI 回填。
- `CmdUI`：处理 `PermissionRequest` 弹窗，并支持 `/permission` 命令。
- `Main`：装配 `new PermissionChecker(PermissionMode.DEFAULT, Path.of("").toAbsolutePath())`。
