# 端侧能力扩展路线图(从 9 个只读工具到「能改代码」;M1 已落地)

> 现状一句话:**M1 已落地** —— 端侧 agent 能在手机上真的完成「建分支 → 提交文件 → 开 PR」(T1 路由收口 +
> T2 写路径 + T3 计划模式;逐条证据见 §5)。T5 已落了第一步:`write_todos` 的清单卡片随消息持久化。
> 仍未具备的是「交互 + 本地工作区」两类能力:`ask_user`、`run_terminal_command`(Android 无任意进程执行,
> 只在路线 B 有意义)、SAF 目录工作区。对照后端运行时的 36 个工具(`docs/agent-architecture.md` §1.1),
> 差距已经从「能不能写」变成「写之外还缺什么」。
>
> 本文把差距拆成可独立交付的任务,并给出验收标准。**当前状态与理由都以仓库代码为准**,不是愿望清单。

---

## 0. 现状盘点(为什么加工具不是「加一个 case」)

| 环节 | 位置 | 现状 |
|---|---|---|
| 工具定义(给模型的说明书 + schema) | `core/model/Tools.kt` `DefaultTools.ALL` | **16 条**:`web_search` `web_fetch` `github_search_repositories` `github_get_file` `github_get_readme` `github_list_branches` `github_create_branch` `github_commit_files` `github_create_pull_request` `calculator` `current_time` `save_memory` `memory_recall` `write_todos` `spawn_subagent` `task_completed` |
| 执行分派 | `core/data/tools/ToolExecutors.execute(call, tools, attempt, host)` | **单入口**(T1 已收口):按注册表 `route` 分流 —— `DATA` 走内部分派表(`dispatchedTools` 与注册表 DATA 集合全等,漏写即红),`HOST` 回调 `ToolHost`(ChatViewModel 实现,闭包携带仓库/会话/UI 回调) |
| 参数体检 / 归一 | `DefaultTools.validate` / `normalize` | 已通用(未知参数名、缺必填、越界、闭集、别名),新工具只需写 `Param` |
| 权限分级 | `core/model/ToolPermissions.kt` | 默认分级的**唯一来源是注册表**(`AgentTool.permission`),不再有第二份手抄清单;只读 → `ALLOW`,写工具与 `save_memory` → `CONFIRM`,未登记工具名 → `CONFIRM`(兜底) |
| 可用性裁剪 | `DefaultTools.forCapabilities(memory, planMode)`(纯函数) | **记忆开关 + 计划模式**两层裁剪,受 `ToolRegistryTest` 断言;「工作区是否已授权」等 T4 落地后再加一个参数 |
| 确认交互 | `dispatchTool` 的 `pendingConfirmation` + `ToolCard.state="waiting"` | 已有挂起/放行/拒绝与「本次会话记住选择」,写工具可直接复用 |
| 展示 | `AgentEvents.kt` `toolDisplayName` / `ToolCard` + `ChatScreen.ToolCards` | 展示名已**从注册表派生**(`AgentTool.label`,不再有第二份手抄清单);卡片支持 `running/error/waiting/reused/subagent:`,**仍没有 diff 预览、清单、选项、终端输出**这几种形态 |
| 能力边界 | —— | `github_get_file` 已补鉴权分支(可读私有仓库);仍无 SAF 目录授权、无本地工作区、无 JGit、无 `DocumentFile` |

**结论(已解决):** 加一个写工具曾经要同时动 5 处(定义 / 分派 / 权限默认 / 展示标签 / 卡片形态),
所以第一个任务是把这条链**抽成路由**。现在全部集中在 `core:model/Tools.kt` 的 `AgentTool` 一处 —— 加一个 `DATA` 工具只改这一个文件,
`ToolRegistryTest`(标签/权限/能力都从注册表派生)与 `ToolExecutorsTest`(分派表与注册表全等)
会替你抓住漏登记。

第二条硬约束:**Android 没有桌面意义上的终端**——不能 `exec` 任意进程,也没有 shell 生态与
Node/Python 运行时。`run_terminal_command` 无法忠实移植;要它只能走路线 B(远端会话)。

---

## 1. 两条路线(建议 A 先做)

| | 路线 A:端侧能力扩展 | 路线 B:接入后端 agent 运行时 |
|---|---|---|
| 做什么 | 用**已有的 GitHub `repo` token** 做真实写入;SAF 目录做本地工作区;端侧实现任务管理/交互类工具 | 会话改为订阅后端事件流(`tool_call`/`tool_result`/`error`/`subagent-response-chunk`),工具在云端跑 |
| 能得到什么 | 提交、开 PR、改文件、清单、计划模式、`ask_user` | 36 个工具 + 真终端 + `render_ui`/`skill`/Composio |
| 代价 | `run_terminal_command` 永远拿不到;能力集与后端**不同构**(端侧工具名要能对得上后端语义) | 依赖后端会话接口契约(目前 `docs/agent-architecture.md` §5 列为「后续接入」),App 变成事件消费端 |
| 现在能做吗 | 能,**离线可验证**:mock + 真实测试仓库 | 需要后端接口与配额/鉴权细则 |

**为什么 A 先做:** token 早就够用了(`GithubApi` 设备流默认 `scope = "repo read:user"`,`repo` 已含写权限,
不必重新授权),不需要新后端,不阻塞,而且「在手机上让 agent 真的提交一个文件、开一个 PR」
是把「只兑现了一半」这句话直接兑现掉的那一步。B 是中期目标,不替代 A。

---

## 2. 任务分解

### T1(前置基础设施)工具路由与注册表收口 —— ✅ 已落地

**做什么**
- 把「定义 → 权限默认 → 分派 → 标签 → 卡片」五处收成一条链:抽 `ToolSpec`(定义 + 默认权限 + 执行器引用),
  `ToolExecutors` 与 `ChatViewModel` 的分派合并为一个入口(带 `ExecContext`:仓库、Context、会话、权限、UI 回调),
  消除「新工具忘了登记权限/标签」这类静默缺陷;
- `TOOL_LABELS` 改为从注册表派生(现在是两份手工清单,`web_search` 还重复了一条);
- `activeTools()` 增加能力裁剪参数(记忆 / 计划模式 / 工作区是否已授权),写成纯函数便于单测。

**验收**:现有 11 个工具行为不变(即今天 16 个里的那 11 个;`:core:model:test :core:data:testDebugUnitTest` 全绿);
新增一个工具只需改 1 个文件即能被模型看到、能执行、有权限、有标签 —— 用一个测试工具证明。

### T2(里程碑 M1)GitHub 写路径闭环:分支 → 提交 → PR —— ✅ 已落地(端到端真机验证待你提供测试仓库)

**工具**(全部走 `api.github.com`,复用 Keystore 里已加密的 token;`github_get_file` 现在走
`raw.githubusercontent.com` 匿名通道,私有仓库读不到,要一并补 `github_get_file` 的鉴权分支)

| 工具 | 权限默认 | 说明 |
|---|---|---|
| `github_list_branches` | ALLOW | 读,给写操作选 base |
| `github_create_branch` | CONFIRM | 从 base 建分支 |
| `github_commit_files` | CONFIRM | **多文件一次原子提交**:blobs → tree → commit → 更新 ref(不要一个文件一个提交) |
| `github_create_pull_request` | CONFIRM | 从 head 到 base 开 PR,回传 PR 链接 |

**关键设计**
- 参数里必须能表达「改哪个仓库的哪个分支」:写操作一律要 `owner`/`repo`/`base`/`branch`,不接受省略;
- **默认在分支上写,不直推默认分支**;`force` 一律不做(不做强推、不做删分支)——破坏性操作不进工具集;
- 失败的处方要对得上 GitHub 的真实语义:401/403(scopes 或权限)、404(仓库/分支/path 不存在或私有)、
  409(分支已存在 / 非 fast-forward)、422(引用名非法)、429(限流,别重试);
- 每次写入的**卡片要能看清将要发生什么**(文件路径 + 变更行数 + 新旧内容摘要),用户点「允许」前知道自己在批什么;
- 日志与错误信封里**不得出现 token**;请求主机固定 `api.github.com`。

**验收(可测)**
1. 单测:参数体检/归一、权限默认(`create_branch`/`commit_files`/`create_pull_request` = CONFIRM)、
   分支名与路径合法性校验、tree/commit 请求体构造、错误分支的处方文案、别名(`branch`/`base`/`files`);
2. 网络层:`MockWebServer` 覆盖 401/403/404/409/422/429 与成功路径;
3. 端到端(人工一次,记录结果):在测试仓库建分支 → 提交一个文件 → 开 PR → 用 GitHub 页面确认 diff 正确;
4. UI:确认弹窗与卡片能显示目标仓库/分支/文件与变更摘要;拒绝后模型收到 `deniedByUser` 信封(已有机制);
5. 文档:更新 `docs/backend-integration.md`(工具清单)与本文件的 M1 状态。

### T3(里程碑 M1 附带)计划模式:权限由工具集强制 —— ✅ 已落地

- 设置页新增「计划模式」开关;开启时 `activeTools()` **物理剔除**全部写工具(与后端 §1.2 同构:
  不是提示词约束,而是模型根本看不到);
- 对话页顶部显示「计划模式」状态与退出入口;计划模式下模型若尝试写工具,走未知工具路径给近邻建议;
- 验收:单测断言裁剪结果集合;真机演示「开启 → 模型只给计划 → 关闭 → 才会真的提交」。

### T4(里程碑 M2)端侧工作区:先只读,再写入

- SAF 目录授权(`ACTION_OPEN_DOCUMENT_TREE`)+ `takePersistableUriPermission`,设置页展示已授权目录;
- **只读批**:`read_files` `list_directory` `glob` `code_search`(ALLOW)—— 让 agent 能读用户手机上的项目;
- **写入批**:`write_file` `str_replace`(CONFIRM)—— 写入前保留原始副本(可撤销),卡片显示 diff;
- 硬约束:路径必须是已授权目录的相对路径,拒绝 `..` 逃逸与符号链接;单文件大小上限 + 截断提示;
  二进制文件拒绝读写;不做递归删除;
- 验收:单测覆盖路径逃逸/大小/编码/二进制拒绝;真机跑通「授权目录 → 读 → 改一个文件 → 撤销」。

### T5(里程碑 M3)任务管理与交互类工具 —— 🚧 部分落地(`write_todos`)

- ✅ `write_todos`:清单写进工具卡片(逐项 content/status),随消息一起落库 —— 重开会话仍是原来那几项;
  状态只认 pending / in_progress / completed(英文同义词与中文说法都能归一),执行结果把整份清单回给模型,
  免得长任务里「排到哪一步」全靠它自己回忆。工具是 ALLOW + HOST 路由(要写当前会话的卡片),
  不受计划模式与记忆开关影响。见 `core:model/TodoList.kt`、`ChatViewModel.executeWriteTodos`、
  `ChatScreen.TodoChecklist`;单测 `TodoListTest` / `ToolCardCodecTest`(往返)
- ⬜ 其余:`create_plan`(计划模式产物)、`suggest_followups`(卡片)、`think_deeply`(推理档位)、
  `ask_user`(**真挂起循环等用户选择**,复用 `pendingConfirmation` 的挂起模式);
- 剩余验收:`ask_user` 挂起后「停止」按钮能中断、超时/取消语义明确。清单卡片那一条已做到
  (消息里持久化 + 重开会话仍在),但仍未在模拟器上跑一次目视回归。

### T6(里程碑 M4,路线 B)远端会话与真终端

- SSE 解析器扩展到 §4 事件表;回合语义(一次输入 = 一个 while 回合);`run_terminal_command`
  卡片(输出流 + 取消,取消映射为后端 abort);
- 验收:一次远程会话里跑一条长命令,输出流式滚动、取消能杀掉进程树;断线重连不重复渲染。

### T7(低优先)生态类

`gravity_index` `skill` `browser_logs` `read_docs` `Composio`(四个):都需要外部服务接入与凭据,
在 A/B 两条路线跑通后再评估。

---

## 3. 不做(明确写下来,免得反复讨论)

- **不做任意进程执行**:Android 没有可用的 shell/运行时,`run_terminal_command` 只在路线 B 有意义;
- **不做强推 / 删分支 / 删仓库 / 递归删除本地文件**:破坏性能力不进工具集,不是「权限默认 CONFIRM」的问题;
- **不做后台常驻写入**:agent 写入必须在用户可见的会话内、逐次确认(通知栏后台任务不携带写工具);
- **不把 token 交给模型**:模型只表达「哪个仓库、哪个分支、改什么」,凭据只在执行层注入。

## 4. 排期建议

T1 → T2/T3(一个可发布的 M1,「能在手机上开 PR」)→ T5(便宜且立刻有体感)→ T4(工作区,工程量最大)
→ T6(路线 B,依赖后端)→ T7。

每个里程碑都要同时交付:工具定义 + 权限默认 + 卡片形态 + 单测(含错误处方)+ 文档更新。

---

## 5. M1 验收证据(T1 + T2 + T3)

验证命令全绿(输出摘录):

```
$ cd android && scripts/gradle.sh :core:model:test :core:data:testDebugUnitTest :app:assembleDebug
BUILD SUCCESSFUL in 1m 58s
162 actionable tasks: 40 executed, 122 up-to-date

$ python scripts/check-translations.py
检查了 5 组 locale(基准 5 组);问题 0 条

$ python scripts/check-hardcoded-strings.py
扫了 33 个文件、574 处硬编码中文;超基线 0 个文件,0 个文件可收紧
```

逐条对照验收清单:

| # | 验收项 | 结果 | 证据 |
|---|---|---|---|
| 1 | 单测:参数体检/归一、别名、权限默认 CONFIRM、分支名与路径合法性、tree/commit 请求体、六种 HTTP 处方 | ✅ | `ToolsTest`、`GitRefTest`、`ToolRegistryTest`、`ToolErrorsTest`、`ToolExecutorsTest` |
| 2 | MockWebServer 覆盖 401/403/404/409/422/429 与成功路径 | ✅ | `GithubWriteApiTest`(含「7 次请求 = 一个 commit」全链路) |
| 3 | 端到端真机:建分支 → 提交 → 开 PR → 页面核对 diff | ⛔ **未验证** | 需要一个测试仓库 + 已连接的 Git 账号(见下) |
| 4 | 确认弹窗显示目标仓库/分支/文件与变更摘要;拒绝回 `deniedByUser`;会话内「记住选择」 | ✅(真机未重跑) | `ConfirmPreview`(纯函数 + `ToolConfirmPreviewTest`)供确认框显示 `repo @ branch → base` / 文件清单 / 提交信息;拒绝信封与会话记忆是原有 `CONFIRM` 路径 |
| 5 | 文案进 `values/` + `values-zh/`,图标按钮补无障碍 | ✅ | `feature/settings`(计划模式开关)、`feature/chat`(状态条 + 确认摘要);`check-translations.py` 0 问题 |
| 6 | 验证命令全绿 | ✅ | 见上 |
| 7 | 文档更新 | ✅ | `backend-integration.md` §3.3.5 / §3.3.10 / §3.3.11 / §6、本文件、`i18n-and-accessibility.md` |

### 未能验证的部分(需要你配合)

1. **真实端到端写入**(验收 #3):需要一个测试仓库(`owner/repo`)+ 已连接的 Git 账号。
   拿到后的步骤:授权 → `github_create_branch` → `github_commit_files` 提交一个文件 →
   `github_create_pull_request` → 打开 PR 页面核对 diff。
2. **计划模式的真机演示**(开启 → 只给计划 → 关闭 → 才真的提交):同样需要真机与测试仓库。
3. **写工具的卡片形态**:四个新工具目前复用现有卡片(`running/waiting/error` + 参数 JSON),
   没做专用的「变更预览卡」(diff 行数、文件清单)。确认弹窗已经能说清目标(见 #4),
   专用的 diff 卡片留到 T4 与 SAF 工作区一起做 —— 一次改到位比现在拆两次改便宜。

### 顺带修掉的一个缺陷

`github_get_file` 以前**只**走 `raw.githubusercontent.com` 匿名通道,私有仓库永远读不到。
现在:已连接账号走 `contents` API(`Accept: application/vnd.github.raw`),未连接才回落匿名通道。
