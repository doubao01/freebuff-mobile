# 上下文工程与记忆:设计参考

本文说明原生 App 的「LLM + 工具 + 循环」运行时如何做上下文压缩与记忆,以及各设计点借鉴的开源方案。实现代码:`core/model/ContextPolicy.kt`、`core/model/Memory.kt`、`core/data/context/ContextBuilder.kt`、`core/data/repository/MemoryRepository.kt`、`feature/chat/ChatViewModel.kt`。

## 1. 总体架构

```
sendMessage(text)
   │
   ▼
┌────────────── agent 循环(最多 30 轮,只当兜底)──────┐
│  buildHistory(sessionId)                            │
│    ├─ MemoryRepository.load()   ← Room memories 表  │
│    ├─ ContextBuilder.build(                         │
│    │     memoryBlocks,     // Letta 核心记忆块      │
│    │     session 消息,     // Room messages         │
│    │     ContextBudget(     // 按模型 ctx 窗口算    │
│    │       parseCtxWindow), │                     │
│    │     llmSummarizer,     // LibreChat 式摘要     │
│    │     cachedSummary)     // 会话级摘要复用       │
│    │   → system(记忆+人设+早期摘要) [近端消息…]     │
│    └─ + protocol 跨轮累积(assistant/tool 消息)      │
│  runStreamRound() → SSE → 事件                      │
│  有 tool_calls? → 执行(save_memory/外部工具)       │
│    → 结果压缩 → 回传 → 下一轮                       │
└─────────────────────────────────────────────────────┘
```

每轮请求都**重建**上下文:工具结果落库(工具卡片)后自动出现在下一轮;记忆块始终注入 system;预算超限时自动压缩。

摘要不进独立消息,而是拼进**首条 system**(`parts[0]`):OpenAI 兼容网关普遍把 system 当首条处理,
中途再插一条 system 各家行为不一。

## 2. 借鉴映射

| 机制 | 借鉴来源 | 本工程落点 |
|---|---|---|
| 核心记忆块(命名块常驻 system,agent 自编辑) | Letta/MemGPT core memory blocks | `MemoryBlock`(persona/user/project)+ `save_memory` 工具 + Room `memories` 表 |
| 检索式记忆(按查询 Top K 注入 + 工具化召回) | MemGPT archival memory / OpenAI memory 工具化 | `MemoryEntry` + `MemoryEntryRepository` + `memory_recall` 工具 + Room `memory_entries` 表 |
| 会话摘要压缩(超限把早期消息换摘要) | LibreChat conversation summarization;Cline auto-compact | `ContextBuilder.build` 裁剪 + `llmSummarizer`(非流式摘要调用,可带【已有摘要】合并) + `cachedSummary`(会话级缓存,被裁段每多 4 轮才重算) |
| 摘要失败的降级 | LibreChat 提取式回退 | `ContextPolicy.extractiveSummary`(首条用户请求 + 最新进展) |
| 工具结果有损压缩(保留首尾) | Cline 机械压缩思想 | `ContextPolicy.compressToolResult`(单条 1200 token 上限) |
| token 预算 = 窗口 − 输出预留 | OpenAI/Anthropic 常规实践 | `ContextBudget.usableTokens`(reserveOutput=4096) |
| 上下文窗口声明 | 各模型 metadata | `CustomModel.ctx`("128k"/"1m"/"200000")→ `parseCtxWindow` |
| Focus Chain(任务焦点记忆) | Cline Focus Chain | `project` 记忆块(模型用 save_memory 维护当前任务焦点) |

## 3. 关键规则

- **永不丢弃**:首条 system(含记忆块与早期摘要)在兜底淘汰(`parts.removeAt(1)`)中受保护 —— 裁剪永远不会动 `parts[0]`。
- **摘要写进首条 system,且按增长步长重算**:`SUMMARY_GROWTH_STEP = 4` —— 被裁段比上次摘要多覆盖不到 4 轮时直接复用会话级缓存(`ChatViewModel.summaryCache`),不重复调 LLM;确实变多时把【已有摘要】一起交给模型合并成一份连贯摘要。
  为什么:实测每轮都重新摘要会在长会话里白烧一次额外请求(与主请求同量级的延迟),而摘要内容又几乎不变。
- **工具结果双重压缩**:执行后回传给模型前压一次(`ContextPolicy.compressToolResult`),历史序列化进上下文时再按上限压一次。
- **save_memory 由能力开关控制**:设置页「上下文记忆」关闭时,工具列表移除 `save_memory`(`DefaultTools.forCapabilities`),记忆块也不注入——模型不会看到不可用的工具。
- **记忆限额**:单块默认 600 字符,超限截断并提示模型用 save_memory 精炼;save_memory 追加超限时保留头部 + 追加段。
- **估算误差**:token 估算(中文 1 token/字,英文 0.3/字符)只用于预算决策,误差由「保守上界 + 预留 4k」吸收。

## 4. 测试

- `ContextPolicyTest`:估算/窗口解析/压缩/预算
- `MemoryTest`:编解码往返/prompt 渲染/限额/save_memory 参数/能力过滤
- `MemoryRepositoryTest`:播种/覆盖/追加压缩/清空(内存假 DAO)
- `ContextBuilderTest`:记忆注入/工作记忆注入/工具结果压缩/裁剪+摘要(摘要在首条 system)/LLM 摘要与失败回退/空会话/摘要覆盖轮数
- `SessionRepositoryTest`:会话删除与撤销;冷启动修复半条消息(空正文→中断提示、过程性步骤清除、未跑完的工具卡片标未完成、幂等)

## 5. 长会话实测(8k 窗口压到极限)

`verify/longsession.py 14`(已移除)(脚本 + 报告 `verify/longsession_report.md`):设备模型 ctx 设为 `8k`,连发 14 轮
(每轮都要求回复足够长,逼出裁剪),逐轮记录请求规模与耗时。结果:

> 注:`verify/longsession.py` 已移除(随本地工装清理删除,未入库不可恢复),下表实测结果保留备查。

| 指标 | 实测 |
|---|---|
| 轮次 / 失败 | 14 轮全部 `ok` |
| 单轮端到端 | 19.8 – 29.9 s |
| 进入请求的消息条数 | 最多 21(不随轮数线性增长 → 裁剪生效) |
| system 段字符数 | 最多 561(摘要并入首条 system 后仍未失控) |
| 摘要调用次数 | **1 次**(14 轮只在被裁段明显变多时重算了一次;改动前是每轮一次) |
| 摘要出现位置 | 请求里 4 次命中首条 system 含摘要标记 |
- `MemoryEntryTest`:分词/相关度排序/类型归一化/memory_recall 参数/注入格式/提取解析容错
- `MemoryEntryRepositoryTest`:写入可检索/去重刷新/类型过滤/热度累加/容量淘汰/用户隔离

## 5. 检索式记忆库(记忆即工具)

核心块(persona/user/project)适合「少量、常驻、模型自编辑」的信息;大量历史信息(用户偏好、关键事实、
任务进度)走检索路径——**记忆同时是一个工具**:每次 LLM 调用前按当前问题检索 Top K 注入为「工作记忆」,
模型也可主动调用 `memory_recall` 拉取。

```
用户输入 ─┬─► MemoryEntryRepository.search(query, top_k=5, memory_type?)
          │        └─ MemoryRetrieval.rank(词法相关度 + 时间衰减 + 命中热度)
          ├─► ContextBuilder.build(workingMemory=…) → system「## 工作记忆」
          ├─► 模型可调 memory_recall(user_id, query, top_k, memory_type) → ❒ 检索记忆 卡片
          └─► 轮次结束:extractTurnMemories() → 模型提取 JSON → memory_entries(long_term/short_term)
```

### 5.1 存储(`memory_entries`,DB v4)

`id / userId / type / content / sessionId / createdAt / updatedAt / hits`:

- `user_id` 隔离多用户记忆;访客模式固定 `local`(system prompt 中告知模型应传的 user_id)
- 写入去重:同一 user 下内容相同只刷新时间与类型;过短内容拒收
- 容量:单用户超过 500 条淘汰最旧条目(短期记忆优先出局)

### 5.2 检索(无嵌入依赖,端侧可离线)

- 分词:CJK 连续串切 bigram(免分词近似)+ 整串特征;拉丁/数字按词小写
- 相关度 = TF 对数加权 / 长度归一 × 命中率缩放 + 子串加成
- 排序 = 相关度 + 时间衰减(长期半衰期 30 天、短期 2 天)+ 命中热度
- **有查询词时只返回真正有特征重叠的条目**(无关条目不得靠时间/热度混入);查询无有效特征时退化为「最近记忆」
- 自动注入 `bumpHits=false`(不写库);模型显式检索才累加热度
- 已知限制:词法检索无法跨语言(中文查询 ↔ 英文记忆)命中——工具路径零命中时回退「最近记忆」
  并在结果里注明「无直接匹配」;提取提示词要求 content 与用户语言一致,避免长期累积跨语言记忆

### 5.3 工具参数(`memory_recall`)

| 参数 | 必填 | 说明 |
|---|---|---|
| `user_id` | ✓ | 记忆命名空间;非本机 id 无命中时回退本机记忆并在结果中注明 |
| `query` | ✓ | 当前需要响应的用户输入/查询词 |
| `top_k` | – | 默认 5,上限 20 |
| `memory_type` | – | `long_term`(偏好/事实)/ `short_term`(任务进度);兼容 long/short/长期/短期 |

注入与回传格式同为 `N. [长期|短期] 内容`;无命中回「记忆库中没有与「…」相关的条目」,避免模型臆造。

### 5.4 关键规则

- 记忆类工具随设置页「上下文记忆」开关整体开关(`DefaultTools.forCapabilities`):关闭时 `save_memory`
  与 `memory_recall` 都不下发,块与工作记忆也不注入
- 自动提取失败静默(不打断对话);单轮最多 5 条、单条 ≤300 字;提取 JSON 容错(剥围栏/夹带说明/纯字符串元素)
- 工作记忆注入在 system prompt 内,受同一 token 预算约束;检索为空时不产生空小节

### 5.5 文件

- `core/model/MemoryEntry.kt`(类型、参数编解码、检索打分、提取解析;纯函数)
- `core/data/repository/MemoryEntryRepository.kt`(去重写入、检索、热度、淘汰)
- `core/data/context/ContextBuilder.kt`(`workingMemory` 注入 + user_id 声明)
- `feature/chat/ChatViewModel.kt`(`dispatchTool` 分派 / `executeMemoryRecall` / `extractTurnMemories`)
