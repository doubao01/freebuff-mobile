# 构建与打包 APK

本文档记录 Freebuff Mobile(Android 原生工程)的构建、打包、签名、安装与**发版**流程。所有命令均在本机验证通过。

## 0. 发版流程(自动化,推荐)

推送 `v*` 标签即自动发版:[`.github/workflows/release.yml`](../.github/workflows/release.yml) 会跑单测、构建 release APK、校验「标签 = versionName」、创建 GitHub Release 并附上按版本命名的 APK,最后把 `dist/update.json`(App 内「检查更新」的数据源)刷成本次版本并回推 `master`。

**版本号唯一来源是 [`android/version.properties`](../version.properties)**:app 的 `versionName`/`versionCode` 与
`core:model` 的 `LATEST_VERSION`(设置页与「检查更新」显示的基准)**都由它构建时生成** ——
发版只需改这一个文件,标签必须与其中的 `versionName` 一致。

```bash
# 1. 提升 android/version.properties 的 versionCode / versionName 并提交推送
git push origin master
# 2. 等 Android CI 全绿
gh run watch   # 或看 Actions 页面
# 3. 打标签推送 → 发版自动完成
git tag -a v1.2.0 -m "v1.2.0: 一句话说明" && git push origin v1.2.0
```

说明:

- **版本一致性硬校验**:标签 `vX.Y.Z` 与 `versionName` 不一致时工作流直接失败,不会发出版本号对不上的包。所以先推版本号提升、再打标签。预发布标签(`v1.2.0-rc1`)取主版本部分比对。
- **更新源自动同步**:发版后 `dist/update.json` 被重写为
  `{"version":"<标签版本>","url":".../releases/latest","notes":["<上一个标签到本标签的提交标题>"]}`
  并提交回 `master` —— 默认 `UPDATE_URL` 直链这个文件,所以「发布的版本号」与「App 检查更新看到的版本」不会再漂移。
  回推三次都失败、或线上校验读到的版本与标签不一致时,本次发版会被判失败(更新通道不能静默停在旧版本)。
- **Release 说明自动生成**:取上一个标签到本标签之间的提交标题(`- 标题 (短哈希)` 逐条列出);首个标签没有可比对的上一个标签时写「首个自动发版」。发布后可在 Releases 页面编辑润色。
- **产物命名**:`FreebuffMobile-<版本>-release.apk`(与第 3 节的 `dist/` 约定一致)。
- **包自证**:发版前会验产物的签名证书与包内 `versionName`/`versionCode`(与 `version.properties` 必须一致,不一致直接失败);
  用的是 debug 证书时在日志里给出显式告警,并把提示写进 Release 说明 —— 避免「以为发的是正式签名包」。
- **重复标签保护**:同一标签推送两次,第二次在创建 Release 时报「已存在」而失败,属预期保护;要重发需先删标签与 Release。
- **发版前过端到端矩阵**:创建 Release 之前先在模拟器上对**这个刚打出来的 APK**跑完 29 个场景,有 FAIL 直接失败(不发版);报告进 Step Summary、现场进 `e2e-matrix` artifact。详见 0.3。
- 该工作流不跑 lint(与 CI 分工:CI 管 PR/分支质量门禁,Release 管出包),签名规则见第 4 节 —— 未配置 `keystore.properties`(CI 上即 Secrets 未注入)时出 debug 证书签名的包。

### 0.1 干跑:不打正式标签先验一遍

正式标签会真的发出一个 Release(不可撤销),所以发版链本身可以先用同一份工作流干跑一遍:
**Actions → Android Release → Run workflow**(选 `master` 分支),输入:

| 输入 | 说明 |
|---|---|
| `tag` | 要模拟的发版标签,如 `v1.0.0`;留空 = 取 `version.properties` 的 `versionName` |
| `dry_run` | 默认 `true`。干跑不发 Release,重建的 `dist/update.json` 只推到临时分支 |
| `manifest_branch` | 干跑时清单回推的分支,默认 `dryrun/update-manifest` |

与正式发版的差异只有两处:**不创建 GitHub Release**、**清单不回推 `master`**;其余步骤(标签＝versionName 硬校验、
单测、R8 release 包、APK 签名与包内版本自证、清单重建、API/raw 线上自证)完全一致。工作流还会额外断言
`master` 上的清单没被干跑改动过 —— 干跑不允许影响真正的更新通道。

```bash
gh workflow run release.yml -f tag=v1.0.0 -f dry_run=true    # 效果同上,命令行触发
gh run watch                                                 # 跟着看
```

干跑会留下一个临时分支(它本身就是「回推成功」的证据),确认没问题后删掉:

```bash
git push origin --delete dryrun/update-manifest
```

本地也能干跑同一套护栏(不依赖 CI,秒级反馈;脚本在真机验证工具目录 `android/verify/`,不入库):

```bash
# 注:verify/dryrun_release.py 已移除(随本地工装清理删除,未入库不可恢复),以下命令保留备查;护栏本身仍在 release.yml 的 run: 块里
cd android && python verify/dryrun_release.py            # 15 项:解析输入/标签护栏/清单重建+回推/自证/签名与版本
python verify/dryrun_release.py --no-push                # 只验证重建与自洽,不写远端
python verify/dryrun_release.py --only-verify            # 只重跑「线上自证」步骤
```

它直接把 `release.yml` 里真实的 `run:` 块抽出来执行(而不是重写一份等价逻辑),所以工作流改了它也跟着变。

### 0.2 更新源与版本清单(手动排查用)

清单格式(`core/data` 的 `UpdateRepository` 解析 `version` / `summary` / `notes[]` / `url` / `apk`):

```json
{
  "version": "1.2.0",
  "url": "https://github.com/doubao01/freebuff-mobile/releases/latest",
  "summary": "一句话可读摘要(更新面板顶部那行)",
  "notes": ["..."],
  "apk": {
    "url": "https://github.com/doubao01/freebuff-mobile/releases/download/v1.2.0/FreebuffMobile-1.2.0-release.apk",
    "sha256": "<64 位小写十六进制>",
    "size": 1488526
  }
}
```

- `summary` / `apk` 都是**可选**扩展:缺了照样能检查更新,只是更新面板退化成「前往下载」跳发布页。
- 带上 `apk` 则面板内**直接下载并校验 sha256** 再交系统安装器 —— 指纹由发版工作流从真实产物算出
  (`--apk-file`),不靠人工填;校验不过的包会被丢弃,不会交给安装器。

- **默认地址**:`https://raw.githubusercontent.com/doubao01/freebuff-mobile/master/dist/update.json`
  (仓库公开,匿名可读;App 只发一次 GET,无任何密钥)。
- **换源**:在 `android/local.properties` 写 `freebuff.updateUrl=…`,或打包时设环境变量 `FREEBUFF_UPDATE_URL=…`
  (二者都注入 `BuildConfig.UPDATE_URL`)。
- **地址必须是 https**:targetSdk 28+ 的 Android 默认禁止明文 HTTP,`http://` 更新源会被系统拦住,
  App 侧显示「地址不被允许:Android 默认禁止明文 HTTP,请把该地址换成 https」。
- **本地生成 / 校验**(与 CI、发版工作流同一份脚本):

```bash
python3 scripts/update-manifest.py 1.2.0 --prev v1.1.0   # 生成(不给 --prev 就取最近提交)
python3 scripts/update-manifest.py 1.2.0 --prev v1.1.0 --apk-file android/dist/FreebuffMobile-1.2.0-release.apk
python3 scripts/update-manifest.py --check               # CI 同款校验
python3 scripts/update-manifest.py 1.2.0 --prev v1.1.0 --release-notes   # 可读变更摘要(Release 正文用)
```

> `--release-notes` 把提交标题按「修复 / 新增 / 改进 / 文档 / 发版与工程」分组并挑一句摘要,
> Release 正文与清单的 `notes` 同源 —— 分组规则只维护一处(`scripts/update-manifest.py`)。

- **不要手工把清单版本改得比 `version.properties` 更高**:那会让所有用户看到「有新版本」却永远装不上,
  CI 会直接拦下;补发旧标签时脚本也会拒绝把清单降级。

### 0.3 端到端矩阵门禁(发版前必过)

单测过的是策略,矩阵过的是「装上去、点下去到底什么反应」。发版工作流在**创建 Release 之前**先在模拟器上
对刚打出来的那个 APK 跑完 29 个场景 —— 不重新构建、也不从 Release 下载,所以不存在「测的包 ≠ 发的包」;
**有 FAIL 就直接失败,不发 Release**。干跑同样会跑这一关(干跑的定位就是「同一条链先走一遍」)。

跑的内容就是 `verify/matrix.py` 的 `CASES`:流式适配(空参数/并行调用/多行帧/坏参修复/错误帧/遗留
`function_call`/思考参数/端点拒 tools)、循环与体验(进度播报要提醒续跑、未知工具、工具报错、重复调用复用、
30 轮上限)、失败态(500 重试/401/流中断/空回复/思考参数被拒/合并处方),以及**杀进程三连**(流开即杀、
半途正文杀、确认弹窗等待时杀 → 冷启动自动修复 + 幂等 + 界面无陈旧步骤)。服务端断言落在
`verify/mock_matrix.log`,设备端落库断言汇成 `verify/matrix_report.md`。

报告去向:完整表格贴进 **Step Summary**;`e2e-matrix` artifact 里留报告、状态清单、mock 日志,
以及每个 FAIL 场景的**截图 + 那一轮 `ChatViewModel` 日志**(`verify/fail_<场景>.png|log`)。

本地复现(与 CI 同一条命令,模拟器已起即可):

```bash
cd android
python verify/ci_run_matrix.py --apk dist/FreebuffMobile-0.0.3-release.apk
# 本地迭代:复用已装的包 / 已配好的模型,省掉装机与引导那两分钟
python verify/ci_run_matrix.py --apk dist/FreebuffMobile-0.0.3-release.apk --skip-install --skip-bootstrap
```

CI 上的三个硬前提(都是踩过才有结论的):

- 镜像必须是 **google_apis**(不能是 playstore):矩阵靠 `adb root` 直接读设备库核对落库内容。
- release 包未声明明文流量,`http://10.0.2.2:8899` 会被系统直接拦掉 —— 所以 mock 包一层自签 TLS
  (`verify/mock_matrix_tls.py` + `verify/tls/`),模型侧打开「跳过 TLS 证书校验」(产品自带的开关)。
- 全新安装得先被带到「能用」的状态(访客模式 + 建好自定义模型并选中),由 `verify/ci_bootstrap.py`
  走 UI 完成 —— 模型记录里的 API Key 是 Keystore 加密的,库外写不进去;这条路径顺带就是新手引导的回归。

## 1. 工具链

工具链已随工程放置在 `android/.toolchain/`(体积大,不入库):

| 组件 | 版本 | 路径 |
|---|---|---|
| JDK | 17.0.20.1+1 | `.toolchain/jdk-17.0.20.1+1` |
| Android SDK | platform-35 / build-tools 35.0.0 | `.toolchain/sdk` |
| Gradle | 8.9 | `.toolchain/gradle-8.9` |

**本机系统 `JAVA_HOME` 是 JDK 8,而 AGP 8.5 要求 JVM 11+** —— 直接用 `gradle` / `./gradlew` 会卡在
`checkDebugAarMetadata` 并报类路径解析失败。因此本机构建一律通过
[`scripts/gradle.sh`](../scripts/gradle.sh) 启动,它会把 Java 固定到 `.toolchain` 里的 JDK 17 再调用 Gradle:

```bash
scripts/gradle.sh :app:assembleDebug
```

> 这个 JDK 路径**不能**写进 `gradle.properties` 的 `org.gradle.java.home`。那样虽然在本机能跑,但绝对路径
> 是机器专属的,入库后会让 GitHub Actions(ubuntu)因找不到目录而直接构建失败。
> 若要用自己的 JDK 17 或 Gradle,设 `FREE_BUFF_JDK` / `FREE_BUFF_GRADLE` 覆盖。

依赖仓库在 `settings.gradle.kts` 中**按环境切换优先级**(两套仓库都在列表里, 只是先后不同):

| 环境 | 顺序 | 理由 |
|---|---|---|
| 本机(`CI` 未设) | 阿里云镜像 → 官方源 | 本机直连 mavenCentral / gradlePluginPortal 会长时间阻塞 |
| CI(`CI=true`) | 官方源 → 阿里云镜像 | 镜像对个别构件会返回 502 / 缺件, 空缓存的新 runner 上会把插件解析打挂; runner 本身直连官方源正常 |

## 2. 打包命令

```bash
cd android

# A. Debug 包 —— 日常调试,含 Compose 预览工具,体积大,启动快可断点
scripts/gradle.sh :app:assembleDebug

# B. Release 包 —— R8 代码压缩 + 资源收缩,发布/体验用
scripts/gradle.sh :app:assembleRelease

# C. 两者一起,并跑单元测试
scripts/gradle.sh :app:assembleDebug :app:assembleRelease \
  :core:model:test :core:data:testDebugUnitTest
```

首次构建需要下载依赖(约 900 MB 缓存),耗时较长;之后增量构建通常在 2~6 分钟。构建日志建议重定向到文件,便于失败后排查:

```bash
scripts/gradle.sh :app:assembleRelease --console=plain > rel-log.txt 2>&1
```

在 CI / 已有 JDK 17 的环境(如 `JAVA_HOME` 已指向 17、或 `actions/setup-java` 装好 JDK)可直接用 wrapper:

```bash
./gradlew :app:assembleRelease
```

## 3. 产物

| 变体 | 路径 | 大小 | 说明 |
|---|---|---|---|
| Debug | `app/build/outputs/apk/debug/app-debug.apk` | ~18 MB | `debuggable`,可直接安装体验 |
| Release | `app/build/outputs/apk/release/app-release.apk` | ~1.4 MB | R8 压缩 + 资源收缩,minSdk 26 |

打包完成后可归集到 `android/dist/`(该目录已在 `.gitignore` 中忽略):

```bash
mkdir -p dist
cp app/build/outputs/apk/debug/app-debug.apk   dist/FreebuffMobile-<版本>-debug.apk
cp app/build/outputs/apk/release/app-release.apk dist/FreebuffMobile-<版本>-release.apk
```

`<版本>` 取 `android/version.properties` 的 `versionName`;`android/dist/` 已在 `.gitignore` 中,与仓库根目录
`dist/update.json`(入库的更新清单)不是一回事。

## 4. 签名:两种模式自动切换

`app/build.gradle.kts` 会根据 `android/keystore.properties` **是否存在**自动选择签名配置:

| `android/keystore.properties` | release 使用的签名 | 能否上架 |
|---|---|---|
| 不存在 | 回退 Android 调试证书 | ❌ 仅本地安装体验 |
| 存在且字段完整 | 你的正式 keystore | ✅ |
| 存在但缺字段 | **构建直接失败并报出缺失字段名** | — |

第三种情况故意做成硬失败:否则你会在「以为用的是正式证书」的情况下拿到一个 debug 签名的包。

无需任何手动改代码,也不用改 CI 脚本 —— 想在本地验证正式签名流程时,只需把 `keystore.properties` 放进来。

### 生成正式 keystore(推荐用脚本)

```bash
cd android
scripts/gen-release-keystore.sh          # 已存在 keystore 时会拒绝覆盖,除非加 --force
```

脚本会交互式询问口令(不落 shell history),生成 `android/freebuff-release.jks`,并写好 `android/keystore.properties`。两者都已在 `.gitignore` 中。

也可以手工执行等价步骤:

```bash
.toolchain/jdk-17.0.20.1+1/bin/keytool.exe -genkeypair -v \
  -keystore freebuff-release.jks -keyalg RSA -keysize 2048 -validity 10000 \
  -alias freebuff

cp keystore.properties.example keystore.properties   # 然后填入真实口令
```

`keystore.properties` 格式(`storeFile` 相对 `app/` 模块解析,所以 `../` 指向 `android/`):

```properties
storeFile=../freebuff-release.jks
storePassword=...
keyAlias=freebuff
keyPassword=...
```

> ⚠️ **务必备份 keystore 与口令。** 丢失后无法再对已发布应用做覆盖升级,只能换包名重新发布。同理,keystore 一旦被替换,签名不同的新包无法覆盖安装旧包(设备上需先卸载)。

### 查看当前实际生效的签名

不需要打包就能确认,几十秒即可:

```bash
scripts/gradle.sh :app:signingReport
```

输出中关注 `Variant: release` 的 `Config:` 行 —— 是 `release` 还是 `debug`,以及 `Store:` 指向哪个 keystore。

## 5. 安装与验证

```bash
# 安装到已连接的设备/模拟器
.toolchain/sdk/platform-tools/adb.exe install -r dist/FreebuffMobile-<版本>-release.apk

# 校验签名与包信息
.toolchain/sdk/build-tools/35.0.0/apksigner.bat verify -v app/build/outputs/apk/release/app-release.apk
.toolchain/sdk/build-tools/35.0.0/aapt2.exe dump badging app/build/outputs/apk/release/app-release.apk
```

预期包信息:`package name='com.freebuff.mobile'`,且 `versionCode`/`versionName` 与 `version.properties` 一致
(当前 `3` / `0.0.2`),minSdk 26 / targetSdk 35。

## 6. R8 混淆注意事项

`app/proguard-rules.pro` 当前**无需额外 keep 规则**:JSON 解析使用 Android 内置 `org.json`,未引入 Retrofit / Gson / kotlinx-serialization 的反射序列化;Compose 与 Hilt 自带 consumer rules 会被自动合并。

若后续引入反射型序列化(如给数据类加 `@Serializable` 或使用 Gson 的 `@SerializedName`),必须补充对应 keep 规则,否则 release 包会在运行期出现字段丢失或类找不到 —— 这类问题只在 release 变体暴露,debug 包不会复现。

同理,任何依赖反射、依赖类名/方法名字符串的代码都需要 keep 规则。发布前建议对 release 包做一次真机冒烟测试。

## 7. 常见问题

| 现象 | 原因与处理 |
|---|---|
| 卡在 `checkDebugAarMetadata` 后失败 | 未使用 JDK 17。请用 `scripts/gradle.sh` 而不是直接跑 `gradle` / `./gradlew` |
| 构建报 `keystore.properties 缺少必要字段` | 该文件存在但不完整。补齐四个字段,或直接删掉它以回退 debug 签名 |
| 想确认 release 到底用了哪个证书 | 跑 `:app:signingReport`,看 `Variant: release` 的 `Config:` / `Store:` 两行 |
| 依赖下载长时间无进展 | 直连 Maven 仓库被阻塞。确认 `settings.gradle.kts` 使用的是阿里云镜像 |
| release 包安装后崩溃、debug 正常 | R8 裁掉了反射所需成员。按第 6 节补充 keep 规则 |
| App 里「检查更新」总是失败 | 默认更新源是 GitHub raw 直链(国内网络可能不可达)。确认网络可访问 `raw.githubusercontent.com`,或把 `freebuff.updateUrl` 指向自建更新源 |
| 发版后 App 仍显示「已是最新」 | 看 release 工作流末尾「Verify published update source」是否失败(清单未回推到 master);重跑该工作流即可 |
| 想在不发版的前提下验证发版链 | 按 0.1 节干跑:同一份工作流,只把清单推到临时分支、不建 Release |
| 想改更新源地址 | 优先用 `freebuff.updateUrl` / `FREEBUFF_UPDATE_URL`(不必改代码);只有默认值要变时才改 `app/build.gradle.kts` 里的 `updateUrl` 常量 |
| `INSTALL_FAILED_UPDATE_INCOMPATIBLE` | 设备上已装同包名但签名不同的应用,先卸载旧包 |
