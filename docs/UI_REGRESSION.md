# 界面与无障碍回归

当前结论以 [STATUS.md](STATUS.md) 和带日期的证据为准。以下夹具只在模拟器运行，会新增标明来源的原创练习；不清除已有数据，不请求 AI。

## 自拟阅读完整流程

构建并安装普通调试 APK 和测试 APK 后运行：

```powershell
adb -s <emulator> shell am instrument --user 0 -w -e scenario ui com.example.cet6.test/com.example.cet6.LocalDataInstrumentation
```

脚本确认新练习 ID 后才编辑，检查文章和题目输入、选项选择、离线提交、原文收藏、错因及后台持久化。使用语义点击、节点刷新和状态等待；全部原业务断言保留。2026-10-24 普通显示和 1.3 字体/深色组合均通过。2026-10-28 接续时先复现 `No scrollable viewport`：页签切换后，目标、容器与滚动分别重取界面树，容器可能已被替换。现改为同轮使用同一树并将已找到的容器交给滚动，全部业务断言保留；最终重编译普通 APK 与修复后的测试 APK 在两种显示下均通过。详细失败与通过记录见 [验收证据](../test-evidence/release-acceptance-20261028.json)。

## 2026-10-28 界面改版

导航改为 今日 / 练习 / 复盘 / 我的，写作、翻译、阅读在「练习」顶部的分段按钮切换（Compose 以 checked 而非 selected 报告分段状态）。自拟阅读脚本相应改为先点「练习」再点「阅读」，业务断言未改。改版后完整隔离套件与该脚本在普通显示和 1.3 字体/深色下均通过；`review-layout` 夹具上手动核对了逐条采纳、页脚“用采纳的 N 处写修改稿”和追问展开/收起。截图为 `test-evidence/emulator-redesign-*-20261028.png`。TalkBack 朗读与实体机未验证。

## 结构化答题卡

先记录字体、夜间模式和无障碍设置原值。准备原创资料和新练习：

```powershell
adb -s <emulator> push tools/exam-sample.json /sdcard/Android/data/com.example.cet6/files/exam-sample.json
adb -s <emulator> shell am instrument --user 0 -w -e fixture g4-sample com.example.cet6.test/com.example.cet6.LocalDataInstrumentation
adb -s <emulator> shell am instrument --user 0 -w -e fixture reading-accessibility com.example.cet6.test/com.example.cet6.LocalDataInstrumentation
```

在「练习 → 阅读」打开“原创样本”，确认标题为“原创答题卡无障碍检查”。选择一个选项，检查自动跳格；再在第一篇“按顺序连续输入”输入 `CADCBADCBA`，确认两篇分别为 `CADCB` 和 `ADCBA`。选中已填格检查“清除”在大字体下仍可见，然后核对答案。核对后，答题卡录入格应禁用，讲解页格子仍可切换题目。回读验证：

```powershell
adb -s <emulator> shell am instrument --user 0 -w -e scenario reading-accessibility-verify com.example.cet6.test/com.example.cet6.LocalDataInstrumentation
```

2026-10-24 已在 1.3 字体/深色下验证上述功能和布局，并修复锁定录入格仍向系统报告为启用控件的问题。证据：[structured-reading-accessibility-20261024.json](../test-evidence/structured-reading-accessibility-20261024.json)。

## TalkBack 边界

2026-10-28 用户明确暂停 TalkBack／语音验收。本次模拟器以 `-no-audio` 启动，TalkBack 未启用；以下只保留历史证据与恢复验收时的边界，不记为已完成。

TalkBack 16.0 服务绑定、触摸探索开启和答题卡标签焦点已有截图证据。完整焦点顺序、朗读及所有控件激活仍未验证；按键和手势尝试没有形成可靠通过证据，不能记为验收完成。初次启动的系统通知弹窗需先正常处理，避免把弹窗焦点当作应用焦点。

[Google 的 TalkBack 导航说明](https://support.google.com/accessibility/android/answer/6006598) 可作人工操作参考。测试结束恢复事先记录的全部设置。不要把已关闭服务、单一绿色焦点或工具成功退出等同于 TalkBack 完整回归。
