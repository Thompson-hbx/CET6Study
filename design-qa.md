# Windows 桌面工作区设计验收

日期：2026-11-01。目标为用户选定的第 3 张 Apple 风格桌面方案；沿用原生 Compose Desktop、真实本地记录及既有同步协议。

## 比较依据

- Source visual truth：`D:/Workspace/projects/cet6/CET6Study/.tools/desktop-design/selected-apple-desktop.png`，1487 × 1058 像素。
- 实现：`desktop/build/design-evidence/translation-draft.png`，1440 × 1024 像素。
- 视口：1440 × 1024 dp，density = 1，fontScale = 1；另检查 1000 × 680 dp。
- 原图按宽高比例归一至 1440 × 1024（纵横缩放差小于 0.1%）；实现直接以同尺寸绘制，不包含 Windows 标题栏和阴影。
- 状态：浅色、翻译草稿「学习日志」、相同原创中英文段落、批改折叠、未配置服务器。样本仅存在于隔离测试数据库，不是生产预置数据。
- 整体并排比较：`desktop/build/design-evidence/comparison-final.png`。
- 标题与中英文字体细节同图比较：`desktop/build/design-evidence/comparison-details-final.png`，上方设计、下方实现。
- 其他实际状态：`translation-feedback.png`、`translation-history.png`、`reading-compact-feedback.png`、`device-sync.png`（均位于同一 evidence 目录）。

## Findings 与修复历史

1. [P1，已修复] 首轮常规字重中文落入衬线回退，工具栏、导航、正文层级偏小。
   - 修复：明确从 Windows 已安装的微软雅黑字体文件加载常规/粗体；调整标题、正文、导航及按钮字号。未复制、分发系统或 Apple 字体。
   - 修复前：`comparison-before-typography.png`；中间复核：`comparison-after-font.png`；最终：`comparison-final.png`、`comparison-details-final.png`。
2. [P2，已修复] 中英文采用相同字号时，英文行过短，中文段落密度与目标不同。
   - 修复：宽窗口中文原文 25sp，英文作答 22sp，行高 42sp；缩小文档标题区底部间距。最终中英文均为四行，保持原文/作答并排与大面积留白。
3. [P2，已修复] 1000 × 680 下展开固定高度批改面板会遮住材料标题并压缩正文。
   - 修复：窄窗口隐藏重复保存提示；短窗口批改区 160dp、折叠条 56dp，文档头减少留白，保留各自滚动的输入区。
   - 复核：`reading-compact-feedback.png`，提交按钮仍完整可见，材料、作答与批改同时可访问。
4. [验收环境修复] 离屏场景未设置容器尺寸时，Popup 被限制在左上角。
   - 修复：测试场景提供真实 WindowInfo 容器尺寸；记录列表正确锚定工具栏按钮，不改生产 Popup 定位行为。
5. [P2，已修复] 同步页底部状态文字在初次视觉检查中不可见。
   - 修复：将保存/同步状态放在工作区的共同固定底栏，避免各分支单独布局。语义检查要求文字拥有非零面积且在视口内，最终位置为 (276, 970)–(1227, 991)。
   - 最终渲染复核：`device-sync.png` 及放大的 `device-sync-footer.png`，可见本机保存状态与未配置同步提示；主练习区在 `comparison-final.png` 中再次对照。

## 五项必查表面

| 表面 | 结论 |
| --- | --- |
| 字体与层级 | 中文无衬线、标题粗体、原文与译文的大小和行距已分别校准；Windows 字形和抗锯齿与概念图仍有细微差异。 |
| 布局与节奏 | 独立侧栏、顶部工具栏、双栏文档、竖分隔线、折叠批改和固定底栏；主区域比例与目标一致，无持久第二列表栏。 |
| 色彩与状态 | 白色文档、珍珠灰背景、蓝色主操作和导航选中态；边框克制。保存异常使用红色提示，不冒充未保存成功。 |
| 图像与图标 | 独立生成并实际加载透明蓝色折页 logo，未用代码图形替代；界面图标来自 Compose 的统一图标库。 |
| 文案与内容 | 侧栏、提交、批改与同步文案一致；副标题显示实际「草稿/已提交」，替代概念图的「原创段落」，避免假定内容来源。 |

## 功能与验证边界

- 离屏测试直接运行生产 Compose 组件、语义操作和隔离 SQLite：新建、输入、立即落盘、提交后只读、修改稿保留原记录、搜索及状态筛选、批改开关、同步/生词/写作导航、数据库重新打开。
- 1000 × 680 下验证提交按钮位置与空材料校验；同步页验证状态文字有实际可见面积。
- 修复键盘焦点样式；快捷键为 Ctrl+N / Ctrl+F / Ctrl+Enter。未用 OS 级输入自动化验收输入法、快捷键和窗口缩放。
- 用户要求后台继续，本次使用无窗口 CanvasLayersComposeScene + Skia 离屏渲染，没有打开或激活当前桌面的应用窗口。原生应用不是浏览器页面，不适用浏览器控制台检查；JVM 测试无运行异常。
- 真实公网 HTTPS 服务、实体手机与最终桌面应用之间的端到端同步尚未验收。

## Follow-up Polish

- [P3] 系统图标与概念图的笔画、logo 折叠角度和背景的柔光纹理有小幅差别。
- [P3] Windows 系统字体与概念图不能逐字像素一致；保留可读性与相同段落行数，不分发 Apple 字体。
- [P3] 原生标题栏、任务栏图标和中文输入法需在日后允许可见窗口检查时复核。

## Implementation Checklist

- [x] 选定方案、完整布局、真实资产、真实数据与交互。
- [x] 同尺寸整体及字体细节比较并修复主要差异。
- [x] 紧凑视口与原记录保全回归。
- [x] 固定状态栏最终渲染与 Windows 免安装应用打包成功。

未发现剩余 P0/P1/P2 设计差异。桌面测试 4 项通过；同步核心 11 项、服务端 3 项既有测试结果保持通过。构建仍报告 Compose 1.7 的 classpath 图片加载 API 弃用提示，不影响此版本运行；未借此升级框架。

final result: passed
