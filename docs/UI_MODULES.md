# 界面模块与 Windows 写作原型

## 当前结构

现有 Kotlin 工程共 29 个 Gradle 模块。`app` 和 `desktop` 只负责启动、窗口/Activity 生命周期、分享入口和依赖装配；功能界面不再留在宿主源码中。类与包名保留，Room 仍为 v8，schema 输出仍在 `app/schemas/`，学习备份与同步协议不变。

| 路径 / 模块 | 职责 |
| --- | --- |
| `android/core-study` | 原有 ai/auth/data/domain、Room、恢复、同步编排和 FSRS；不依赖 UI |
| `android/core-ui` | 主题、字体、通用组件、通用屏幕框架和平台文档选择器；不依赖业务或 ViewModel |
| `android/ui-state` | 跨功能共享的 StudyViewModel、Compose 状态适配与对话/练习状态 |
| `android/ui-navigation` | 根标签、NavHost 和功能装配 |
| `android/feature-today` | 今日页与周统计 |
| `android/feature-practice` | 写译阅读目录、练习历史与专项切换 |
| `android/feature-writing` | 写作工作台与构思 |
| `android/feature-translation` | 翻译工作台与逐句辅助 |
| `android/feature-reading` | 阅读工作台、答题卡、自选文章、定位与错因面板 |
| `android/feature-review` | 复盘首页、错因回顾与学习趋势 |
| `android/feature-settings` | 我的首页、AI 连接、备份恢复、题库管理、设备同步、风格与诊断；拥有专用 ViewModel |
| `android/feature-legacy` | 旧版首页、单词复习、词库导入/许可和旧作文记录 |
| `android/feature-papers` | 离线原卷 PDF 查看 |
| `android/feature-workspace-common` | 写译阅读共用的编辑器、批注、版本列表、计时与对话控件 |
| `desktop-modules/desktop-core` | 现有桌面 SQLite、加密凭证、同步与状态模型 |
| `desktop-modules/desktop-ui-common` | 桌面主题、图标、按钮及共用批注组件 |
| `desktop-modules/desktop-ui-navigation` | 桌面侧栏、功能装配与启动错误展示 |
| `desktop-modules/desktop-feature-today` | 桌面今日页 |
| `desktop-modules/desktop-feature-review` | 桌面复盘页 |
| `desktop-modules/desktop-feature-study` | 桌面写译阅读编辑、批改查看与历史工作台 |
| `desktop-modules/desktop-feature-words` | 桌面生词列表 |
| `desktop-modules/desktop-feature-settings` | 桌面设备同步、冲突选择与本地诊断 |
| 根目录五个共享/服务模块 | `sync-core`、`sync-server`、`design-tokens`、`review-core`、`diagnostics` |

公共组件向下依赖，功能模块通过公共状态和服务工作；宿主负责组合功能。旧版页面组合了现有备份和复盘控件，因此显式依赖相应功能模块，没有从公共层反向引用功能或宿主。

Kotlin 包名保留以避免不必要的调用方和序列化改动。共享资源的 R 类改由 `core-ui` 的独立 namespace 提供，桌面打包也从同一字体目录读取。已有单元测试跟随其所属层移动，仍读取 `app/testdata`；设备仪器测试留在宿主。为保留原有跨模块设备检查，少量原 internal 测试接缝公开，认证校验、Keystore、恢复与流解析逻辑不变。

## 检查

```powershell
python tools/check_module_boundaries.py
.\gradlew.bat --console=plain --no-daemon verifyEngineering
.\tools\verify.ps1 -Python <已有 pypdf 和 reportlab 的 Python 路径>
```

`check_module_boundaries.py` 读取实际 Gradle 项目及依赖，拒绝依赖环、功能反向依赖宿主、宿主残留功能 Composable、公共视觉层依赖业务层，以及同包同名 Kotlin 文件导致的 JVM facade 冲突；已接入本地验证入口和原 CI。

`verifyEngineering` 对全部模块跑 ktlint、detekt 和各自的单元测试；Android 库使用 `testDebugUnitTest`，JVM 模块使用 `test`，并构建 Android APK、测试 APK 和运行 Android Lint。不是把所有 Android 库同时构建 debug/release 全变体。

旧 detekt 条目按原签名及可追溯来源迁移，没有将新原型代码加入 baseline。迁移中清理了少量失效的行内注释并拆行旧测试字符串，测试内容及断言保留。

## React + Tauri 原型

独立工程在 `desktop-tauri/`。交付为 Windows 桌面 EXE；React 在进程管理的 WebView2 内渲染，Rust 负责受限本地文件命令。没有实现或部署浏览器产品，也没有替换原有 Compose Desktop。

功能和运行说明见 [原型 README](../desktop-tauri/README.md)。Android 仍使用上表中的 Compose 功能模块，现有手机/桌面同步协议保持兼容；本原型尚未接入实际同步或 AI 服务。

本轮实际运行与交付哈希见 [验证证据](../test-evidence/ui-modules-tauri-20261101.json)。
