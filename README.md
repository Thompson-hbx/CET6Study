# 朱批 · 六级写译工作台（Windows）

个人自用的大学英语六级作文、翻译练习程序，界面像一本作业本：在横线纸上写，交稿后请 AI 老师用红笔批改，逐条决定采纳或不改，再抄出新的一稿。数据只存在这台电脑上。

## 功能

- **作文本、翻译本**：各自编页码；题目像便签别在页上；停笔 0.7 秒自动记下，关窗前也会保存。
- **交稿与重写**：交了的页不能再改；「重写一稿」另起一页并记下是哪一页的修改稿，原稿留着。
- **铅笔自查**（不花 AI 额度）：作文查字数范围、给定开头句、段落数；翻译比对原文与译文句数。
- **请老师批改**：模拟档位（2 / 5 / 8 / 11 / 14）、总评、四个维度和最多 12 处红笔批注；必改红线、可改虚线；逐条「采纳 / 不改」，「抄一遍改好的」只写进采纳的改法。
- **问老师**：就某一处批注或整篇追问，问答记在这一页上。
- **老师从哪来**：DeepSeek、兼容 OpenAI 的接口（API Key），或 ChatGPT 账号登录后用套餐额度（按 OpenAI「Sign in with ChatGPT」开源 / 本机应用流程实现）。
- **翻译逐句**：中文原文按句编号，点一句后在便利贴上问「难词怎么译 / 句子怎么搭」，老师只给词和骨架、不给整句；提示记在页上。
- **单词本**：从批注、翻译提示收词或手动加；六格抽屉法复习（间隔 1、2、4、8、16、32 天），「忘了」十分钟后再考。
- **复盘**：交了多少、近两周动笔天数、最近各页档位、必改批注里最常见的类别，以及批注处理和单词复习情况。
- **套卷**：导入自己整理的真题内容包（格式见 `docs/EXAM_IMPORT.md`），按考次排在书架上；一键把作文题或翻译原文翻开成新的一页，标出每套写到哪一步。包里附的参考译文 / 范文在交稿后才能翻开对照。同一套再导入时按修订号更新，修订号相同但内容不同的不覆盖。
- **搬旧本子**：一次性把旧手机 App 备份里的作文、翻译正文搬进空本子。

## 运行

需要 .NET 10 SDK（仓库的 `windows/global.json` 固定为 10.0.403，允许更新的功能版本）。

```powershell
dotnet run --project windows/src/Zhupi.App
```

数据在 `%LOCALAPPDATA%\Zhupi\`：

| 文件 | 内容 |
| --- | --- |
| `study.db` | 本子（SQLite） |
| `teacher.json` | 选的老师、接口地址和模型（不含密钥） |
| `teacher.key` | API Key，Windows DPAPI 加密 |
| `chatgpt.session`、`chatgpt.hint` | ChatGPT 登录凭证，DPAPI 加密 |
| `chatgpt.client`、`chatgpt.host` | OpenAI 发给本机朱批的应用编号、本机标识（不是凭证） |

试用或检查时可用 `--data-dir=<目录>` 指向别的文件夹，避免碰到正式本子。

字体（可选，都是开源字体，不随仓库分发）：批注等手写处用[霞鹜文楷](https://github.com/lxgw/LxgwWenKai)（`LXGWWenKai-Regular.ttf`），标题用 Noto Serif SC。装到当前用户即可；没装时分别退回楷体、华文中宋。

## 开发

```powershell
cd windows
dotnet build Zhupi.slnx
dotnet test Zhupi.slnx
dotnet format Zhupi.slnx --verify-no-changes --severity warn
```

- `windows/src/Zhupi.Core`：本子存储、自查、老师请求与批改协议、ChatGPT 登录（`net10.0`，可测试）。
- `windows/src/Zhupi.App`：WPF 界面（`net10.0-windows`），CommunityToolkit.Mvvm。
- `windows/tests/Zhupi.Core.Tests`：xUnit；网络都用假服务端，不发真实请求。

`tools/` 是整理个人真题内容包的 Python 脚本，见 [docs/EXAM_IMPORT.md](docs/EXAM_IMPORT.md)。私人资料的保存规则见 [docs/PRIVATE_BACKUP.md](docs/PRIVATE_BACKUP.md)。

旧的 Android App、Compose 桌面端和同步服务已于 2026-11-07 从当前代码中删除，需要时可在 Git 历史中查看。
