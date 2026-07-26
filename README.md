
 > 在第一章中,该测的都测了,这章主要在于实现和优化.
 
 
我们把上一章的测试结果粘贴过来, `tools`块的格式应该是啥样子的

``` json
{
  "type": "function",
  "function": {
    "name": "get_weather",
    "description": "查询指定城市的实时天气信息，包括温度、湿度和天气状况。适用于用户询问某地天气时调用。",
    "parameters": {
      "type": "object",
      "properties": {
        "city": {
          "type": "string",
          "description": "城市名称，如'北京'、'上海'。支持国内主要城市。",
          "enum": ["北京", "上海", "广州", "深圳"]  
        },
        "unit": {
          "type": "string",
          "description": "温度单位，可选摄氏度（metric）或华氏度（imperial），默认为metric。",
          "default": "metric"
        }
      },
      "required": ["city"]
    },
    "returns": {
      "type": "object",
      "properties": {
        "temperature": {"type": "number"},
        "humidity": {"type": "number"},
        "condition": {"type": "string"}
      }
    }
  }
}
```

所以我们可以开始自己写`ToolDefinition`的成员变量了


## 代码介绍

> 对于本章节来说,我不太会写过多的笔记,因为我对这个项目也是边摸索边实现的
> 我就主要说下整个流程

一个工具被调用的整个流程

* 工具定义: 
* 实现工具: 
* 工具发现:
* 工具注入: 主要注入提示词
* 工具执行:
* 结果返回:
* 重新调用LLM:

### 工具定义

这里可以直接看着上面的JSON串,自己封装,想封装成啥样都行,只要自己能做好Class和JSON转换就行

``` java
// 对于工具的职责,我们定义一个接口
public interface Tool {  
  
    String name();  
    String description();  
    ToolCategory category();  // READ,Write等,用来看tool的执行策略 
  
    ToolDefinition definition();  
  
    ToolExecuteResult execute(Map<String,Object> args);  
    default boolean shouldDefer(){return false;}  
  
}

public record ToolDefinition(  
        String name,  
        String description,  
        Map<String, ToolParamDefinition> properties,  
        List<String> required,  
        ToolReturnDefinition returns  
) {  
}

public record ToolParamDefinition(  
        String type,  
        String description,  
        // default , enum 等等信息  
        Map<String,Object> others  
) {  
}

public record ToolReturnDefinition(  
        String type,  
        // "param" : { "type" : "number"}  
        Map<String,String> properties  
) {  
}

```

我真的是按照 JSON 随意封装的, 想封装成啥样全看自己

### 实现工具

也就是实现`Tool`接口,自己去做工具


|           |        |     |     |                    |
| --------- | ------ | --- | --- | ------------------ |
| 工具        | 分类     | 只读  | 破坏性 | 典型场景               |
| ReadFile  | file   | 是   | 否   | 查看文件内容、读取配置        |
| WriteFile | file   | 否   | 否   | 创建新文件、覆盖写入         |
| EditFile  | file   | 否   | 否   | 精确修改文件某几行，节省 token |
| Bash      | shell  | 否   | 是   | 编译、测试、安装依赖、执行命令    |
| Glob      | search | 是   | 否   | 了解项目结构、查找特定类型文件    |
| Grep      | search | 是   | 否   | 搜索代码中的函数定义、变量引用    |

> 实现工具这一步我就不想动手了,直接让AI去做吧,这点事情就不自己写了
> 按照接口实现,让AI干绝对没有问题


### 工具发现

其实就是我们统一写一个Register,提供一个方法能够拿到所有工具类和工具定义信息

之后Agent通过`name`拿到对应`Tool`的实现类,然后调用`execute`方法

所以这里就是写一个调用工具好吧


###  工具注入

我们在`day-1`的时候封装了 `RequestBodyHelper`
我们当时是把tools放进去作为了一个参数
所以我们也在`buildRequestBody` 里面补充我们的`tools`注入信息

### 工具执行

这里肯定要先回到我们的`SSE-doStream()`方法
因为我们拿到的是断断续续的片段,先要拼接,之后才能拿到整个`tool_calls`信息

拿到`tool_call`我们就需要在main里面执行了

### 结果返回

这一步很有说法,
我们肯定会把`tool_calls`和`tool_results`封装进去`Message`,也就是放在我们的`ConvesationManager`里面

但是请记住,我们之前还有一个方法,就是`buildMessages()`
这个方法做的是,把`history`拿出来,重新整理,再交给下一次请求

>千万不要忘记了这里的处理


### 重新调用LLM

直接把整个请求逻辑放在for循环

如果检测到`tool_calls`是空,就代表结束了.
否则就循环

>但注意要设置一个最大循环次数


## 好了,现在试试吧

> 我启动项目之后直接让Agent在`resource`下生成一个介绍本项目的`html`文件
> 各位可以打开看看怎么样.

## Plus Test

> 试一试我们直接让Agent给本项目加一个好看的UI系统
> 我打算使用`day-2-test`分支进行测试
> 后续`day-3`将仍然会在`day-2`基础上进行