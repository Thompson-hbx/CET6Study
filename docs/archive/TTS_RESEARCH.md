# 离线发音自然度核查

用户真机听测：单词和例句能听到，但声音机械。应将“离线发音功能可用”和“音色自然度满意”分开，不能根据引擎完成回调推断听感满意。

当前改进：仅从已安装、声明不依赖网络的英文 voice 中选择；美式优先，随后按引擎报告的 quality 从高到低、latency 从低到高选择。原实现只按名字排序，不能保证选到质量较好的音色。质量分数由引擎报告，不保证更改后听感一定改善。

官方核查：

- Android Voice 文档明确提供 getQuality（越高越好）、getLatency（越低越好）及 isNetworkConnectionRequired，可在不添加第三方依赖的情况下选择现有离线音色：https://developer.android.com/reference/android/speech/tts/Voice
- Google Android 帮助说明可选择语音引擎、语言、语速、音高，并安装语言数据；可选引擎因设备不同，不应假定 vivo 必有某个 Google 音色：https://support.google.com/accessibility/android/answer/6006983
- sherpa-onnx 官方 Android 项目包含可替代系统 TTS 的 SherpaOnnxTtsEngine，框架为 Apache-2.0，可作为后续更自然离线合成的评估方向：https://github.com/k2-fsa/sherpa-onnx/tree/master/android 。框架许可：https://github.com/k2-fsa/sherpa-onnx/blob/master/LICENSE 。各语音模型的授权、体积、性能及听感仍须分别核验，不能将框架许可证当作全部模型的许可证。

本轮没有安装新引擎、下载模型、改变系统默认语音设置、使用未公开在线朗读接口，也没有改写 ChatGPT OAuth/Responses 链路。若现有离线 voice 均机械，排序改进无法创造不存在的高质量模型；后续选定具体模型并说明授权和成本后，再按用户规则确认安装。
