# 朱批（CET6Study）协作说明

个人本地优先的六级作文 / 翻译练习程序，只有 Windows 版（WPF）。用法与功能见 `README.md`。

## 技术栈

- .NET 10（`windows/global.json`：SDK 10.0.403，`rollForward: latestFeature`），C#，`Nullable` 与 `TreatWarningsAsErrors` 在 `windows/Directory.Build.props` 统一开启。
- `windows/src/Zhupi.Core`（`net10.0`）：`Notebook`（SQLite，`Microsoft.Data.Sqlite`）、`SelfCheck`、`Teacher/`（老师接口、批改与追问协议、批注定位）、`ChatGpt/`（Sign in with ChatGPT、Responses 调用）。
- `windows/src/Zhupi.App`（`net10.0-windows`，WPF）：`CommunityToolkit.Mvvm`；DPAPI（`ProtectedData`，Windows 桌面运行时自带）保存 API Key 和 ChatGPT 凭证。
- `windows/tests/Zhupi.Core.Tests`：xUnit。网络请求一律用假 `HttpMessageHandler`；本机回调测试只监听 127.0.0.1。
- 新增 NuGet 依赖前先问用户。

## 命令（PowerShell，在 `windows/` 下）

- 构建：`dotnet build Zhupi.slnx`
- 测试：`dotnet test Zhupi.slnx`
- 格式检查：`dotnet format Zhupi.slnx --verify-no-changes --severity warn`（规则在仓库根目录 `.editorconfig`）
- 运行：`dotnet run --project src/Zhupi.App`；`--data-dir=<目录>` 使用隔离数据。
- 用户正在运行程序时，`bin/` 里的文件被占用；检查编译可加 `-p:BaseOutputPath=<临时目录>\`，不要关掉用户的窗口，除非用户同意。

## 约定

- 本子数据库只做加法迁移：新增表、列、索引并提升 `Notebook.SchemaVersion`，不删除或改写旧表；更新版本的数据库拒绝打开。
- 交了的页不可改；改动只通过「重写一稿」产生新页。批改、采纳状态、问答都挂在原页的批改上。
- 老师的回复必须先通过 `MarkingProtocol.Read` 等严格校验才保存；不合格式就拒绝，不猜。只有完整回复才落盘，叫停或中断不留半截记录。
- 每次 AI 请求都可能花用户的钱：不自动重试、不自动降档、不在测试或检查中发真实请求。
- 界面文字写在 XAML / ViewModel 里，用中文；代码注释用英文，只写「为什么」；文档用中文。
- 提交信息用英文 Conventional Commits，例如 `feat(windows): ...`；在功能分支上提交，合并、推送前先问用户。
- 本程序是重新设计的：不要参照或移植旧手机端 / Kotlin 代码的逻辑（它们已从当前代码删除，只在 Git 历史中）。旧数据只通过 `OldNotesImport` 读取。

## 界面检查

- 不要在用户的桌面上弹出测试窗口、抢焦点或截取屏幕。检查界面时把窗口放到屏幕外（`Left/Top = -32000`、`ShowActivated = false`、`ShowInTaskbar = false`），用 `RenderTargetBitmap` 渲染成图片。
- 设计稿在 Artifact 画布「朱批 Windows 界面设计」的「A 作业本」页。

## 数据与凭证

- `%LOCALAPPDATA%\Zhupi\` 下的 `study.db` 是真实学习记录；不得擅自删除、覆盖或在上面做试验。
- 不读取、输出或记录 API Key、ChatGPT 令牌、`teacher.key`、`chatgpt.session`、`chatgpt.hint` 的内容；只可确认文件是否存在。
- 私人资料（`raw-papers/`、`.tools/private-exams/`、`.tools/` 下的数据库快照）受版权或隐私限制，按 `docs/PRIVATE_BACKUP.md` 保留，不要移动、重新编码或改动字节；不要 `git add -f` 其他 `.tools/` 内容。

## 授权边界

- 可按明确目标自主阅读、设计、开发、测试和在功能分支提交；普通非破坏性改动无需逐步审批。
- 破坏性数据操作、跨项目更改、推送与合并、改 CI、发布、向外部服务发送内容，须先获得用户明确同意。
- 汇报修改内容、验证证据、局限和下一步；不宣称未运行过的测试或未完成的远端操作。
