
举个例子: 
开启一个session: 以后这个项目固定使用 Java 21，测试代码由你编写，我只写生产代码。

```
history[0]
role = user
content = 以后这个项目固定使用 Java 21，测试代码由你编写，我只写生产代码。

history[1]
role = assistant
content = ""
toolCalls = [
  {
    toolId: "call-1",
    type: "function",
    toolName: "ReadFile",
    params: {
      "file_path": "pom.xml"
    }
  }
]

history[2]
role = tool
content = ""
toolResults = [
  {
    toolId: "call-1",
    content: "<pom.xml>...</pom.xml>",
    isError: false
  }
]

history[3]
role = assistant
content = "我已经确认项目使用 Java 21，后续测试部分由我负责。"
```

### SessionStore 

> sessionManager.save();
把记忆持久化到<MyCoder项目根>/.mycoder/sessions/session-xxx.json
> 

```json
{
  "formatVersion": 1,
  "id": "session-1787817600000-a1b2c3d4",
  "createdAt": 1787817600000,
  "updatedAt": 1787817610000,
  "messages": [
    {
      "role": "user",
      "content": "以后这个项目固定使用 Java 21，测试代码由你编写，我只写生产代码。",
      "toolCalls": [],
      "toolResults": []
    },
    {
      "role": "assistant",
      "content": "",
      "toolCalls": [
        {
          "toolId": "call-1",
          "type": "function",
          "toolName": "ReadFile",
          "params": {
            "file_path": "pom.xml"
          }
        }
      ],
      "toolResults": []
    },
    {
      "role": "tool",
      "content": "",
      "toolCalls": [],
      "toolResults": [
        {
          "toolId": "call-1",
          "content": "<pom.xml>...</pom.xml>",
          "isError": false
        }
      ]
    },
    {
      "role": "assistant",
      "content": "我已经确认项目使用 Java 21，后续测试部分由我负责。",
      "toolCalls": [],
      "toolResults": []
    }
  ]
}
```
 

Session只是做了复制


### MemoryExtractor处理
自动记忆不会读取所有历史，也不保存工具输出。
本轮成功后，交给提取器的内容大概只有：

>user:
以后这个项目固定使用 Java 21，测试代码由你编写，我只写生产代码。
assistant:
我已经确认项目使用 Java 21，后续测试部分由我负责。

```json

{
  "memories": [
    {
      "scope": "PROJECT",
      "category": "CONSTRAINT",
      "key": "java.target_version",
      "content": "MyCoder must use Java 21.",
      "confidence": 0.99
    },
    {
      "scope": "USER",
      "category": "PREFERENCE",
      "key": "workflow.test_ownership",
      "content": "The assistant writes test code; the user writes and studies production code.",
      "confidence": 0.98
    }
  ]
}
```

保存到<MyCoder项目根>/.mycoder/memory/memories.json