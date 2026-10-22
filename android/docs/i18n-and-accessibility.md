# 多语言与无障碍(i18n & a11y)

这篇文档说明:文案放在哪、哪些文案**故意**不翻译、无障碍描述怎么加,以及怎么验证。
新增界面文案时按这里的规则做,就不会出现「英文设备上冒出一句中文」。

---

## 1. 语言方案

| 位置 | 语言 | 作用 |
|---|---|---|
| `res/values/strings.xml` | **英文** | 默认值,也是所有未匹配语言的兜底(Android 惯例) |
| `res/values-zh/strings.xml` | 中文 | 设备语言为中文时生效(或被应用内语言开关选中) |

**应用内语言开关**:设置页「外观」组里可以选跟随系统 / 简体中文 / English,实现在
`core/ui/AppLanguage.kt`(存 `SharedPreferences("freebuff_app").language`,不进 Room —— 它必须在
`attachBaseContext` 里同步读到,那时还没有协程与 Hilt)。生效路径按系统版本分两条:

- **API 33+**:交给系统的**应用级语言** `LocaleManager.setApplicationLocales`。Activity 与
  Application 的字符串一起切,界面由系统重建(所以那种情况下系统「应用 → 语言」里也能看到本应用)。
- **API 26–32**:没有该 API,退化为在 `MainActivity.attachBaseContext` 里包一层 Configuration,
  并自己 `Activity.recreate()`。此时 Compose 的 `stringResource` 立即跟随,但用
  `@ApplicationContext` 取文案的少数 ViewModel 提示条要等下次启动才切 —— 这是退化路径的已知代价,
  不是漏翻译。

语言名(「简体中文」/「English」)在两种语言里都写母语形式,这是语言选择器的惯例;
只有「跟随系统」跟着界面语言翻译。

- **每个 UI 模块各有一份**,就近管理自己用到的文案:`app/`、`core/ui/`、`feature/auth/`、`feature/chat/`;
  组件内部产生的文案(如 `ModelChip` 的空态)归组件所在的模块,**调用方传入的文案由调用方负责**。
- 不要在 `core/*` 里放纯装饰性文案 —— 它们没有 `Context`,放进去就得被迫发明一套取资源的办法。
- **`:app` 的 namespace 是 `com.freebuff.mobile`,与 Kotlin 包 `com.freebuff.app.*` 不同**,所以 app 模块里用
  `R` 必须显式 `import com.freebuff.mobile.R`;其余模块 namespace 与包名一致,直接用 `R`。
- Compose 里读数:**组合期用 `stringResource(R.string.x)`;ViewModel 里用注入的 `@ApplicationContext` +
  `context.getString(...)`**(`ChatViewModel`/`HomeViewModel`/`TaskWizardViewModel` 都是这么做的)。
- 顶层 `val`(非 @Composable)不能调 `stringResource` —— 存**资源 id**(`R.string.x`)到组合期再解析,
  见 `ChatScreen` 的 `CHAT_SUGGESTS`。
- 带参数的文案一律用**位置参数**(`%1$s`/`%1$d`),不要用 `%s`:译文语序不同,顺序参数会错位。

## 2. 哪些文案**不**翻译(边界)

翻译它们是**改行为**,不是改界面:

| 类别 | 位置 | 原因 |
|---|---|---|
| 给模型看的提示词(摘要压缩、记忆提取) | `ChatViewModel`(EXTRACT_PROMPT、llmSummarizer) | 模型读的指令,与用户界面语言无关 |
| 工具说明书与错误信封 | `core/model/Tools.kt`、`ToolErrors.kt` | 同上;且这些文本已被大量单测断言 |
| 参数/路径校验与工具结果说明 | `core/model/GitRef.kt`、`core/data/tools/ToolExecutors.kt`、`core/data/network/GithubApi.kt` | 同上:都是回给模型的「问题 / 怎么改」文本,翻译会改变模型行为 |
| 工具执行结果里的补充说明 | `ChatViewModel`(记忆检索/子代理参数体检) | 作为 observation 回传模型 |
| `@文件` 这类插入 token | `ChatScreen` Composer | 插进输入框后是一段要发给模型的文本,不是标签 |
| 会话状态牌/停止标注 | `core/model/MsgSteps.kt` | 纯模块无 `Context`;要做多语言需按 §5 的方式把文案参数化 |

已经**参数化**的例子:`AgentNotify.runningTitle(..., runningLabel)` —— 规则(截断/首行/回落)留在纯模块并继续可单测,
`:app` 的服务把 `getString(R.string.notify_running)` 传进来,于是通知外壳也跟着系统语言走。

## 3. 无障碍:图标按钮必须自述

原则:**可见文字本身就是读屏文案**的按钮(「发送」「取消」「删除」)不用加描述;只有**纯字形/图标**的控件才需要,
否则 TalkBack 只会念出一个符号(「‹」「⋯」「▤」)。

两种写法,按场景选:

```kotlin
// ① 字形对用户没有任何意义(「‹」「✕」「⋯」「▤」「+」):换成明确描述
val cd = stringResource(R.string.chat_cd_back)
Text("‹", modifier = Modifier
    .clickable(onClickLabel = cd, role = Role.Button) { onBack() }
    .clearAndSetSemantics { contentDescription = cd }
    .padding(...))

// ② 内容已经可读(模型名、工具卡片的名称+状态),只是用途不明:补一个动作标签即可
Modifier.clickable(
    onClickLabel = stringResource(R.string.cd_model_picker),
    role = Role.Button,
) { onClick() }
```

- `clearAndSetSemantics` 用于①:连子节点的文字一起丢掉,避免「‹ · 返回」这种重复播报。
- `role = Role.Button` 让读屏宣布「按钮」,而不是只说一段文字。
- **不要在 `Modifier.semantics {}` 的 lambda 里调 `stringResource`**(那不是 @Composable 上下文):
  先在组合期取成局部变量,再赋给 `contentDescription`。
- 当前已覆盖:欢迎页 Logo(切主题)、对话页返回 `‹`、思考块与工具卡片的展开/收起、消息级操作、搜索框清空 `✕`、
  会话行与 `⋯` 菜单、模型芯片、底部「+」标签、输入区 `@` / `▤`;设置页的三个「可展开行」
  (工具权限、自定义模型快照、高级设置)补了 `onClickLabel`,字形 `▸/▾` 从无障碍树里摘掉(`clearAndSetSemantics`),
  不然读屏只会念出一个箭头、听不出点是展开还是收起。

## 4. 验证

```bash
cd android

# ① 翻译完整性:默认语言与各 locale 的 key 集合、格式参数必须一一对上(漏翻/占位符写错都会报错并退出码 1)
python scripts/check-translations.py

# ①b 有没有「又写死在 Kotlin 里」的中文:与基线逐文件比数量,新增一处就失败(退出码 1)
python scripts/check-hardcoded-strings.py
python scripts/check-hardcoded-strings.py --list    # 排查时看每处位置
python scripts/check-hardcoded-strings.py --update  # 迁移完成后收紧基线(数值只降不升)

# ② 编译 + 单测(文案/通知规则的纯函数测试在这两层)
scripts/gradle.sh :core:model:test :core:data:testDebugUnitTest :app:assembleDebug

# ③ 产物级核对:APK 里两种语言是否都真的带上了(默认英文 + zh 中文)
AAPT=.toolchain/sdk/build-tools/35.0.0/aapt2
"$AAPT" dump resources app/build/outputs/apk/debug/app-debug.apk > res.txt
grep -A2 "string/chat_home_title" res.txt     # 应看到 () "Chats" 与 (zh) "会话"
```

①只覆盖「已经迁进资源」的文案,漏在 Kotlin 里写死的中文它看不见 —— 第 ①b 步补的就是这一半:
`scripts/hardcoded-strings-baseline.txt` 记下每个文件**当前**还允许的字面量数量,多一句就报错。
用「基线 + 棘轮」而不是白名单,是因为提示词、工具说明书这类**故意不翻译**的文案(§2)与界面代码在同一个文件里,
白名单只能整文件放行;棘轮则能挡住「在已迁移的文件里又写死一句中文」,同时不逼着人先搬完存量。

## 5. 已完成 / 待办

已完成(文案全部进资源 + 图标按钮补无障碍):

- `app/`:底部标签、崩溃横幅与提示、长任务通知的渠道名与外壳、通知权限提示;`+` 标签加描述
- `core/ui/`:模型芯片(空态文案 + 动作标签)
- `feature/auth/`:欢迎页全部文案(含 Logo 描述)
- `feature/chat/`:对话页(含计划模式状态条与写工具确认摘要 `chat_confirm_files`)、会话列表、模型表、会话菜单、发起任务向导、三个 ViewModel 的提示条
- `feature/settings/`:设置页、Git、自定义模型列表/表单、更新面板、关于 —— **代码里已无中文字面量**
  (194 条 × 2 语言,`check-hardcoded-strings.py` 基线里这一整个模块是空的)

**UI 模块(`app/`、`feature/`、`core/ui/`)的界面文案已经清完**,剩下的都在没有 `Context` 的纯模块里,分两类:

1. **给模型看的文案(§2 明确不翻译,继续留中文)**:`core/model` 的工具说明书/错误信封
   (`Tools.kt` 170 处、`ToolErrors.kt` 76 处、`TodoList.kt` 22 处、`GitRef.kt` 24 处)与 `core/data` 的提示词、
   工具执行说明与 GitHub 请求契约(`ToolExecutors.kt` 90 处、`GithubApi.kt` 13 处)。
   注:数字随 M1(T1/T2/T3)新增四个 GitHub 工具与写路径校验而上升 —— 上升的部分全部落在上面的
   「不翻译」类别里,`app/`、`feature/`、`core/ui/` 的界面文案仍然一律进资源(基线里这三个目录为空)。
2. **确实会显示给用户、但目前没法直接翻译的**:
   - `core/data` 的网络错误文案(`ApiResult.kt` 35 处):设计上「本地化后交给 UI 直接展示」(见该文件类注释),
     但数据层没有 `Context`。要真正多语言,得让 `ApiError` 带上**结构**(它已经是 sealed class:状态码/目标/主机名都有),
     由 UI 侧按类型映射到资源,`message` 只当兜底。
   - `core/model` 的状态牌与会话分组(`MsgSteps.kt` 13 处:连接 / 正在生成 / (已停止生成);
     `SessionGrouping.kt` 9 处:今天 / 昨天 / 7 天内)、工具卡片名(`AgentEvents.kt` 38 处「读取文件」这类)。
     迁移方式与 `AgentNotify` 相同:**保留中文默认值 + 增加文案参数**,由有 `Context` 的一侧传入,
     纯模块的单测不受影响。

这些存量的**数量**已经落在 `scripts/hardcoded-strings-baseline.txt` 里:改一块、`--update` 降一次,
进度不再靠人肉数,数字也不会反弹。

另有一处读屏细节待补:**`stateDescription`** —— 「运行中/已完成」这类状态目前靠可见文字表达,
状态类控件可以再补一个朗读用短句(例如工具卡片)。
