# 端侧子代理机制:最小可行方案(MVP 设计)

端侧(手机本机)编排子代理:主 agent 把任务拆成子任务,子代理各自跑一条独立的 LLM 循环,
结果压缩后回填主循环。本文是 **MVP 设计文档**,按 P0/P1/P2 分阶段落地
(**P0 已落地**:`spawn_subagent` 真执行,串行、三类型均可用,见 §12);行为参考
agent-architecture.md 的后端运行时(spawn_agents / spawn_agent_inline / subagent-response-chunk)。

## 0. 现状与可复用资产

| 已有机制 | 位置 | 与子代理的关系 |
|---|---|---|
| 子代理卡片约定:`ToolCard.SUBAGENT_PREFIX="subagent:"`,`subagentName` 渲染「✷ 子代理 · 名」+ 实时输出尾部 | core/model AgentEvents.kt + ChatScreen ToolCardItem | **UI 已预留**,卡片即子代理的展示载体 |
| `AgentEvent.SubagentChunk(agent, chunk)` 事件 | core/model AgentEvents.kt | 子代理流式输出的事件通道已定义 |
| 流式管道:chatStream + idleWatchdog + RetryPolicy | core/data ChatRepository / StreamWatchdog / RetryPolicy | 子代理的 LLM 循环直接复用 |
| 权限层:ToolPermissions(allow/confirm/deny)+ SessionPermissionMemory | core/model ToolPermissions.kt | 子代理继承父权限;CONFIRM 在子代理内降级为 deny(见 §5) |
| 上下文预算:ContextPolicy / ContextBuilder / compressToolResult | core/model + core/data | 子代理输出回填主上下文前的压缩闸门 |
| 工具执行器 ToolExecutors:**单线程串行** | core/data tools | 并发子代理不能依赖它;子代理用独立协程 + OkHttp 异步 |
| **子代理执行引擎(P0 已落地)** | core/data `SubagentRunner.kt` + `ChatViewModel.dispatchTool` | 串行跑一个子代理:独立上下文 + 只读白名单 + 自己的 LLM 循环,结论压缩后作为普通 tool_result 回填主循环(见 §12) |

## 1. 目标与非目标(MVP 边界)

**目标**
- 主 agent 通过工具调用(`spawn_subagent`)把可并行的调研/读取类子任务委托出去;
- 每个子代理 = 独立 LLM 循环(自己的上下文,不共享主会话历史),限定只读工具集;
- 多个子代理有界并发执行,进度实时流式展示在对话流中;
- 结果压缩回填主循环,主 agent 汇总产出最终回复。

**非目标(明确不做)**
- ❌ 嵌套子代理(子代理不能再 spawn,深度固定 1);
- ❌ 用户自定义 agent 模板(内置固定剧本,见 §2);
- ❌ 子代理内交互(ask_user 类);CONFIRM 级工具在子代理内一律按 deny 处理 —— 后台任务不能阻塞在弹窗上;
- ❌ 子代理完整转录持久化(只保留最终输出在卡片上);
- ❌ 子代理写类工具(save_memory 等),从源头回避并发写冲突。

## 2. 子代理类型(内置固定剧本)

对齐 base2 的 agent 模板思想:类型 = system 剧本 + 工具白名单 + 输出预算。

| 类型 | 职责 | 工具白名单 | 输出上限 |
|---|---|---|---|
| `researcher` | 联网调研,多源交叉,给结论与出处 | web_search, web_fetch | 1000 token |
| `code_reader` | 读公开仓库文件/README,提炼结构与时序 | github_get_file, github_get_readme | 1000 token |
| `analyst` | 纯推理计算,无需外部数据 | calculator | 600 token |

每个类型的 system prompt 模板固定四段:身份与目标 → 工作方式(允许的工具与建议轮次)→
硬性约束(不许越权工具、不许编造、总输出不超过预算)→ 输出格式(直接给结论正文,分点,不客套)。

## 3. 编排协议:`spawn_subagent` 工具

主循环新增一个**编排工具**,注册进 `DefaultTools`(记忆关闭等能力裁剪不影响它):

```
spawn_subagent(agent_type: "researcher"|"code_reader"|"analyst",
               task: string,            // 子代理的目标,一句话,自包含(子代理看不到主会话!)
               context: string,         // 主 agent 认为必要的最小背景摘录(压缩后注入)
               expected_output: string) // 期望产出的形态,如"≤200 字结论+来源列表"
```

- 主模型在一轮里可发起多个 `spawn_subagent`(与其它工具混合同样走 Calls 事件);
- 参数校验失败(agent_type 未知/task 为空)→ 立即 error 结果,不执行;
- **任务拆分完全交给主模型**(prompt 里引导:「可并行的独立子任务应拆成多个 spawn_subagent」),
  端侧不做自动拆分 —— 模型拆分质量已是可用水位,端侧规则拆分反而脆弱。

## 4. 执行引擎(并发与隔离)

```
主循环一轮的 calls:
  ├── spawn_subagent × N ──→ coroutineScope { N 个 async,Semaphore(2) 限流,awaitAll }
  │       每个子代理:自己的一轮循环(≤3 轮工具),复用 chatStream + watchdog + RetryPolicy
  │       文本增量 → AgentEvent.SubagentChunk → UI 卡片实时尾部
  │       最终输出 → compressToolResult → tool_result 回填 protocol(按原 callId 顺序)
  └── 其它工具 → 现有串行 dispatchTool 不变
```

- **并发上限 2**(`MAX_CONCURRENT_SUBAGENTS`),单轮最多 3 个(`MAX_SUBAGENTS_PER_ROUND`,
  超出部分立即 error 回填,不排队);
- **隔离**:子代理上下文 = 自己的 system 剧本 + task + context + 它自己产生的工具往返,
  不读主会话历史;上下文窗口按当前 ChatTarget 的 ctxWindow 走 ContextBudget;
- **取消语义**:用户停止 = 取消主协程 → structured concurrency 级联取消所有子代理
  (callbackFlow 的 awaitClose 已保证 HTTP 层中断);不实现「只停某个子代理」;
- **超时**:单个子代理总时限 120s(`SUBAGENT_DEADLINE_MS`,withTimeout 包整个循环)+
  流式空闲看门狗 60s(比主循环更激进);失败按 RetryPolicy 重试(≤1 次,比主循环更保守);
- 执行不走 ToolExecutors 的单线程 executor:子代理直接用 OkHttp 异步回调管线
  (chatStream 本身就是),calculator 类本地工具用 Dispatchers.Default。

## 5. 权限与安全

- 子代理工具白名单只含只读工具,`forCapabilities` 之外再叠一层 `SUBAGENT_TOOLS[type]` 硬过滤;
- CONFIRM 级工具在子代理内 **不经弹窗直接 deny**(结果:「子代理无权执行需确认的工具」);
  这保证后台并发任务永远不会被 UI 阻塞,也保证权限模型「需人工确认的操作永远在主循环前台发生」;
- SessionPermissionMemory 不参与子代理;深度固定 1,spawn_subagent 不在子代理工具集内。

## 6. 上下文与预算(结果聚合)

- 子代理最终输出过 `ContextPolicy.compressToolResult`(1200 token 上限)再进主循环 protocol ——
  5 个子代理各回 1000 token 也不会撑爆主上下文;被压缩的细节留在卡片可展开区供人查阅;
- 主循环下一轮 buildHistory 照常走 ContextBuilder:子代理结果与普通 tool_result 同权参与
  预算裁剪/摘要压缩,不引入第二套预算规则;
- 子代理自身的 token 消耗计入会话总消耗展示(后续,非 MVP)。

## 7. UI 展示(复用现有卡片,增量最小)

| 阶段 | 卡片状态 | 内容 |
|---|---|---|
| 已派出未开跑 | waiting | 「✷ 子代理 · researcher · 排队中」 |
| 运行中 | running(呼吸) | 实时尾部:`SubagentChunk` 的最近一行(现有 subagent 渲染逻辑直接用) |
| 完成 | done | 展开区显示最终输出(可截断);摘要行显示首行结论 |
| 失败/超时/被限流 | error | 归因文案(超时/重试耗尽/并发上限) |

- 消息级 steps 不动;并发子代理各自一张卡,按 callId 顺序排列(现有时间线语义不变);
- 聚合结果不单独做「汇总卡片」:主 agent 的最终回复本身就是聚合产物(它看得到全部 tool_result)。

## 8. 持久化与恢复

- 卡片(含最终输出)随消息 toolsJson 落库 —— 进程死亡/会话切换后历史可见,状态停在当时;
- 子代理转录不落库;运行中进程死亡 = 该子代理卡片停在 running,重开会话由
  finishAgent 的「running 卡片 → error(未完成)」兜底(现有逻辑已覆盖);
- 不做「恢复重跑」:主 agent 收到 error 结果后可自行重新 spawn(它有完整上下文判断)。

## 9. 测试计划

| 层 | 用例 |
|---|---|
| core/model | spawn 参数解析(未知 type/空 task)、SUBAGENT_TOOLS 白名单过滤、输出压缩截断 |
| core/data | MockWebServer:子代理循环(剧本请求结构、工具往返、3 轮上限)、并发 2 限流(Semaphore 计数)、120s 超时、失败重试 1 次、父取消级联取消 |
| feature/chat | compileDebugKotlin + 现有回归(卡片渲染逻辑无改动,主要靠模拟器端到端) |

## 10. 分阶段落地

| 阶段 | 内容 | 验收 |
|---|---|---|
| **P0** 串行单子代理 ✅ **已落地** | spawn_subagent 注册(工具定义 + 权限默认 ALLOW)+ 串行执行 + 卡片 done/error + 结果回填。落地范围比原计划宽一点:**三种类型都可用**(执行引擎按类型取白名单,不做类型开关),但仍是**单轮内串行** | 单测已覆盖(SubagentRunnerTest 8 例:一轮工具往返、白名单/权限降级、轮数用尽强制收敛、确定性失败不重试、瞬时失败重试、空结论归因);模拟器端到端待补(见 §12 末的验收缺口) |
| **P1** 并发 + 实时流 | Semaphore(2) 并发 + SubagentChunk 流式尾部 + 三类型剧本 + 超时/限流 | 双子代理并发可观察(两卡同时呼吸);停止按钮级联取消 |
| **P2** 打磨 | 子代理用廉价快模型(按类型映射 ChatTarget)、token 消耗统计、失败自动重 spawn 策略提示 | — |

## 11. 与后端机制的对应(供后续服务端联调)

| 端侧 | agent-architecture.md 后端机制 |
|---|---|
| spawn_subagent 工具 + 固定剧本 | spawn_agents / spawn_agent_inline + agents/*.ts 模板 |
| SubagentChunk → 卡片实时尾部 | subagent-response-chunk 事件 |
| 深度 1 + 白名单只读 | 后端按 agentTemplate.toolNames 裁剪;plan 模式剔除写工具 |
| compressToolResult 后回填 | context-pruner 每步前修剪上下文 |
| 停止级联取消 | 后端 abort 杀整棵进程树 |

**实现落点**:`core/model/Tools.kt`(spawn_subagent 定义 + SUBAGENT_TOOLS)、
`core/model/Subagent.kt`(剧本/参数解析/常量)、
`core/data` 的 `SubagentRunner.kt`(执行引擎,不塞进 ChatViewModel)、
`feature/chat/ChatViewModel.kt`(dispatchTool 分派 + 卡片接线)。

---

## 12. P0 实现现状(已落地)

`spawn_subagent` 现在**真的会执行**。主模型调用它 → 端上串行跑一个子代理 → 结论压缩后作为本轮
普通 `tool_result` 交给主循环(主模型看得到的只有结论 + 来源标签,看不到子代理的工具过程)。

| 环节 | 实际行为 |
|---|---|
| 入口 | `ChatViewModel.dispatchTool` → `Subagent.TOOL_NAME -> runSubagent(...)`;先过统一权限层(默认 ALLOW,用户可改成需确认/禁止) |
| 参数 | `Subagent.parseRequest`;`agent_type` 未知或 `task` 为空 → 回 `[工具错误]` 信封(带正确调用示例),**不执行** |
| 白名单 | `Subagent.toolsFor(type)` 再按权限过滤:**只有 ALLOW 级工具**进子代理清单,CONFIRM/DENY 一律摘掉(后台任务不能阻塞在弹窗上);剧本 `Subagent.systemPrompt(type, toolNames)` 按实际可用工具说实情 |
| 越权调用 | 模型硬调清单外的名字(含幻觉工具、`spawn_subagent` 自己)→ 不执行,回 `UNKNOWN_TOOL` 信封并列出可用工具(深度固定 1) |
| 上下文 | 只有自己的 system 剧本 + `task/context/expected_output`;**不读主会话历史**;子代理的中间过程只回填给它自己 |
| 轮数 | 最多 `MAX_TOOL_ROUNDS`(3)轮工具;**用尽后去掉 `tools` 再要一次**,逼它基于已有结果收敛成结论;另有 `MAX_REQUESTS` 兜底 |
| 时限与重试 | 总时限 `DEADLINE_MS`(120s,超时→失败归因);流空闲看门狗 `IDLE_TIMEOUT_MS`(60s);瞬时失败(超时/连接/429/5xx/流空闲)最多重试 `MAX_RETRIES`(1)次,**且仅在本轮尚无输出时**(避免重复文本) |
| 回填 | `Subagent.formatResult` 带来源标签(`[子代理 researcher 完成 · 2 轮工具 · web_search]`)→ `ContextPolicy.compressToolResult`(1200 token 上限) |
| 失败 | `Subagent.formatFailure`:说清这一路没结果 + 给主模型替代路径(改用 `web_search`/`web_fetch`/`github_get_*` 或直接告知用户),避免原地重派 |
| UI | 卡片就地改成子代理形态(`subagent:<type>`,复用 `ToolCard.SUBAGENT_PREFIX` 渲染分支):运行中按 80 字符节流写入正文尾部做「实时尾部」,收尾后展开区是压缩后的最终输出 |
| 持久化 | 子代理转录**不落库**(仅卡片) —— 进程死亡即丢弃,与 §8 一致 |
| 取消 | 用户「停止」= 取消主协程 → structured concurrency 级联取消子代理(callbackFlow 的 awaitClose 中断 HTTP) |

回归位置:`core/data/src/test/.../SubagentRunnerTest`(MockWebServer;全程用 `analyst` 的 calculator,
零外网)、`core/model/src/test/.../SubagentTest`(参数/白名单/剧本/回填格式)。

**验收缺口(未做)**:设计文档原本的 P0 验收是模拟器端到端(主任务拆两次 spawn、串行跑完、最终回复聚合两路)。
当前只有单测覆盖引擎本身与请求契约;**真机矩阵尚无 `subagent` 场景**——补场景时建议用 `analyst`
(calculator 纯本地,不依赖外网与 mock 之外的服务),并在 mock 里按「system 提示词含『子代理』」分流剧本。
