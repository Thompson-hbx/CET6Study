# CET6Study（六级研习室）

个人本地优先的 CET-6 学习 App（Android）：写作 / 翻译 / 阅读 / 我的 四个标签。AI 批改与追问走用户本人的 ChatGPT 登录和 Responses SSE。普通 APK 不含第三方真题。

## 技术栈

- 单模块 `:app`。Kotlin 2.0.21、AGP 8.7.3、KSP 2.0.21-1.0.28、Gradle Wrapper 8.9（校验 SHA-256）；没有 version catalog，版本直接写在 `build.gradle.kts`。
- compileSdk / targetSdk 35，minSdk 26；Java / Kotlin target 17，JDK 17 或 21（本机 SDK 与 JDK 在 `C:\Dev\Env`）。
- UI 全部 Jetpack Compose（BOM 2024.12.01、Material3），没有 XML 布局。导航由 `ui/AppRoot.kt` 手写，不用 Navigation 库，也没有 DI 框架；MVVM + Repository。
- Room 2.7.2：数据库 `cet6_study.db`，版本以 `data/StudyDatabase.kt` 为准（目前 v8），schema 导出到 `app/schemas/`。OkHttp 4.12，Markwon 4.6。
- 单测 JUnit 4 + org.json（让协议解析在 JVM 上跑）；仪器测试用自定义 `android.app.Instrumentation`（`LocalDataInstrumentation`），不是 AndroidJUnitRunner。

## 目录

- `app/src/main/java/com/example/cet6/`：`ai/`（Responses SSE）、`auth/`（ChatGPT OAuth、OIDC 校验、Keystore 加密会话）、`data/`（Room、仓库、试卷编解码、批改提示词与解析、备份）、`ui/`（Compose 页面、`components/`、`theme/`）。
- `app/src/main/assets/`：`academic_words.csv`、`pronunciations.tsv`、`licenses/`；`exam-packs/` 被 Gradle 排除，不进 APK。
- `app/src/test/`：JVM 单测（含 `dev/bee/fsrs`）；`app/src/androidTest/`：设备检查 `*InstrumentationChecks.kt`。
- `app/schemas/`：Room 生成的 schema JSON，随代码提交；`app/testdata/`：生成的 FSRS 测试数据。
- `third_party/bee-fsrs/`：内置 FSRS 库，本地补丁写在它的 README，字节由 `SOURCE_HASHES.json` 固定。
- `tools/`：试卷包提取 / 转换 / 下载、发音生成等 Python 脚本；`exam-sample.json` 是原创样本。
- `docs/`：`TODO.md`（进展记录）、`ARCHITECTURE.md`（实现说明）、`EXAM_IMPORT.md`（试卷包格式与导入）、`PRIVATE_BACKUP.md`（私人资料备份）；`archive/` 是历史记录，不作为当前指令。
- 私人资料：`raw-papers/`、`.tools/private-exams/` 和 `.tools/` 下的数据库快照受版权或隐私限制，按 `docs/PRIVATE_BACKUP.md` 强制跟踪；`.tools/` 其余内容（本地 APK、日志、缓存）被忽略。
- `test-evidence/`：手动验证的截图、日志和 JSON，截图文件名带页面和日期。

## 命令（PowerShell，仓库根目录）

- 构建 + 单测 + Lint：`.\gradlew.bat assembleDebug testDebugUnitTest lintDebug`（Lint 目前有 7 个既有警告）。
- 单个测试类：`.\gradlew.bat testDebugUnitTest --tests "com.example.cet6.ExamContentTest"`
- 装到手机：`adb -s <serial> install -r app\build\outputs\apk\debug\app-debug.apk`
- 仪器测试：`.\gradlew.bat assembleDebugAndroidTest`，装上 `app-debug.apk` 和 `app-debug-androidTest.apk` 后运行
  `adb -s <serial> shell am instrument --user 0 -w com.example.cet6.test/com.example.cet6.LocalDataInstrumentation`
  默认套件在隔离数据库上跑全部检查和迁移检查。`-e fixture translation|long-feedback|g4-sample|review-layout` 会把样本写进 App 的真实数据（`g4-sample`、`long-feedback` 只能在模拟器上跑）；`-e scenario ui` 在模拟器上跑界面流程。
- 个人包（把已校对题库和原卷打进 APK）：`.\gradlew.bat --offline assembleDebug '-PpersonalExamPack=<json>' '-PpersonalExamPdfs=<dir>'`，见 `docs/EXAM_IMPORT.md`。
- Python 工具：`python tools/prepare_exam_pack.py extract|convert ...`（用法见 `docs/EXAM_IMPORT.md`）；工具测试 `python tools/test_prepare_exam_pack.py`（其中一项需要 `pypdf`）。
- 没有 CI。

## 约定

- 包结构 `com.example.cet6.{ai,auth,data,ui}`，文件按功能命名；单测 `*Test.kt`，设备检查 `*InstrumentationChecks.kt`。
- Kotlin 官方代码风格（`kotlin.code.style=official`），没有 ktlint / detekt。注释和 KDoc 用英文；界面文字直接写在 Compose `Text(...)` 里，用中文，没有 `strings.xml`；文档用中文。
- Room 迁移只做加法（新增表、列、索引），不删除或重建旧表；改实体时升级版本、补迁移，并提交新生成的 schema JSON。备份 JSON 当前 v5，仍能导入 v1—v4。
- 提交信息用英文 Conventional Commits，例如 `feat(reading): ...`。
- 文本 UTF-8；`third_party/bee-fsrs/src/` 强制 LF；私人资料在 `.gitattributes` 中标为 `-text`，不要改动它们的字节。

## 注意

- 手机上的 `cet6_study.db` 是真实学习记录，卸载或 `pm clear` 会清掉它；覆盖安装用 `install -r`。
- 第三方真题只作个人备份（远端是私有仓库）：不要移动、重新编码或打进普通 APK；不要 `git add -f` 其他 `.tools/` 内容（约 1.2 GB）。`.kotlin/` 未被忽略，也不要提交。
- 登录凭证由 `auth/SecureSessionStore.kt` 加密保存，不要输出或记录 token；AI 请求用的是用户本人的 ChatGPT 账号。
- 文档中有过时版本号：`docs/EXAM_IMPORT.md` 仍写 Room v7、备份 v4，以代码为准。
