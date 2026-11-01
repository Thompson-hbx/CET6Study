# 朱批 Windows 写作工作台原型

这是可独立运行的 React + Tauri Windows 桌面原型，用于体验写作与批注。现有 Android 和 Compose Desktop 保留；原型使用独立标识 `com.zhupi.writing.prototype`，不打开或迁移旧的 Room、SQLite、题库、凭证或备份。

## 使用

构建后的程序为 `src-tauri/target/release/zhupi-writing-prototype.exe`。可双击，或从仓库根运行：

```powershell
.\tools\writing-prototype.ps1 run
```

运行已构建的 EXE 不需要 Node.js 或 Rust。需要 Windows 的 WebView2 运行时；本机已实际运行验证。当前不提供 MSI、自动更新或签名安装包。同一应用标识的调试与发行实例需先后运行，避免共用 WebView2 配置的占用冲突。

1. 点击「新建作文」，填写标题、题目与正文；编辑按顺序保存到本机，Ctrl+S 可主动保存或重试。
2. 点击「导入已有批改」，粘贴完整的写作批改 JSONL。这个原型不调用 AI，不把手工样例当作模型输出。
3. 在「批注」中核对原句，逐条采纳/忽略。原稿中无法定位的引用不会自动应用。
4. 点击「生成修改稿」创建独立新稿；原稿和采纳决定保留。关闭窗口会等待保存，失败时明确提示继续编辑或放弃未保存内容。主窗口仅开放生命周期所需的 close/destroy 权限，没有通用文件系统或网络插件权限。

导入协议沿用当前写作批改格式，支持代码围栏与多行 JSON；字段类型、重复单值和整数原始格式按手机回归语料验证。修改后的草稿与批注快照不一致时停止自动生成，避免把旧批注套到新文本上。

## 本地存储

Rust 使用 Tauri 的应用数据目录，保存 `workspace.json`，最多 100 篇、总文件 32 MiB；正文每篇最多 30,000 字符。串行保存、版本核对和 Windows 文件共享锁用于防止过期快照或重复进程覆盖。先写临时文件并同步，再替换正式文件；损坏/未知版本会明确拒绝，不重置为空库。

默认路径由系统和 Tauri `app_data_dir` 决定，独立于原桌面应用的 Xiye 目录。调试构建可通过 `ZHUPI_WORKBENCH_TEST_DIR` 使用隔离测试目录；优化构建忽略这个测试覆盖。数据不自动上传，不进入原应用的备份/同步。

## 构建与验证

Node、pnpm、MSVC Build Tools 和 MSVC Rust 工具链需已安装。本机额外 Rust 位于 `C:\Dev\Env\Rust`，下载缓存按用户指定保存在 `C:\Dev`；脚本只临时配置本次进程环境，不改全局 PATH。

首次安装项目依赖：

```powershell
pnpm --dir desktop-tauri --store-dir C:\Dev\Env\pnpm-store install --frozen-lockfile --ignore-scripts
.\tools\writing-prototype.ps1 check
.\tools\writing-prototype.ps1 build
```

`check` 执行 TypeScript 严格检查、Node 内置测试、Rust 格式与存储测试；`build` 生成优化 EXE。可通过 `-RustDirectory <已有安装目录>` 指定不同的 Rust 根目录。前端 `pnpm-lock.yaml` 和原生 `Cargo.lock` 均保留；没有全局安装 npm 包。`pnpm dev` 仅供 Tauri 开发流程使用，独立在浏览器打开不会提供伪装的存储后端。

当前检查包含现有 JVM 写作协议语料、非完整/错误批改拒绝、精确定位与偏移、保存顺序、错误重试、重开、损坏保全和实际磁盘失败。原生窗口已验证新建、中文标题/多段英文存取、离线批注、采纳、修改稿与跨进程恢复；不等于所有输入法、辅助技术和极端退出都已验收。
