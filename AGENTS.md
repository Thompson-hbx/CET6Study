# CET6Study（六级研习室）

个人本地优先的 CET-6 学习 App（Android）：今日 / 练习（写作·翻译·阅读）/ 复盘 / 我的 四个标签。AI 批改与追问支持用户本人的 ChatGPT 登录/Responses SSE、DeepSeek 和自定义 Chat Completions SSE。普通 APK 不含第三方真题。

## 技术栈

- 模块：现有 Kotlin 工程共 29 个 Gradle 模块；`:app`、`:desktop` 是薄宿主，Android 功能位于 `android/`，Compose Desktop 功能位于 `desktop-modules/`，另有 `sync-core`、`sync-server`、`design-tokens`、`review-core`、`diagnostics`。完整边界见 `docs/UI_MODULES.md`。Kotlin 2.0.21、AGP 8.7.3、KSP 2.0.21-1.0.28、Gradle Wrapper 8.9（校验 SHA-256）；插件与依赖版本统一写在 `gradle/libs.versions.toml`。
- compileSdk / targetSdk 35，minSdk 26；Java / Kotlin target 17，JDK 17 或 21（本机 SDK 与 JDK 在 `C:\Dev\Env`）。
- Kotlin UI 使用 Jetpack Compose（Android BOM 2024.12.01、Material3），没有 XML 布局。导航用 Navigation Compose（`android/ui-navigation` 的 `AppRoot.kt`）；依赖注入用 Hilt（宿主 `CET6App`、`di/AppModules.kt`，`StudyServices` 按 ViewModel 作用域创建）。独立 Windows 写作原型位于 `desktop-tauri/`，使用 React + Tauri，使用独立存储，尚未接入真实 AI/同步。
- Room 2.7.2：数据库 `cet6_study.db`，版本以 `data/StudyDatabase.kt` 为准（目前 v8），schema 导出到 `app/schemas/`。OkHttp 4.12，Markwon 4.6。
- 单测 JUnit 4 + org.json（让协议解析在 JVM 上跑）；仪器测试用自定义 `android.app.Instrumentation`（`LocalDataInstrumentation`），不是 AndroidJUnitRunner。

## 目录

- `app/src/main/java/com/example/cet6/`：Android 启动、Activity 生命周期、分享入口与 Hilt 装配；业务在 `android/core-study`，共享视觉在 `android/core-ui`，共享 ViewModel 在 `android/ui-state`，功能在 `android/feature-*`，根导航在 `android/ui-navigation`。
- `app/src/main/assets/`：`academic_words.csv`、`pronunciations.tsv`、`licenses/`；`exam-packs/` 被 Gradle 排除，不进 APK。
- 单元测试随所属模块移动：主要在 `android/core-study/src/test/`；功能状态测试在所属 `android/feature-*`；设备检查仍在 `app/src/androidTest/`，共用检查源码在 `app/src/testShared/`。
- `app/schemas/`：Room 生成的 schema JSON，随代码提交；`app/testdata/`：生成的 FSRS 测试数据。
- `third_party/bee-fsrs/`：内置 FSRS 库，本地补丁写在它的 README，字节由 `SOURCE_HASHES.json` 固定。
- `tools/`：试卷包提取 / 转换 / 下载、发音生成等 Python 脚本；`exam-sample.json` 是原创样本。
- `docs/`：`TODO.md`（进展记录）、`ARCHITECTURE.md`（实现说明）、`EXAM_IMPORT.md`（试卷包格式与导入）、`PRIVATE_BACKUP.md`（私人资料备份）；`archive/` 是历史记录，不作为当前指令。
- 私人资料：`raw-papers/`、`.tools/private-exams/` 和 `.tools/` 下的数据库快照受版权或隐私限制，按 `docs/PRIVATE_BACKUP.md` 强制跟踪；`.tools/` 其余内容（本地 APK、日志、缓存）被忽略。
- `test-evidence/`：手动验证的截图、日志和 JSON，截图文件名带页面和日期。

## 命令（PowerShell，仓库根目录）

- 完整工程检查：`.\gradlew.bat verifyEngineering`（全部 29 个模块的 ktlint、detekt、单测，Android 构建、Lint 与测试包）；`.\tools\verify.ps1` 另跑模块边界、迁移与 Python 检查。详见 `docs/ENGINEERING.md`。Windows 原型检查/构建/运行：`.\tools\writing-prototype.ps1 check|build|run`，详见 `desktop-tauri/README.md`。
- 构建 + 单测 + Lint：`.\gradlew.bat assembleDebug testDebugUnitTest lintDebug`（Lint 的既有警告是依赖有新版本与 targetSdk 提示）。
- 代码规范：`.\gradlew.bat ktlintCheck detekt`；自动格式化 `.\gradlew.bat ktlintFormat`。既有问题记在各模块 `config/ktlint/baseline.xml`、`config/detekt/baseline.xml`，新代码不得新增；规则见根目录 `.editorconfig` 与 `config/detekt/detekt.yml`。
- 其他模块测试：`.\gradlew.bat :desktop:test :sync-core:test :sync-server:test :design-tokens:test :review-core:test :diagnostics:test`。
- 单个测试类：`.\gradlew.bat :core-study:testDebugUnitTest --tests "com.example.cet6.ExamContentTest"`
- 装到手机：`adb -s <serial> install -r app\build\outputs\apk\debug\app-debug.apk`
- 仪器测试：`.\gradlew.bat assembleDebugAndroidTest`，装上 `app-debug.apk` 和 `app-debug-androidTest.apk` 后运行
  `adb -s <serial> shell am instrument --user 0 -w com.example.cet6.test/com.example.cet6.LocalDataInstrumentation`
  默认套件在隔离数据库上跑全部检查和迁移检查。`-e fixture translation|long-feedback|g4-sample|review-layout` 会把样本写进 App 的真实数据（`g4-sample`、`long-feedback` 只能在模拟器上跑）；`-e scenario ui` 在模拟器上跑界面流程。`-e fixture reading-accessibility` 只在模拟器新增原创答题卡练习，手动录入并提交 `CADCBADCBA` 后可用 `-e scenario reading-accessibility-verify` 回读验证，见 `docs/UI_REGRESSION.md`。
- 模拟器草稿跨进程检查：安装普通调试包和测试包后执行 `.\tools\check_draft_process.ps1`；会新增原创草稿并结束所选模拟器的本项目进程，不进入默认隔离套件，详见 `docs/DRAFT_PERSISTENCE.md`。
- 个人包（把已校对题库和原卷打进 APK）：`.\gradlew.bat --offline assembleDebug '-PpersonalExamPack=<json>' '-PpersonalExamPdfs=<dir>'`，见 `docs/EXAM_IMPORT.md`。
- Python 工具：`python tools/prepare_exam_pack.py extract|convert ...`（用法见 `docs/EXAM_IMPORT.md`）；工具测试 `python tools/test_prepare_exam_pack.py`（其中一项需要 `pypdf`）。
- Android CI 在 `.github/workflows/android.yml`；本地入口 `tools/verify.ps1`。CI 执行模块边界检查、`verifyEngineering`、迁移与评测工具检查；远端结果以实际 Actions 为准。React/Tauri 原型尚未加入远端构建任务。

## 约定

- 包结构 `com.example.cet6.{ai,auth,data,ui}`，文件按功能命名；单测 `*Test.kt`，设备检查 `*InstrumentationChecks.kt`。
- Kotlin 官方代码风格（`kotlin.code.style=official`），由 ktlint（`.editorconfig`）与 detekt 检查。注释和 KDoc 用英文；界面文字直接写在 Compose `Text(...)` 里，用中文，没有 `strings.xml`；文档用中文。
- Room 迁移只做加法（新增表、列、索引），不删除或重建旧表；改实体时升级版本、补迁移，并提交新生成的 schema JSON。完整备份为分段 ZIP v1，学习字段沿用 JSON v5，仍能导入旧 JSON v1—v5；见 `docs/BACKUP.md`。
- 提交信息用英文 Conventional Commits，例如 `feat(reading): ...`。
- 文本 UTF-8；`third_party/bee-fsrs/src/` 强制 LF；私人资料在 `.gitattributes` 中标为 `-text`，不要改动它们的字节。

## 注意

- 手机上的 `cet6_study.db` 是真实学习记录，卸载或 `pm clear` 会清掉它；覆盖安装用 `install -r`。
- 第三方真题只作个人备份（远端是私有仓库）：不要移动、重新编码或打进普通 APK；不要 `git add -f` 其他 `.tools/` 内容（约 1.2 GB）。`.kotlin/` 已忽略，属于构建缓存，不要提交。
- 登录凭证由 `auth/SecureSessionStore.kt` 加密保存，不要输出或记录 token；AI 请求用的是用户本人的 ChatGPT 账号。
- 当前状态与未验证项统一见 `docs/STATUS.md`；历史统计、校对记录和运行证据按其日期理解。

## 协作与授权说明

本文件只约束**本仓库的日常协作方式**，不取代用户最新指令、平台安全规则或法律要求。以当前 `master` 源码及可复现的运行证据判断真实实现状态。产品历史见 `docs/product/G3_BLUEPRINT.md`；当前事项见 `docs/TODO.md`；两者都不是永久的功能禁令或阶段验收门槛。

## 自主实施，而非等待审批

- GPT-6 Astra / 其他开发 Agent 可按明确目标自主阅读代码、设计方案、重构、开发、修复、构建、运行必要测试并迭代，普通非破坏性改动无需逐阶段征求批准。
- 不再沿用 G3/G4 的“下一项指定任务”、Commander Gate、七天验收、固定测试矩阵、强制多 Agent 审查、强制先写长报告等历史流程；验证力度随变更风险调整。
- 不设置对词汇、FSRS、TTS、听力、UI、架构优化或新能力的永久禁止。优先级由实际学习价值、当前需求、技术可行性与维护成本决定；既有决策可以在证据充分时更新。
- 不再把“不使用 Notion”“只能维护一个任务文件”等历史项目管理选择视为永久禁令。接入外部服务、修改外部工作区，仍须有相应授权并遵守服务访问权限。
- 允许在功能分支提交、推送并提交 PR；不得无意覆盖用户工作或强推受保护分支。用户明确要求直接合并/发布时，再按可用权限与风险执行。
- 先确认仓库状态及实际功能，再选择最短可验证路径。可自行决定是否需要计划、测试、截图、评审或文档；不要因为过期流程阻碍有效实现。

## 保留的实质性边界

- **数据保全**：不得擅自清除 Room 学习记录、个人题库、训练历史、备份、OAuth 会话；涉及 schema、导入格式、备份或 applicationId 的改动，确保可迁移与可回退并适度验证。
- **凭证与隐私**：不输出 Token、Cookie、Keystore 秘密或私人数据；不绕过认证、安全防护或第三方授权；未经许可不上传/发布个人原卷及有版权限制的材料。
- **来源真实性**：真题、答案、证据句和已校对状态必须可追溯；AI 诊断、测试记录和构建结论不得伪装成已验证事实。
- **既有能力**：可以有依据地调整 OAuth、Responses、Room、Compose 设计，但不得在无迁移与回归依据时破坏既有可用路径。
- **操作审慎**：破坏性数据操作、跨项目更改、生产发布及高风险权限调整须获得明确授权。保留未提交现场，不强制重置或覆盖。

## 真机调试：已获限定授权

- 用户已授权本项目 `com.example.cet6` 后续调试 APK 的构建、`adb install -r` 覆盖安装、启动和非破坏性 UI 检查；ADB 已授权且设备在线时，无需重复询问普通安装确认。
- 先检查 `adb devices -l` 并选定实体手机 serial，确认 applicationId 和安装目标。不得擅自卸载、`pm clear`、降级、改签名、改 applicationId 或触碰其他应用。
- 点击“允许本次 USB 安装 / 继续安装”等本项目正常确认前，先获取**新鲜截图或 UI 层次**确定弹窗内容；不得盲点坐标。首次 USB 调试授权及系统要求的密码/指纹仍由用户亲自完成。
- 不关闭系统安全防护、不操作无关权限，不自动消费真实试卷或产生额度开销的 AI 请求。调试副作用必须最小化。
- 按实际变更选择构建、覆盖安装、启动、截图和回归检查；记录确实执行的步骤及未验证项。既有 `test-evidence/` 只作证据，不作硬性流程门槛。

**交付原则：**以完成可用功能及保护用户数据为优先。汇报修改内容、证据、局限和下一步；不宣称未运行过的测试或未完成的远端操作。
