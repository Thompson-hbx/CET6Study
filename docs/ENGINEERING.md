# 工程化基线

Gradle 依赖沿用已有版本；React/Tauri 原型的新增依赖已获用户授权，额外工具位于用户指定的 `C:\Dev`。

## 模块边界

现有 Kotlin 工程已按全部 UI 功能拆分为 29 个模块；Android 和原有 Compose Desktop 均保留。完整目录、依赖方向及检查规则见 [界面模块说明](UI_MODULES.md)。原有数据/服务位于 `android/core-study`，共享状态位于 `android/ui-state`，通用主题/组件位于 `android/core-ui`；两端宿主只装配功能与管理平台生命周期。

独立的 [React + Tauri Windows 写作原型](../desktop-tauri/README.md) 使用自己的依赖清单和锁文件。它不替换 Android/原桌面端，也未接入其真实同步或 AI。Gradle 插件与依赖版本仍在 `gradle/libs.versions.toml`。

`review-core` 保留两端共用的严格批改解析；数据库写入相关 `ReviewDecisions` 随原业务层迁至 `core-study`，校验与事务语义保留。数据库、备份、同步协议与 applicationId 不变。

## 统一检查

```powershell
.\gradlew.bat --console=plain --no-daemon verifyEngineering
.\tools\verify.ps1 -Python <已有 pypdf 和 reportlab 的 Python 路径>
```

`verifyEngineering` 执行所有模块的 ktlint、detekt、JVM 单测，以及 Android 调试构建、Lint 和测试 APK 构建。模块列表来自 Gradle 子项目，JVM 与 Android 库分别执行对应单测任务。`tools/verify.ps1` 另跑模块边界检查、全部 Python 工具测试、实际 SQL 的 v1–v7→v8 迁移校验和 `git diff --check`。

GitHub Actions 运行同一个 Gradle 入口，并保留迁移、评测工具和 Room schema 差异检查。CI 的 Python 范围仍为评测工具；完整 PDF 工具测试在本地入口执行，需要已有 pypdf/reportlab。远端结果以实际 Actions 运行为准。

共享核心的 detekt baseline 仅从 `app` 转移该代码已有的 20 项记录，没有重新生成全仓库 baseline，也没有把新诊断代码加入 baseline。ktlint 原 baseline 保留；修改旧文件时仍需处理因行号变化而暴露的旧条目。`.kotlin/` 作为构建缓存忽略。

迁移脚本支持格式化后的多行 `execSQL` 和尾逗号，遇到无法提取的 SQL 表达式会失败，不静默跳过。它只在内存 SQLite 上验证，不操作真实学习数据库，也不替代 Android Room 迁移测试。

## 本地崩溃诊断

手机入口：「我的 → 崩溃诊断」；桌面入口：侧栏「崩溃诊断」。用户可查看、选取并手动复制最近一条记录，没有自动上传和第三方账号要求。

- 仅记录 UTC 时间、应用版本、异常类型、代码类/方法和行号；不记录异常消息、线程名、文件路径、作答、请求内容、凭证或设备标识。
- 每份最多 32 KiB、4 层原因、每层 24 帧，固定保留 `last-crash.txt`；通过同目录临时文件替换，支持原子移动时使用原子移动。
- Android 保存于 `noBackupFilesDir/diagnostics/`；Windows 保存于现有数据目录的 `diagnostics/`，不进入学习同步或备份。
- 记录后始终交给原未捕获异常处理器，保留 Android 的终止行为；磁盘/权限失败不能阻止原处理器。JVM 原有 stderr 报错行为保留。
- 仅覆盖到达进程默认处理器的 Java/Kotlin 未捕获异常；被框架或业务捕获的错误、ANR、原生崩溃、系统杀进程和掉电不在范围内。内存耗尽或存储故障时不保证成功落盘。

这是一条离线诊断路径，尚不具备远程告警、崩溃率统计或 Crashlytics 的完整能力。
