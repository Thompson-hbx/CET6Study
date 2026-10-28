# 草稿落盘与进程恢复边界

更新：2026-10-27。写作、翻译、阅读使用现有 Room 自动保存。下表保留 2026-10-25 生命周期与跨进程证据；2026-10-27 另修复了恢复后的草稿加载时序，没有更换存储格式。

## 本次观察

| 场景 | 终止前条件 | 新进程读回结果 |
| --- | --- | --- |
| 前台直接结束进程 | 三类草稿各快速编辑 20 次，均显示已保存 | 3/3 最新完整内容恢复 |
| 切换到桌面后结束进程 | 实际 Activity 已停止，均显示已保存 | 3/3 最新完整内容恢复 |
| 熄屏后结束进程 | Activity 已停止，PowerManager 表明屏幕非交互，均显示已保存 | 3/3 最新完整内容恢复 |
| 数据库写入被阻塞时结束进程 | 三类草稿均仍显示“正在保存”，没有成功确认 | 最新输入 0/3 落盘，先前已提交的基线 3/3 保留 |

每次都先确认 PID 属于本项目，再以应用 UID 发送 SIGKILL；终止后新 PID 重新打开数据库并启动真实 Activity/ViewModel，验证当前草稿。原有练习逐条哈希及题库哈希未变。屏幕已恢复唤醒。

最后一项是对未落盘窗口的受控验证，不能记为“不丢稿通过”。测试允许以后恢复出更完整的最新稿；当前记录明确显示未确认输入丢失。正在保存时杀进程仍有风险，已取消原来的 350 ms 固定防抖也不能让未提交的内存内容获得持久化保证。输入事件尚未被应用处理、掉电和存储硬件异常仍未覆盖；本日低内存尝试也尚无可确认的 LMKD 通过证据，见末节。

## 复跑

安装同一版本普通调试 APK 与测试 APK 后，在仓库根目录执行：

```powershell
.\gradlew.bat --offline assembleDebug assembleDebugAndroidTest
adb -s emulator-5554 install -r app\build\outputs\apk\debug\app-debug.apk
adb -s emulator-5554 install -r app\build\outputs\apk\androidTest\debug\app-debug-androidTest.apk
.\tools\check_draft_process.ps1
```

脚本默认只接受 emulator serial，四种场景使用新建的有标签原创练习；不会卸载、清除数据、删除历史或发出 AI 请求。它会终止所选模拟器的本项目进程并改变屏幕开关，不能对正在使用的会话同时运行。失败时保留运行日志和标记，先检查现有进程及标记再决定恢复或重跑；不要覆盖未完成场景。

准备/核对入口为自定义仪器测试的 `draft-process-prepare`、`draft-process-verify`，附带 mode 和 run-id，由脚本协调。它们不会进入默认隔离套件；显式场景会给模拟器的 App 数据库新增原创草稿。证据：[backend-draft-process-20261025.json](../test-evidence/backend-draft-process-20261025.json)。

## 恢复后的当前草稿（2026-10-27）

已修复任务落盘后、数据库提交前中断的冷启动问题：数据库恢复成功后，编辑器此前仍保留旧稿快照。现在有待恢复任务时先结束启动恢复，再加载当前草稿；恢复期间收到的系统分享也等待草稿加载完成，原稿与新分享分别保留。自动启动恢复不提前 flush 内存快照，日常手动恢复仍保存当前编辑。

三类稿正常恢复、提交后续传、恢复期间分享三个模拟器新进程路径均通过；旧练习和题库哈希未变。完整入口与边界见 [备份恢复说明](BACKUP.md#启动恢复与草稿同步2026-10-27)，证据 [backend-restore-draft-20261027.json](../test-evidence/backend-restore-draft-20261027.json)。这不改变上表未确认输入仍可能丢失的结论。

## 低内存尝试与证据边界（2026-10-27）

**本次未确认 LMKD 回收通过。** 首次独立夹具因 Kotlin 运行库不随测试 APK 独立打包而启动失败，已改为只依赖 Android/Java SDK；后续较大内存施压时，模拟器活动服务超时并重启。施压目标正常应用 PID 为 6820，施压前已核对 `oom_score_adj=900`；清理压力进程时它仍存活，系统恢复后没有取得该 PID 的匹配退出记录。因此不能把服务重启、PID 消失或驱动超时当作低内存回收通过。

用新进程和真实 ViewModel 单独检查数据：3/3 已确认保存的最新草稿完整恢复，42 条原练习逐条哈希与题库哈希未变。结果只输出 `DATA ONLY`，标记 `lowMemoryVerified=false`；它不改变未确认输入仍可能丢失的边界。原前台 SIGKILL 驱动回归也通过。

新增显式工具 `tools/check_low_memory_draft.ps1`，不进入默认隔离套件，也未在手机运行：

- 只接受模拟器 serial，并复核设备指纹；Android 11+ 且能报告低内存退出时才准备原创草稿。仪器准备结束后，普通启动应用并退到后台，达到实际缓存条件才施压。
- 压力组件只在测试 APK，入口要求 Shell 持有的 `android.permission.DUMP`、模拟器身份、明确测试参数和 UUID。当前分配预算最多为模拟器 RAM 的四分之一且不超过 512 MiB，分配循环有 35 秒截止；清理只通过压力包自身 UID 结束压力进程，不依赖 ActivityManager，也不手动终止学习进程。
- 成功必须匹配正常学习进程 PID、时间及 [`ApplicationExitInfo.REASON_LOW_MEMORY`](https://developer.android.com/reference/android/app/ApplicationExitInfo#REASON_LOW_MEMORY)，并通过新进程草稿/旧记录/题库核对；普通 SIGKILL、其他退出原因或仅数据检查均不算通过。
- 缓存条件未达到时不施压并保留标记。确认同一正常进程已达到缓存条件后，可用 `-ResumeRunId <本次UUID>` 继续；已有施压记录时拒绝盲目重复。失败日志、旧标记和历史数据均保留。

复跑前安装匹配的普通 APK 与测试 APK，再执行：

```powershell
.\tools\check_low_memory_draft.ps1 -Serial emulator-5554
```

当前较大施压未通过，不应靠自动放大预算来取得通过结果。最终夹具仅做了 32 MiB 上限/截止/释放检查，已通过；这不证明 512 MiB 或所有环境均稳定，更不证明已触发 LMKD。完整隔离套件、构建与 Lint 通过（0 错误 / 7 个既有警告）；本轮无产品代码改动，未重跑 JVM 单测、未更新手机或发出 AI 请求。后续仍需更合适的低内存测试环境、实体机与掉电验证。

证据：[backend-low-memory-probe-20261027.json](../test-evidence/backend-low-memory-probe-20261027.json)。内存映射和释放使用平台 [SharedMemory API](https://developer.android.com/reference/android/os/SharedMemory)，没有新增依赖。
