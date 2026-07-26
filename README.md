# MyCoder

MyCoder 是一个使用 Java 编写的本地 AI 编程 Agent。它连接 OpenAI 兼容接口，能够在连续对话中读取、搜索、修改项目文件并执行命令。

本次改造为原有命令行程序增加了一套可直接使用的本地 Web UI，同时保留 CLI 模式。

## 作者感想
> Agent好像跑了20轮,可能触发最大限制了.但是实现的非常好,没有报错
> 我也不多说啥了.下面都是AI自己写的了

## 本次提示词

> 我现在需要给E:\Agent_Learning\Hzy_Code\MyCoder这个项目做一个UI系统,目前还只是命令行,请你实现一下.最后重写README.md,写我的提示词是啥,你做了什么

## 我做了什么

### 1. 增加本地 Web UI

- 新增深色、响应式的聊天工作台；
- 支持用户消息与 AI 消息展示；
- 支持 LLM 内容逐段流式呈现，而不是等待完整回答；
- 工具开始执行、执行完成、失败和输出均可视化；
- 支持 Enter 发送、Shift + Enter 换行；
- 提供常用任务快捷入口；
- 支持一键新建对话并清空后端上下文；
- 桌面端与移动端均有适配。

UI 页面位于：

```text
src/main/resources/ui/index.html
```

### 2. 增加轻量 Web 服务

新增 `WebServer`，基于 JDK 自带的 `HttpServer` 实现，不需要引入 Spring Boot 或额外前端框架。

提供接口：

| 接口 | 方法 | 用途 |
| --- | --- | --- |
| `/` | GET | 返回 UI 页面 |
| `/api/chat` | POST | 发起 Agent 对话，以 NDJSON 流返回运行事件 |
| `/api/clear` | POST | 清空当前会话上下文 |

服务仅监听 `127.0.0.1`，默认端口为 `8080`。

### 3. 抽离可复用 Agent 核心

原来的 Agent 循环全部写在 `Main` 中，只能向终端输出。现在新增：

- `CodingAgent`：负责对话上下文、模型流、工具调用与自动推进；
- `AgentEventListener`：将状态、内容增量和工具事件传递给不同界面；
- `ConversationManager.clear()`：支持新建会话。

因此 Web UI 和 CLI 能够共用同一套 Agent 执行逻辑，避免维护两套实现。

### 4. 保留命令行模式

默认启动 Web UI；传入 `--cli` 可继续使用原来的终端交互方式。

### 5. 兼容当前构建环境

将流式请求线程调整为普通后台线程，Web 服务采用缓存线程池。项目源码仍按 `pom.xml` 声明面向 Java 21 编译，同时相关新增代码也能由当前 Maven 所使用的 JDK 17 编译。

## 项目结构

```text
src/main/java/com/jcoder/
├── Main.java                    # 启动入口：Web UI / CLI
├── config/                      # 应用和模型配置
├── llm/                         # OpenAI 兼容客户端、SSE 解析
├── message/                     # 对话、工具调用和工具结果
├── run/
│   ├── CodingAgent.java         # Agent 执行核心
│   ├── AgentEventListener.java  # UI 事件接口
│   └── TurnResult.java
├── tool/                        # 工具定义、注册与实现
└── ui/
    └── WebServer.java           # 本地 Web 服务

src/main/resources/
├── application.json             # Provider 与提示词配置
└── ui/index.html                # Web UI
```

## 内置工具

| 工具 | 功能 |
| --- | --- |
| `ReadFile` | 读取文本文件 |
| `WriteFile` | 创建或覆盖文件 |
| `EditFile` | 精确替换文件内容 |
| `Glob` | 按 Glob 模式查找文件 |
| `Grep` | 使用正则搜索文件内容 |
| `Bash` | 执行 Shell 命令 |

## 环境要求

- JDK 21（与 `pom.xml` 的编译目标一致）；
- Maven 3.6+；
- 一个可用的 OpenAI Chat Completions 兼容服务。

> 如果 Maven 实际运行在较旧 JDK 上，请先检查 `mvn -version`。编译和运行最好统一使用 JDK 21，避免测试 class 文件版本不一致。

## 配置

编辑 `src/main/resources/application.json`，填写 Provider 的服务地址、API Key、模型和输出长度等参数。

请勿将真实 API Key 提交到公开仓库。建议在后续迭代中增加环境变量读取能力。

## 启动 Web UI

### 使用 Maven 编译

```bash
mvn clean package -DskipTests
```

### 启动

```bash
java -cp target/classes com.jcoder.Main
```

启动后程序会尝试自动打开浏览器，也可以手动访问：

```text
http://127.0.0.1:8080
```

### 指定端口

```bash
java -cp target/classes com.jcoder.Main --port 9090
```

然后访问 `http://127.0.0.1:9090`。

## 启动 CLI

```bash
java -cp target/classes com.jcoder.Main --cli
```

输入 `exit` 或 `quit` 退出。

## 工作流程

1. 用户在 UI 或 CLI 输入任务；
2. `CodingAgent` 将消息写入 `ConversationManager`；
3. `OpenAIClient` 调用模型并解析 SSE 流；
4. 文本增量实时发送到 UI；
5. 如果模型请求工具，`ToolRegister` 查找并执行对应工具；
6. 工具结果加入上下文，再次请求模型；
7. 循环执行，直到模型给出最终回答或达到最多 20 轮。

## 构建验证

本次实现执行过：

```bash
mvn -DskipTests package
```

主代码编译和打包成功。

在当前机器上执行完整 `mvn test` 时，Maven 使用的是 JDK 17，而测试 class 由 Java 21（class file version 65）编译，因此 Surefire 启动测试时报 `UnsupportedClassVersionError`。这是本地 Maven 运行 JDK 与测试字节码版本不一致造成的环境问题；将 `JAVA_HOME` 和 Maven 统一切换到 JDK 21 后再执行：

```bash
mvn clean test
```

## 说明

原来的 `src/main/resources/index.html` 是项目介绍页。本次真正的交互式 UI 位于 `src/main/resources/ui/index.html`，并由 `WebServer` 提供服务。
