# CET6Study 第一阶段构建报告

日期：2026-10-16（Asia/Shanghai）。结论：**工程构建、模拟器基础测试及 vivo 真机“登录 → 模型目录 → Responses 完整回答”链路已通过，刷新与异常场景仍有待测项。**

## 交付物

- Debug APK：`app/build/outputs/apk/debug/app-debug.apk`
- 大小：16,572,700 字节（约 15.80 MiB）
- SHA-256：`8178DA0BDCFE021514D9D26C9A05A687942C5FAF7FDD4C1639A53F3AD074FCBE`
- 包名：`com.example.cet6`；版本：`0.1.0` / versionCode `1`
- minSdk：26；compileSdk / targetSdk：35；入口：`com.example.cet6.MainActivity`
- 完整构建日志：[BUILD_LOG.txt](BUILD_LOG.txt)
- 认证审阅：[AUTH_REVIEW.md](AUTH_REVIEW.md)
- 真机步骤：[DEVICE_TEST.md](DEVICE_TEST.md)

当前 Debug 包已在 vivo V2359A 实体手机安装、启动、恢复登录并完成一次真实推理；先前版本已完成模拟器基础测试。不是生产发布包，详细实测结果见 [PHONE_TEST.md](PHONE_TEST.md)。

## 源码与架构审阅

起始目录仅有源码 ZIP，不是 Git 仓库。解压至 `CET6Study/` 后保留原压缩包，以原始内容作差异对照；没有执行提交、分支操作、上传或部署。

已阅读压缩包全部 7 个生产 Kotlin 文件、原有测试、Manifest、资源、Gradle 配置及 README/迭代计划。保留原 Compose → StudyViewModel → Room DAO / OAuth / Responses 客户端结构，没有重写学习模块或改变数据库表结构。对于当前小型验证工程，直接由 ViewModel 协调客户端和 DAO 可以继续复用；后续功能扩大时再讨论 repository 分层。

依赖保持既有版本：AGP 8.7.3、Kotlin/Compose 插件 2.0.21、KSP 2.0.21-1.0.28、Compose BOM 2024.12.01、Room 2.7.2、OkHttp 4.12.0、JUnit 4.13.2。没有新增第三方业务或测试依赖。原有 `material-icons-extended` 尚无源码引用，本阶段保留，不做无关清理。

## 改动

- 补齐 Gradle 8.9 Wrapper 的脚本、JAR 和官方发行包 SHA-256 校验。
- 补齐 ViewModel 的 Compose 状态委托导入；限制重复请求、无权限和空消息发送，刷新失败后同步真实本地会话状态。
- 连接页支持编辑消息、选择服务端模型、显示流式回答；将套餐 scope 与实际调用成功分开说明。
- 修复 SSE 分帧的空事件污染、多行数据空白处理、EOF 记录处理；无效 JSON 不再静默吞掉，明确处理失败/不完整事件和拒绝文本。
- HTTP 错误显示状态、响应结构类型、经限制的错误码/参数和 request ID；不回显任意服务端正文，不自动重发推理请求。
- 按已批准方案修复注册映射保留、退出撤销、终止性刷新错误、轮换令牌校验、回调容错和 OIDC azp 检查；认证 HTTP 禁止跟随重定向。
- 明确排除 Android 12+ 凭证与 host ID 的云备份/设备迁移。
- 配置 Room schema 导出并保存 `app/schemas/com.example.cet6.data.StudyDatabase/1.json`，不改变 version 1 数据模型。
- 模拟器发现标题覆盖系统状态栏，补充顶部状态栏安全边距；重新构建、覆盖安装及截图确认重叠已消失。
- 真机响应诊断发现 HTTP 200、缺失/不可解析的 MIME 类型及实际 SSE 正文，增加只对缺失 Content-Type 的严格流起始事件校验；仍要求 `response.completed`。修复后完成真实请求，并新增 4 项回归测试。

## 构建环境与验证结果

复用现有 Windows / PowerShell 环境：

- JDK：`C:\Dev\Env\Java\jdk-21`，21.0.12；Java/Kotlin 字节码目标仍为 17。
- SDK：`C:\Dev\Env\Android\Sdk`。
- 原有 Gradle 8.14.3 用于首次环境探测；正式构建使用项目 Wrapper 的 Gradle 8.9。
- 经用户批准补齐 SDK Platform 35、AGP 默认需要的 Build Tools 34.0.0 和项目已声明依赖；Gradle 8.9 下载至项目 `.tools/`，没有修改系统环境变量或全局代理配置。
- 网络下载卡住时复用已安装且与 Maven 校验一致的 Kotlin 编译器，并校验官方仓库下载文件后填充 Gradle 缓存。

首次离线构建因缺少 AGP 8.7.3 失败，未进入源码编译。补齐依赖并完成修复后的正式命令：

```powershell
.\gradlew.bat --offline assembleDebug testDebugUnitTest lintDebug --console=plain
```

首次完整验证为 `BUILD SUCCESSFUL in 1m 16s`。修复模拟器顶部布局问题后再次通过；最终流兼容修复版执行相同命令，结果为 `BUILD SUCCESSFUL in 12s`，53 个任务，17 个执行、36 个使用已有产物。当前 APK 已重新检查签名与对齐。

| 验证 | 结果与证据 |
| --- | --- |
| Wrapper | `gradlew.bat --version` 正常返回 Gradle 8.9 / JDK 21 |
| APK 构建 | `assembleDebug` 成功 |
| JVM 单元测试 | 26/26 通过，0 失败、0 错误、0 跳过 |
| OAuth 协议/策略测试 | 11 项：回调解析、state/重复参数、无关请求继续等待、真实本机 socket、超时、刷新错误及 token 字段 |
| SSE 分帧测试 | 8 项：多行/CRLF、EOF、注释、事件隔离、空白、空数据、连续帧、大小限制 |
| 缺失响应头回归 | 4 项：有效 Responses 创建事件、无关 SSE/伪完成事件、无效对象、标准 SSE 路径 |
| 原有复习算法测试 | 3 项，全部通过 |
| Android Lint | 0 errors、7 warnings，未抑制检查 |
| 签名 | `apksigner verify --verbose` 通过，APK v2 签名有效 |
| 对齐 | `zipalign -c -P 16 4` 通过，退出码 0 |
| Manifest/资源 | aapt2 确认包名、入口及 SDK 版本；构建完成资源与 Manifest 处理 |
| 差异审阅 | 对照原 ZIP 阅读改动，`git diff --no-index --check` 未输出空白错误诊断；该目录没有 Git 历史 |

测试 HTML 位于 `app/build/reports/tests/testDebugUnitTest/index.html`，JUnit XML 位于 `app/build/test-results/testDebugUnitTest/`；Lint 报告位于 `app/build/reports/lint-results-debug.html` 和 `.xml` / `.txt`。

7 个 Lint 警告为：targetSdk 不是工具认为的最新版本、5 个 Activity/Lifecycle 依赖更新提示、缺少正式应用图标。为保留第一阶段范围，没有批量升级依赖或更改 targetSdk。另有构建工具提示多个 Kotlin daemon 会话，来自构建重试，不是编译错误。

## Android 模拟器测试补充

经用户授权使用现有 Android Studio 和 SDK 启动 `Pixel_7_API_36`，设备为 Android 16 / API 36、`emulator-5554`。该轮仅操作已有模拟器，后续实体手机验证见 PHONE_TEST.md。

已验证：

- `adb install -r` 安装成功，`am start -W` 冷启动返回 `Status: ok`；更新后的冷启动约 1.66 秒。
- 连接测试页显示，未登录时刷新模型、模型选择和发送请求按钮处于禁用状态。
- 四个现有页面可以切换；单词页读取 8 条样例，显示释义并记录复习后待复习变为 7 条。
- 强制结束应用、冷启动及覆盖安装后，待复习仍为 7 条，说明该次 Room 更新已持久化。
- 阅读示例选择 B 后本地判题正确，保存 1 条记录，覆盖安装后仍可读取。
- 作文页显示题目、输入框、字数和批改入口；未进行真实联网批改。
- 登录按钮调用系统 Chrome，App 返回后显示等待授权。Chrome 当前停在首次使用设置，尚未进入 OpenAI 授权页；中止该次待授权流程后应用可以再次冷启动。
- 测试期间所查应用进程日志未匹配到 FATAL EXCEPTION、SQLiteException 或 ANR；这不是全面稳定性测试。

Android Studio 已打开项目，但 IDE 导入/同步被 `Proxy Authentication: 127.0.0.1` 窗口阻塞，尚未确认 IDE Sync 成功。没有填写、读取或保存代理密码，也没有修改 IDE 全局代理设置。命令行构建和模拟器运行结果不依赖该窗口。

证据：修复前后截图见 `test-evidence/emulator-connection.png` 和 `test-evidence/emulator-connection-fixed.png`；简要记录见 `test-evidence/emulator-smoke.txt`。本次只对模拟器内样例学习数据进行了测试操作，未清空数据库。

## 协议核对与尚未实测

已在线核对官方 Sign in with ChatGPT 的注册、会话、token、模型与推理、预览限制文档，并读取官方 OIDC discovery。现有套餐路由使用 `models[]` / `slug` 与 `POST /v1/responses`，请求要求的 `input` 数组、`store=false`、`stream=true` 均已检查。详细依据见认证审阅报告。

**随后已在 vivo 真机完成真实账号授权，加载 7 个模型并用 GPT-6.1-Sol 成功完成一次 Responses 套餐路由请求，收到 `response.completed` 和回答“六级研习室 API 测试成功。”** 覆盖安装及冷启动后仍能恢复登录。未读取或创建 API Key，未记录邮箱或令牌。此次成功不代表其他账号、模型或后续用量必然可用。

仍需验证：

1. 实体手机完整页面与 Room 验证（vivo V2359A 安装、冷启动和连接页检查已通过，模拟器基础测试已通过）。
2. 其他浏览器、系统版本及账号；本次设备的浏览器回调与 OIDC 验证已通过。
3. 其他模型、较长输出和用量限制错误；本次模型目录及短消息完整响应已通过。
4. 真实 token 到期刷新轮换、远端撤销及服务器断开授权；本次 Keystore 保存与冷启动恢复已通过。
5. 断网、超时、回后台、旋转和系统回收进程后的行为；系统备份与设备迁移排除规则。

明确限制：单账号、每次消息为独立请求、无 Android 官方 SDK 兼容承诺、浏览器回调后需手动切回 App。首次注册换码失败时在同一进程保留 issued client ID；若此时进程被回收，未验证的注册暂不持久化。保留先前验证的 ID token 仅作后续授权提示，不使用刷新返回的未验证 ID token 更新身份。

若官方套餐路由拒绝该应用，不绕过协议；应申请官方支持，或另行批准自有后端保管 API Key 的标准 Responses API 替代方案。不会把开发者 API Key 放入 Android APK。

此为 P0 当时的阶段边界。后续 Goal G1.0 明确授权在保留认证链路的前提下按 P1→P2→P3 推进，当前状态以 PROGRESS.md 为准；未测认证场景继续保留，不宣称 P0 完整验收。
