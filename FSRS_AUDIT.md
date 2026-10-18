# G2.0 单词模块审计与最小接入方案

## 当前事实

- 基线 `0c97a55`，Room 3，单词表保存内容、来源、dueAt、repetitions、intervalDays；没有 stability/difficulty/lastReview 或三档评分。
- 调度为固定 1/3/7/14/倍增至 90 天；忘记后隔日。日志只有 remembered 布尔值，不能推断历史 Hard/Good。
- 首页有新词/复习目标；单卡作答与日志在同一事务中，已有重复提交保护。
- 80 个内置原创练习词，支持带来源说明的 CSV/JSON；缺音标与发音。真机测试额外导入 resilient，不得删除。
- OAuth / Responses 已有真机成功证据，必须保留。

## 候选核验（2026-10-16）

1. `open-spaced-repetition/FSRS-Kotlin`，固定 `670f2f895f316a9f78dfd19b991d9bc523c9b037`：README 声明 MIT，但没有独立 LICENSE、构建或测试文件；最后提交 2025-08-07。源码耦合原 App 类/资源，使用指数遗忘曲线而非 FSRS-6 幂函数，初始稳定度使用 coerceAtMost(0.1)，线性阻尼括号也不匹配上游。不能直接集成。
2. `bee-san/bee-fsrs`，固定 `98f3ed75a9b7f3fa7243320721671557908eea2d`：完整 MIT 许可，最后提交 2026-07-29；Kotlin stdlib 外无运行时依赖，有 JVM/JUnit 测试及 38 条 FSRS-6 参考向量，未提供覆盖率百分比。小型项目，不据此声称长期维护保证。提供 FSRS-6 和研究版 FSRS-7；本项目仅复用 FSRS-6。
3. 逐行比较备选实现与其标注的官方 `py-fsrs v6.3.1`（`3abe686e9c058d3f3c00bbeb92e68b71211b2b31`）发现：长期 stability 应使用更新前 difficulty，备选却用更新后 difficulty；interval 应使用 Python 的偶数舍入。其 38 向量来自公式快照而非实际运行上游，不能独立证明一致性。

## 接入决策

固定拷入 bee-fsrs 的 9 个 FSRS-6 源文件并保留许可，不引入包仓库认证、FSRS-7 或新运行时依赖。仅修正上述可追溯差异；移除 3 个 JVM Record 注解以兼容现有 Android API 26 工具链。保留修改清单与源文件 SHA-256。

对照测试直接运行固定官方 Python 源码生成，非重新抄写公式；Python 3.14 下只为 typing_extensions.Self 提供 typing.Self 类型别名，不改变上游算法，无包安装。测试覆盖首次评分、同日、跨日、延迟、失败及各评分。未完成这些核对前不宣称接入正确。

## 数据与交互方案

- Room 3→4：新增独立 word_schedules 表保存 FSRS memory、lastReviewedAt、learning step、算法身份；dueAt 仍以 words 表为唯一来源。新旧状态与日志原子保存。
- 保留旧词 dueAt/intervalDays/repetitions，不批量重排，不把旧布尔日志伪造成 FSRS 评分。旧词到期首次实际作答时用本次评分建立 FSRS memory，明确这是过渡初始化。
- 三档：不认识=Again、模糊=Hard、认识=Good。短时学习步骤由应用负责，FSRS 引擎负责 memory 与长期 interval；分钟到期任务不受每日数量上限隐藏。
- 新词/复习目标按每日不同单词计数，短时多次作答保留全部日志但不重复完成计划。
- 音标使用有明确再分发许可的 CMUdict 子集，保留来源和许可；已有中文释义/例句继续使用项目原创内容。用户导入仍须声明授权。
- 发音复用 Android TTS，仅选择本机离线英文 voice，无离线语音包时显示降级提示，不自动下载或更改系统设置。

## 验证与风险

历史验证范围包括离线构建、既有回归、参考测试、迁移、并发去重和调度模拟；此清单不构成 G3 开发门槛。

主要风险：初次 FSRS 过渡缺少旧 memory；系统英文语音包可能不可用；算法复用源规模较小；本地短测不能证明长期记忆效果。分别通过保留旧到期时间、明确初始化、离线降级、固定版本及直接上游对照控制风险。

来源：

- https://github.com/open-spaced-repetition/FSRS-Kotlin/tree/670f2f895f316a9f78dfd19b991d9bc523c9b037
- https://github.com/bee-san/bee-fsrs/tree/98f3ed75a9b7f3fa7243320721671557908eea2d
- https://github.com/open-spaced-repetition/py-fsrs/blob/3abe686e9c058d3f3c00bbeb92e68b71211b2b31/fsrs/scheduler.py
