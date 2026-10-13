# 工具调用说明书质量基线

「说明书(工具描述 + 示例 + 参数名 + 错误信封)到底教会了模型多少」——用同一批任务在真机跑,
把答案变成三个数字:**第一次就调对率**、**任务完成率**、**平均轮次**。换模型、改说明书,数字应当可比。

## 1. 指标口径

| 指标 | 定义 | 说明 |
|---|---|---|
| 首调正确率 | 本回合**第一个调用**的工具名正确 + 必填参数齐全 + 取值合法 + 没有编造参数名 | 别名(`file`→`path`、`q`→`query`、`expr`→`expression`)算对,单独计「别名调用」 |
| 任务完成率 | 首调正确 且 正文命中该任务的答案判据(可叠加库内断言) | 首调错但靠错误信封救回来,不计入完成率 —— 那是「自愈」不是「说明书好」 |
| 平均轮次 | 每任务平均工具轮次(logcat `round N start` / `tool req … round=N`) | 越大越费钱、越慢 |
| 别名调用 | 用别名满足必填的任务数 | 别名用得越多,说明模型越记不住规范名(说明书的别名表有用,但也在提示该默认名不够自解释) |
| 幻觉工具调用 | 调了注册表里没有的工具名(次数) | `get_time`、`docker_list` 这类 |
| 出错但仍答对 | 收过错误信封、最终正文仍答对的任务数 | 衡量「错误信封 + 说明书」的兜底能力 |

## 2. 取数方式(为什么不是读库里的工具卡)

库里 `messages.toolsJson` 的 `input` 是**给人看的参数摘要**(`summarizeInput` 只保留
`path/paths/command/pattern/url/query/prompt/goal` 里第一个命中的**值**,键名会丢),
回归脚本读它分不清「参数名写错」和「摘要只留了值」。因此 App 侧加了一行调用轨迹:

```
I/ChatViewModel: tool req sid=291769 round=1 name=web_search args={"query":"Android 15 edge-to-edge"}
```

跑批脚本优先读这一行(`origin=trace`);老包没有这行时回退到卡片摘要(`origin=card`),
回退模式只能还原「首个主参数」,报告里会明确标出来。轨迹行在 `dispatchTool` 之前,
**未知工具名也会被记下来**,幻觉统计因此可信。

## 3. 任务集(12 条;原脚本 `verify/bench_tasks.py` 已移除)

| 任务 | 用户话术(原样发给 App) | 期望首调 | 考点 |
|---|---|---|---|
| t_time | 现在几点了?顺便告诉我今天星期几 | `current_time` | 无参工具(空 arguments 也要能跑) |
| t_calc | 帮我精确算一下 (12+8)*3.5,不要自己心算 | `calculator` | 「别心算」的顺从度 + 表达式取值 |
| t_calc2 | 1.5 万的 7% 是多少?再平均分给 3 个人 | `calculator` | 两步计算:该调工具却直接作答算失败 |
| t_calc_alias | 用 calculator 工具算一下 3+4*2 的结果 | `calculator` | 别名 `expr` 的正面用例 |
| t_search | 帮我查一下 Android 15 是不是强制 edge-to-edge | `web_search` | 工具选择(搜索 vs 抓取)+ 关键词质量 |
| t_fetch | 读一下 https://example.com 这个页面讲了什么 | `web_fetch` | 有链接就直接抓,别绕搜索 |
| t_gh_readme | CodebuffAI/freebuff 这个仓库的 README 讲了什么?给我 5 条要点 | `github_get_readme` | 与 `github_get_file` 的语义边界 |
| t_gh_file | 读一下 GitHub 上 CodebuffAI/freebuff 仓库里的 README.md 文件内容 | `github_get_file` | 三个必填(owner/repo/path)一次写全 |
| t_mem_save | 记住:我做 Android 开发,回答尽量简短 | `save_memory` | `block` 闭集取值 + 确认弹窗后落库 |
| t_mem_recall | 我上次让你记住的偏好是什么? | `memory_recall` | `user_id` 别漏(漏了就是必填缺失) |
| t_no_tool | 用 Kotlin 写一个带重试的下载函数,直接给代码就行 | 不调工具 | 该不调就不调(纯代码题) |
| t_unknown | 用 docker_list 工具帮我列出本机正在跑的容器 | 不调工具 | 不存在的工具:如实说明,而不是幻觉一个调用 |

`verify/check_bench_registry.py`(已移除) 会校验任务集里的参数名/别名与出厂 `Tools.kt` 一致;
改了工具参数**必须同步** `bench_tasks.REGISTRY`,否则首调正确率会判错。

> 注:本节与 §4 用到的 `bench_tasks.py`、`bench.py`、`bench_weakmock.py`、`check_bench_registry.py` 均已移除(随本地工装清理删除,未入库不可恢复);任务集清单与下表实测结果保留备查(`dbexec.py`、`dbdump.py` 仍在库)。

## 4. 怎么跑

```bash
# 前置:App 已装、设备已连、模型行的 base 指向目标端点
# 注:bench_tasks.py / bench.py / bench_weakmock.py / check_bench_registry.py 已移除(随本地工装清理删除),以下命令保留备查(dbexec.py 仍在)
python verify/check_bench_registry.py                 # 先确认注册表没漂移

# A. 负对照:脚本化「弱模型」(故意幻觉工具名/编造参数/漏必填/用别名/只回文本)
python verify/bench_weakmock.py &                     # 8898
python verify/dbexec.py "update custom_models set base='http://10.0.2.2:8898/v1' where id='id9949695'"
python verify/bench.py --label weakmock --no-switch

# B. 真实模型:切 apiId 后跑(base 必须是真实端点)
python verify/bench.py --label grok-4.7
python verify/bench.py --label <小模型 id> --tasks t_time,t_calc --repeat 3   # 想只看确定性任务时
```

产物:`verify/bench_report.md`(可读报告)、`verify/bench_results.json`(逐任务明细,含首调参数原文)。
每任务独立开一个新会话,避免上一题的工具历史污染下一题。

## 5. 结果

| 模型 | 任务数 | 首调正确率 | 任务完成率 | 平均轮次 | 平均耗时 | 别名调用 | 幻觉工具调用 | 出错但仍答对 |
|---|---|---|---|---|---|---|---|---|
| weakmock(脚本化负对照) | 12 | 50% | 50% | 1.9 | 55.2s | 6 | 2 | 5→5 |
| grok-4.7(真实端点参考) | 12 | 75% | 75% | 3.5 | 159.5s | 0 | 0 | 0 |

两侧失败点完全不在同一处,这正是分开看「首调 / 选工具 / 轮次」的价值:

- **负对照(弱模型形态)**:错在**工具名与参数** —— 幻觉 `get_time`/`docker_list` 各一次、`calculator` 带编造参数 `mode`、
  `github_get_file` 只给 `file`(缺 owner/repo)、`memory_recall` 缺 `user_id`;还有一次「该调工具却直接口算」。
  5 个收到错误信封的任务最终都答对了(5→5),说明信封+说明书能把弱模型的错**捞回来**,但首调仍然算错。
- **grok-4.7(强模型)**:0 幻觉、0 错误信封、0 别名 —— 工具名与参数一次到位;三处失败全在**选工具/该调不调**:
  `t_fetch` 给了明确链接却去 `web_search`、`t_gh_readme` 拼 raw 链接走 `web_fetch` 而不是 `github_get_readme`、
  `t_mem_save` 说了「记住」却没调 `save_memory`。它的平均轮次也更高(3.5):多出的轮次主要来自端上续跑提醒
  (每任务 0~3 次 `nudge`,模型常常只回正文、不调 `task_completed`),这是等待时间的大头。

**结论(说明书该往哪补)**:参数体检与错误信封已经够用(强模型零命中、弱模型能被救回);
下一处要补的是**交叉指引** —— 在 `web_search`/`web_fetch` 的描述里写清「目标若是公开仓库里的文件/README,
换成 github_* 工具,不要自己拼 raw 链接」「用户已经给了链接就直接 `web_fetch`,不要再搜一遍」,
并把「用户说『记住…』」明确绑到 `save_memory`。

## 6. 已知边界

- 联网类任务(`t_search`/`t_fetch`/`t_gh_*`)结果受上游影响,不稳定的是**答案**不是**调用**;
  首调正确率不受影响(它只看模型发出的第一个调用)。
- `weakmock` 不是模型,它是**负对照**:用来证明指标能抓住错误;真实小模型请用 `--label <模型 id>`。
- 单次运行有随机性,想看稳定性用 `--repeat 3`。
- 平均耗时含 `settle` 轮询粒度(约 1~2s)与端上续跑提醒的往返,不适合当作模型纯推理速度。
