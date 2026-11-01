# 个人桌面端与设备同步

本版提供 Windows 桌面端、个人同步服务和 Android「我的 → 设备同步」。目标是手机写草稿、电脑续写、手机继续查看；不是多人题库服务。

## 当前范围

- Windows：离线新建/编辑作文、翻译、自定义阅读，保存/提交和创建修改稿，查看手机已有完整批改、追问、错因与生词。修改立即写入本机 SQLite。
- 同步：练习草稿与提交记录、完整批改与对话、旧作文/阅读历史、每日计划、单词及其复习日志/FSRS 状态。同一词的进度与日志作为一条记录处理。
- 自动同步：开启后，应用进程运行期间每 30 秒检查一次，亦可立即同步。不是后台推送；关掉应用后不承诺继续同步。
- 首版桌面端不发起 AI 请求，批改和复习评分继续在手机完成；未作答题库与 PDF、AI 凭证、设备计时、页面位置和界面偏好不在本版同步范围内。已打开练习保存的材料会随该练习同步。
- 手机 Room 仍为 v8，学习备份仍为 JSON v5，旧备份和现有记录不需要迁移。同步容量上限为 32 MiB，与旧 JSON 备份一致；更大记录暂不支持同步，仍可使用原有分段完整备份。

## 构建与运行

沿用项目 Gradle Wrapper 和现有 Kotlin 2.0.21。新增模块使用 JDK 21（`sync-core` 输出 JVM 17 字节码供 Android 使用）；不升级 Android 编译插件或数据库依赖。

```powershell
.\gradlew.bat --console=plain --no-daemon :sync-core:test :sync-server:test :desktop:test
.\gradlew.bat --console=plain --no-daemon :desktop:run
.\gradlew.bat --console=plain --no-daemon :desktop:packagePortable :sync-server:installDist
.\gradlew.bat --console=plain --no-daemon :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
```

桌面免安装目录在 `desktop/build/portable/Xiye/`，需保留整个目录及其自带运行时，双击 `Xiye.exe`。该任务使用 JDK 自带的 `jpackage --type app-image`，不依赖 WiX；Compose 1.7 的默认 `createDistributable` 在 Windows 也会要求 WiX，因此这里另设免安装打包入口。MSI 的 `:desktop:packageMsi` 另需 WiX。

同步服务启动脚本在 `sync-server/build/install/sync-server/bin/`，需 JDK 21。用户在自己的终端/服务管理器中配置以下环境变量；不要将真实密钥写入代码、命令示例、日志或 Git：

| 变量 | 含义 |
| --- | --- |
| `XIYE_SYNC_SECRET` | 必填，自己生成的 32–256 位随机英文字符；两端填写同一配对密钥 |
| `XIYE_SYNC_PORT` | 本机监听端口，默认 8787 |
| `XIYE_SYNC_DATABASE` | 数据库路径，默认当前目录的 `data/xiye-sync.db`；正式运行应指定绝对路径 |

服务只监听 `127.0.0.1`，通过本机 HTTPS 反向代理提供给客户端。`/health` 只返回存活状态；`/v1/study` 的读取和写入都验证配对密钥。客户端只接受有有效证书的 HTTPS，禁止重定向及明文传送密钥。反向代理要允许至少 34 MiB 请求体，并与后端一起关闭包含 Authorization/正文的访问日志。

暂时没有购买域名不影响构建和本地协议验证；要让真实手机通过公网连接，仍需选择可访问的服务器和可验证的 HTTPS 地址。本轮不创建云资源、不购买域名、不发布到公网。反向代理、证书和服务器操作系统的具体配置，待实际部署环境确定后再定。

## 两端配置

1. 在自己的服务器配置密钥并启动服务和 HTTPS 代理。
2. Android「我的 → 设备同步」填写 HTTPS 基础地址和配对密钥，点「加密保存并同步」。
3. Windows「设备同步」填写相同地址和密钥。密钥由 Android Keystore / Windows DPAPI 分别加密保存，不进入学习备份或同步内容。
4. 手机保存一份原创作文草稿并立即同步；电脑同步后续写，再在手机立即同步并核对内容。

默认是一个私人学习空间，没有公开注册、短信登录或多人隔离。密钥持有人可读写该空间，勿把密钥分发给其他人。更换密钥时由用户在服务器和两端分别配置。

## 冲突、断网与恢复

- 用服务器版本和本机上次同步基线做三方合并，不依赖设备系统时间判断覆盖顺序。
- 上传仅包含变化记录，并以服务器版本做条件写入；下载以 ETag 避免未变化时重复传输，服务器有变化时下载完整学习快照。
- 两端修改同一记录时停止自动覆盖，在「设备同步」显示两份内容并选择保留的版本。选择只针对当时看到的内容，新的并发修改会重新提示。单词的追加日志始终保留。
- 同步期间本机继续编辑时，应用在本地事务中重新核对快照；不匹配便保留新输入，留待下一次同步。该极端情形下可能再次提示人工解决冲突。
- 服务器事务保存当前记录和历次变化；本机采用外部内容前保存两端快照，冲突发生时也先保存副本。断网、HTTP 错误、配置损坏或写盘失败不会清空原学习记录。
- 原有备份恢复仍使用原流程。同步不传播删除；不要把重装/空白数据库当作清除服务器的方法。
- 重新保存服务器地址和密钥相当于重新配对，会建立新的同步基线，保留之前的同步历史；首次配对遇到同标识不同内容仍需人工选择。

Windows 数据默认在 `%LOCALAPPDATA%/Xiye/`：`study.db` 是学习记录，`connection.protected` 是 DPAPI 密文，`sync/` 下是同步基线和 `versions/` 副本。测试可通过 `Xiye.exe --data-dir=<项目内测试目录>` 隔离，切勿指向手机数据库或覆盖已有备份。

## 桌面工作区

按选定的第 3 版设计，桌面采用独立侧栏、顶部工具栏及原文/作答双栏。两栏可以分别滚动；「批改面板」默认收起，需要时展开已有批改与追问。「练习记录」支持按标题、材料和作答搜索，并筛选草稿或已提交记录。已提交内容只读，通过「修改一版」保留原记录并继续编辑。

在编辑区使用 `Ctrl+N` 新建、`Ctrl+F` 打开练习记录、`Ctrl+Enter` 提交（已提交时创建修改稿）。字体使用 Windows 已安装的中文无衬线字体，不分发 Apple 字体。首次启动为空白工作区，设计演示段落只存在于隔离测试中。

Android 同步基线和历史在应用私有 `noBackupFilesDir/sync/`，不改变现有数据库。排查或恢复时只处理学习快照，不读取或复制加密凭证。服务器备份应通过 SQLite 备份接口或停服后复制数据库及必要日志，不能在运行中只复制主 `.db` 文件。

## 验证入口

```powershell
# 隔离 Room：两端续写、重复同步、旧记录去重、并发输入保全、拒绝移除记录
adb -s <emulator-serial> shell am instrument --user 0 -w -e scenario device-sync com.example.cet6.test/com.example.cet6.LocalDataInstrumentation
```

JVM 测试覆盖三方合并、过期冲突选择、服务器身份变化、条件写入重试上限、未知/凭证字段拒绝、HTTPS 地址校验、HTTP 认证、持久化历史和两个客户端往返。Android 单测另验证现有学习备份字段与 FSRS 进度往返。实际执行结果以本轮交付记录为准，不把进程内协议测试等同于真实公网或实体手机验收。

`DesktopWorkspaceTest` 在无窗口的 Compose 场景中运行真实桌面组件与 SQLite，检查编辑、提交、修改稿、搜索、批改展开/收起及数据重新打开，并将渲染结果保存到 `desktop/build/design-evidence/`。它不会打开或激活用户桌面窗口；不替代 Windows 输入法和系统窗口管理的人工检查。

框架依据：[Compose Multiplatform 1.7.3](https://github.com/JetBrains/compose-multiplatform/releases/tag/v1.7.3)、[Ktor 测试](https://ktor.io/docs/server-testing.html)、[SQLite JDBC](https://github.com/xerial/sqlite-jdbc)、[Windows DPAPI 适配](https://github.com/java-native-access/jna/blob/5.15.0/contrib/platform/src/com/sun/jna/platform/win32/Crypt32Util.java)。
