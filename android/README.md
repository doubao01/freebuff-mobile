# Freebuff Mobile(Android)

按 `freebuff-android-prototype` 的 HTML 原型一比一实现的 **官方安卓 App**。
Kotlin + Jetpack Compose + Material 3 + Hilt + Room + OkHttp,无图片资源,全部用主题色、圆角与文本拼出原型视觉。

## 运行要求
- Android Studio(建议 Ladybug 2024.2.1 或更新)+ JDK 17
- Gradle 8.9 + AGP 8.5.2 + Kotlin 2.0.20(compileSdk 35,minSdk 26)
- 首次同步需联网拉依赖;本机无 Android SDK 时可直接用 Android Studio 打开并自动安装

## 打开方式
1. Android Studio → Open → 选择本目录 `android/`
2. 等待 Gradle Sync
3. 连接设备/模拟器 → Run ▶(包名 `com.freebuff.mobile`)

## 模块结构

```
android/
├─ app/                          入口 + DI 装配(BuildConfig 配置经 @Named 注入)
│  └─ com/freebuff/app/          MainActivity · FreebuffApplication · RootViewModel ·
│                                AppNavigatorImpl · ui/FreebuffRoot · di/AppModule
├─ core/
│  ├─ model/                     纯 Kotlin 领域模型与工具(Models/Seed/Urls/Catalog/Cleaner)
│  ├─ ui/                       设计令牌(Theme)+ 通用组件 + 导航契约 AppNavigator
│  └─ data/                     数据层
│     ├─ db/                    Room(实体/DAO/数据库)
│     ├─ security/              CryptoManager(Android Keystore AES/GCM)
│     ├─ network/               ApiClient · ApiResult(错误分类)· FreebuffApi ·
│     │                         GithubApi(设备流)· ChatRepository(SSE 流式)
│     └─ repository/            Session/Settings/CustomModel/ModelCatalog/GitAuth/Update/Migrator
└─ feature/
   ├─ auth/                     欢迎页
   ├─ chat/                     会话首页 · 对话页 · 模型选择 · 发起任务向导
   └─ settings/                 设置页 · Git 账号 · 自定义模型列表/表单 · 版本更新
```

## 已实现(对应原型功能)
- **双主题**:深色默认、浅色、跟随系统;欢迎页 Logo 点击可循环切换
- **会话**:首页列表 + 新建/删除/清空;对话页**真实 SSE 流式对话**(OpenAI 兼容端点,逐段写回消息)+ 代码块 + 上下文胶囊
- **中英双语与无障碍**:界面文案进入各模块 `res/values/`(默认英文)与 `values-zh/`(中文),跟随系统语言;
  纯字形按钮(返回 `‹`/清空 `✕`/会话菜单 `⋯`/发起任务 `+`/模型芯片等)补齐 `contentDescription`,TalkBack 可读。
  规则、不翻译的边界与验证脚本见 [docs/i18n-and-accessibility.md](docs/i18n-and-accessibility.md)
  (设置页文案仍在迁移中,详见该文档 §5)
- **发起任务向导**:三步(仓库 → 模型 → 描述);仓库支持不关联 / 手动输入(平台快捷粘贴、git clone 解析、
  分支胶囊、协议补全)/ **Git 账号真实仓库**(GitHub 设备流);官方与自定义模型分组展示
- **消息级操作**:每条消息一行操作(复制 / 编辑 / 删除),最后一条回复多一个**重新生成**;
  编辑用户消息在气泡内就地改写并重发 —— 发送前明示「会丢弃其后的 N 条消息」;单条删除二次确认。
  三条入口与「发送」共用同一个 agent 循环,规则是 `core:model.MessageOps` 纯函数(见 [docs/backend-integration.md](docs/backend-integration.md) §3.3.9)
- **长任务后台保活**:一次对话开始时启动前台服务(`dataSync` 类型),**切走或锁屏后多轮任务继续跑完**;
  运行中一条低优先级静音常驻通知显示当前轮次与在调用的工具,结束时撤下,并按结果发一条可点回的完成通知
  (跑完/失败各一种文案,用户主动停止则不再打扰)。通知权限(API 33+)被拒不影响功能,
  实现与边界见 [docs/backend-integration.md](docs/backend-integration.md) §3.3.8
- **工具调用**:16 个端侧工具(联网搜索/抓取、GitHub 搜索与读文件/README/分支、写路径建分支/提交/开 PR、任务清单、计算器、当前时间、核心记忆读写、子代理派发)+
  逐工具三级权限(免确认/需确认/禁止)、参数体检、带处方的错误信封、重复调用复用与连败熔断
- **设置**:外观(主题三档)、模型(当前模型 → 选择模型)、集成(Git 账号 / 仓库地址解析严格↔宽松 / 自定义模型)、
  会话与数据(数据本机说明 + **二次确认**清除全部会话)、关于(说明弹层 / 版本检查 / 官方网站 / 反馈与建议)
- **自定义模型**:添加/编辑表单(Base URL 实时预览完整请求路径、中转站直接粘贴、密钥显示切换、TLS 开关、
  请求头、**真实测试连接** → `/v1/models` 可用列表点选、快照随记录保存、从快照重建);列表行展示完整请求路径与
  「上次可用 N 个模型」快照展开区(模型行状态点:上次连通时间与延迟 + 就地重新测试连接);
  启动自动清洗损坏记录并展示「已自动修复」报告(逐条差异 + 二次确认恢复原始记录,仅会话内)
- **端侧子代理**:`spawn_subagent` 在端上**真执行** —— 派一个只读子代理(researcher 联网调研 / code_reader 读公开仓库 /
  analyst 纯计算),它有自己的上下文与工具白名单、最多 3 轮工具,之后强制收敛成结论;结论压缩后回填主循环,
  卡片显示实时尾部(实现与边界见 [docs/subagent-design.md](docs/subagent-design.md) §12)
- **数据**:Room 持久化 + 一次性 SharedPreferences 迁移(`freebuff_proto_v1`,带 `migrated_v2` 完成标志,
  **迁移只执行一次**——清空会话后重启不会复活演示会话,旧偏好也不会覆盖后来的修改);API Key(Git token / 自定义模型 / 搜索接口)经 Keystore 加密

## 后端接入(网络层与错误处理)

完整接口/错误分类/回退策略见 **[docs/backend-integration.md](docs/backend-integration.md)**。

多语言与无障碍(文案归属、`contentDescription` 约定、完整性检查)见 **[docs/i18n-and-accessibility.md](docs/i18n-and-accessibility.md)**。

后端 agent 运行时的行为参考(工具注册表、工具调用执行管线、agent 双层循环、事件流契约)见 **[docs/agent-architecture.md](docs/agent-architecture.md)**——对接完整 agent 会话流时,事件类型与渲染语义以此为准。

配置项在 `app/build.gradle.kts` 的 `buildConfigField`(留空即走「未配置」路径,不影响编译与体验):

| 配置 | 作用 | 未配置行为 |
|---|---|---|
| `DEFAULT_GATEWAY_BASE_URL` | 官方网关根地址(如 `https://api.example.com/v1`) | 官方目录使用内置列表;官方模型对话提示未配置 |
| `GITHUB_OAUTH_CLIENT_ID` | GitHub OAuth App 的 client_id | Git 账号接入不可用,弹层内就地提示未配置 |
| `UPDATE_URL` | 版本检查 JSON 地址 | 默认已是公开可达的仓库 `dist/update.json`;换自建源用 `freebuff.updateUrl` / `FREEBUFF_UPDATE_URL` |

版本号唯一来源是 [`version.properties`](version.properties):app 的 `versionName`/`versionCode` 与
`core:model` 的 `LATEST_VERSION` 均由它派生(构建时生成),发版只改这一个文件;发版工作流会用
[`scripts/update-manifest.py`](../scripts/update-manifest.py) 重建 `dist/update.json` 并回推 `master`。

要点:
- 所有网络调用返回统一 `ApiResult<T>`(`Ok` / `Err(ApiError)`),错误分类含超时/DNS/连接/TLS/HTTP(保留状态码)/解析/未配置
- 阻塞式请求统一经 `apiCallIo` 切到 `Dispatchers.IO`;流式对话用异步 `enqueue` + SSE 增量解析
- 失败永不静默:目录失败保留内置并提示原因、仓库读取失败可重试、更新检查区分「未配置 / 网络失败 / 更新源 404」并指明实际请求的主机名

## 设计说明
- 设计令牌与 HTML 原型同源(见 `freebuff-android-prototype/css/app.css`),替换品牌色只需改 `core:ui` 的 `Theme.kt`
- Git 授权选择 OAuth 设备流:无需自定义 scheme 回调与后端中转服务,只需一枚 client_id
- 文案沿用原型的中文界面,便于直接进入联调

## 打包 APK

完整流程(工具链、签名、R8 注意事项、排错)见 **[docs/build-and-release.md](docs/build-and-release.md)**。

```bash
cd android

# Debug 包(日常调试, ~18 MB)
scripts/gradle.sh :app:assembleDebug
# → app/build/outputs/apk/debug/app-debug.apk

# Release 包(R8 压缩 + 资源收缩, ~1.4 MB)
scripts/gradle.sh :app:assembleRelease
# → app/build/outputs/apk/release/app-release.apk

# 单元测试(model + data)
scripts/gradle.sh :core:model:test :core:data:testDebugUnitTest

# 翻译完整性(默认语言与各 locale 的 key/格式参数必须一致)
python scripts/check-translations.py

# 有没有把中文写死在 Kotlin 里(与基线比数量,新增一处即失败;--update 收紧)
python scripts/check-hardcoded-strings.py
```

| 变体 | 大小 | 说明 |
|---|---|---|
| Debug | ~18 MB | `debuggable`,含 Compose 预览工具,可直接装机体验 |
| Release | ~1.4 MB | R8 压缩 + 资源收缩,minSdk 26 / targetSdk 35 |

两点注意:

- **签名**:有 `android/keystore.properties` 时 release 用你的正式 keystore 签名,没有时自动回退 debug 证书(仅本地安装,不可上架)。一键生成正式密钥:`scripts/gen-release-keystore.sh`。详见文档第 4 节。
- **工具链**:位于 `android/.toolchain/`(JDK 17 + SDK 35 + Gradle 8.9)。系统默认 `JAVA_HOME` 为 JDK 8,无法运行 AGP 8.5,所以本机构建统一走 `scripts/gradle.sh`(它把 Java 固定到自带 JDK 17)。仓库依赖以阿里云镜像优先,官方源仅作兜底。
