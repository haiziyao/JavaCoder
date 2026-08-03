

> 在之前我们已经实现好了一个可以持续对话的简陋的Agent.
> 现在我们要实现一些常用的事项
> 比如封装一个ReAct-Agent,可以直接用


>在此之前,由于deepseek最近发布了v4-flash模型,那么我们先扩展一个ds接口
>我们只需要把配置文件改了, 在`create()`函数中加入 case deepseek就可以了

但是,好巧不巧,出现问题了:
 ```
 > 你好
nullnullnullnullnullnullnullnullnullnullnullnullnullnullnullnullnullnullnullnullnullnullnullnullnullnullnullnullnullnullnullnullnullnull你好！很高兴为你服务。😊

我是你的编程助手，可以帮你完成各种代码相关的任务，比如：

- 📝 **编写代码**：用各种语言写程序、脚本
- 🔍 **调试问题**：分析报错、查找 bug
- 📂 **文件操作**：创建、修改、搜索项目文件
- 🔧 **代码重构**：优化结构、改进性能
- 📚 **解答疑问**：解释概念、算法、框架用法

请告诉我你想做什么，我会尽力帮助你！有什么我可以为你效劳的吗？
 ```

为什么会输出一堆null呢?
怎么去排查这个问题呢?我们对AI发话"请你输出一个1"

```
> 请你输出一个 1
nullnullnullnullnullnullnullnullnullnullnullnullnullnullnullnullnullnullnullnullnullnullnullnullnullnullnullnullnull1
```

这时候我们去Postman再调用一下

```

data: {"id":"a3b388f5-c94e-469f-ace8-748043517274","object":"chat.completion.chunk","created":1785720376,"model":"deepseek-v4-pro","system_fingerprint":"fp_9954b31ca7_prod0820_fp8_kvcache_20260402","choices":[{"index":0,"delta":{"role":"assistant","content":null,"reasoning_content":""},"logprobs":null,"finish_reason":null}]}

data: {"id":"a3b388f5-c94e-469f-ace8-748043517274","object":"chat.completion.chunk","created":1785720376,"model":"deepseek-v4-pro","system_fingerprint":"fp_9954b31ca7_prod0820_fp8_kvcache_20260402","choices":[{"index":0,"delta":{"content":null,"reasoning_content":"我们"},"logprobs":null,"finish_reason":null}]}

data: {"id":"a3b388f5-c94e-469f-ace8-748043517274","object":"chat.completion.chunk","created":1785720376,"model":"deepseek-v4-pro","system_fingerprint":"fp_9954b31ca7_prod0820_fp8_kvcache_20260402","choices":[{"index":0,"delta":{"content":null,"reasoning_content":"被"},"logprobs":null,"finish_reason":null}]}

data: {"id":"a3b388f5-c94e-469f-ace8-748043517274","object":"chat.completion.chunk","created":1785720376,"model":"deepseek-v4-pro","system_fingerprint":"fp_9954b31ca7_prod0820_fp8_kvcache_20260402","choices":[{"index":0,"delta":{"content":null,"reasoning_content":"要求"},"logprobs":null,"finish_reason":null}]}

data: {"id":"a3b388f5-c94e-469f-ace8-748043517274","object":"chat.completion.chunk","created":1785720376,"model":"deepseek-v4-pro","system_fingerprint":"fp_9954b31ca7_prod0820_fp8_kvcache_20260402","choices":[{"index":0,"delta":{"content":null,"reasoning_content":"输出"},"logprobs":null,"finish_reason":null}]}

data: {"id":"a3b388f5-c94e-469f-ace8-748043517274","object":"chat.completion.chunk","created":1785720376,"model":"deepseek-v4-pro","system_fingerprint":"fp_9954b31ca7_prod0820_fp8_kvcache_20260402","choices":[{"index":0,"delta":{"content":null,"reasoning_content":"1"},"logprobs":null,"finish_reason":null}]}

data: {"id":"a3b388f5-c94e-469f-ace8-748043517274","object":"chat.completion.chunk","created":1785720376,"model":"deepseek-v4-pro","system_fingerprint":"fp_9954b31ca7_prod0820_fp8_kvcache_20260402","choices":[{"index":0,"delta":{"content":null,"reasoning_content":"。"},"logprobs":null,"finish_reason":null}]}

data: {"id":"a3b388f5-c94e-469f-ace8-748043517274","object":"chat.completion.chunk","created":1785720376,"model":"deepseek-v4-pro","system_fingerprint":"fp_9954b31ca7_prod0820_fp8_kvcache_20260402","choices":[{"index":0,"delta":{"content":null,"reasoning_content":"这"},"logprobs":null,"finish_reason":null}]}

data: {"id":"a3b388f5-c94e-469f-ace8-748043517274","object":"chat.completion.chunk","created":1785720376,"model":"deepseek-v4-pro","system_fingerprint":"fp_9954b31ca7_prod0820_fp8_kvcache_20260402","choices":[{"index":0,"delta":{"content":null,"reasoning_content":"很简单"},"logprobs":null,"finish_reason":null}]}

data: {"id":"a3b388f5-c94e-469f-ace8-748043517274","object":"chat.completion.chunk","created":1785720376,"model":"deepseek-v4-pro","system_fingerprint":"fp_9954b31ca7_prod0820_fp8_kvcache_20260402","choices":[{"index":0,"delta":{"content":null,"reasoning_content":"。"},"logprobs":null,"finish_reason":null}]}

data: {"id":"a3b388f5-c94e-469f-ace8-748043517274","object":"chat.completion.chunk","created":1785720376,"model":"deepseek-v4-pro","system_fingerprint":"fp_9954b31ca7_prod0820_fp8_kvcache_20260402","choices":[{"index":0,"delta":{"content":null,"reasoning_content":"直接"},"logprobs":null,"finish_reason":null}]}

data: {"id":"a3b388f5-c94e-469f-ace8-748043517274","object":"chat.completion.chunk","created":1785720376,"model":"deepseek-v4-pro","system_fingerprint":"fp_9954b31ca7_prod0820_fp8_kvcache_20260402","choices":[{"index":0,"delta":{"content":null,"reasoning_content":"输出"},"logprobs":null,"finish_reason":null}]}

data: {"id":"a3b388f5-c94e-469f-ace8-748043517274","object":"chat.completion.chunk","created":1785720376,"model":"deepseek-v4-pro","system_fingerprint":"fp_9954b31ca7_prod0820_fp8_kvcache_20260402","choices":[{"index":0,"delta":{"content":null,"reasoning_content":"1"},"logprobs":null,"finish_reason":null}]}

data: {"id":"a3b388f5-c94e-469f-ace8-748043517274","object":"chat.completion.chunk","created":1785720376,"model":"deepseek-v4-pro","system_fingerprint":"fp_9954b31ca7_prod0820_fp8_kvcache_20260402","choices":[{"index":0,"delta":{"content":null,"reasoning_content":"即可"},"logprobs":null,"finish_reason":null}]}

data: {"id":"a3b388f5-c94e-469f-ace8-748043517274","object":"chat.completion.chunk","created":1785720376,"model":"deepseek-v4-pro","system_fingerprint":"fp_9954b31ca7_prod0820_fp8_kvcache_20260402","choices":[{"index":0,"delta":{"content":null,"reasoning_content":"。"},"logprobs":null,"finish_reason":null}]}

data: {"id":"a3b388f5-c94e-469f-ace8-748043517274","object":"chat.completion.chunk","created":1785720376,"model":"deepseek-v4-pro","system_fingerprint":"fp_9954b31ca7_prod0820_fp8_kvcache_20260402","choices":[{"index":0,"delta":{"content":"1","reasoning_content":null},"logprobs":null,"finish_reason":null}]}

data: {"id":"a3b388f5-c94e-469f-ace8-748043517274","object":"chat.completion.chunk","created":1785720376,"model":"deepseek-v4-pro","system_fingerprint":"fp_9954b31ca7_prod0820_fp8_kvcache_20260402","choices":[{"index":0,"delta":{"content":"","reasoning_content":null},"logprobs":null,"finish_reason":"stop"}],"usage":{"prompt_tokens":7,"completion_tokens":16,"total_tokens":23,"prompt_tokens_details":{"cached_tokens":0},"completion_tokens_details":{"reasoning_tokens":14},"prompt_cache_hit_tokens":0,"prompt_cache_miss_tokens":7}}

data: [DONE]

```

一眼便知道 是`content`和`reasoning_content`搞得鬼
(上面的json可能看得不是很清楚,原因是我使用的text代码块展示,而不是json,在github中,json格式只得有一个根节点,否则爆红)

>所以,应该是我们没对content内容做校验,在gpt中,如果content没有内容就是结束了
>但是ds中content会为null,我们肯定不能把null都排除,因为我们正文中有content
>所以ds的话就需要我们对`content`和`reasoning-content`两个进行判断了

``` java
if (delta.has("content") && !delta.get("content").isNull()) {  
    String text = delta  
            .get("content")  
            .asText();  
  
    if (!text.isEmpty()) {  
        queue.put(new StreamBlock.ContentDelta(text));  
    }  
}
```
加上一个判断就行了,如果content的节点是null就扔掉,之前没有写后面那个逻辑
这里对于reasoning_content,我们是直接抛弃了,后面再想会有什么其他做法


## 实现ReAct-Agent
其实这章做的事情简单来说,就是把main内容也封装了.

我们把Main中的循环,封装为一个ReAct-Agent.


## 新加类和代码

### Agent

这个就有一个Agent.run()方法,集成了之前的Main的代码

### AgentEvent
这个类可能有很多人都会质疑
我们之前有StreamBlock,链路是这样的
```
Request->Response->StreamBlock->Consumer
我们这里的Consumer是谁?是我们mian里面的System.println()
```

那么AgentEvent是做什么的呢?

这么来说,也就是我们拿到的StreamBlock肯定要被消费,但是我们Agent代码里面不想写消费
想把消费提取出去,怎么办,我们想UI去消费
那么就多一层队列
```
StreamBlock->AgentEvent->Consumer
```

也就是说,我们在Agent.run()里面,把收到的结果都装queue里面.然后返回这个队列

随想用就给谁用

### AgentEventQueue
我们这里算是一个代理类, 因为如果用原生queue的put,offer等等会遇到并发,阻塞的问题
我们先提出来一层,方便以后更改

### ui.CmdUI
这里就是我说的消费层



## 实战

### 美化index.html
仍旧使用deepseek-api,然后美化一下index.html
检测一下是否形成闭环

### 实现一个WebUI
让AI实现一个WebUI,并使用WebUI.看看AI的能力
我们仍旧把这里代码放在`day-3-test`