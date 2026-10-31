# MicYou Mixer Android 预览版

日期：2026-10-31。基于 MicYou 2.1.1，Android 包名 `com.lanrhyme.micyou.ptt`。

## 下载与使用

从 [GitHub Release](https://github.com/SHAY5555-gif/MicYou/releases/tag/mixer-preview-2026.10.31) 下载 `MicYou-Mixer-2026.10.31-preview.apk`。`SHA256SUMS.txt` 提供校验值。

1. 在 Android 蓝牙设置中连接音箱。
2. 打开 MicYou Mixer，选择 Bluetooth，再点击 Connect Bluetooth。
3. Push to talk 只在按住时输出声音。Normal 单击开启，再次点击静音。
4. Sensitivity 越低，越需要较强的近距离声音才能打开拾音；Microphone volume 单独调整已拾取声音的输出增益。
5. 先关闭附加回声，确认干声合适后再调整 Echo level 与 Echo delay。

推荐起点为 Sensitivity 15%、Microphone volume 100%、Echo level 0%。音箱输出音量应按现场调整。拾音门限依赖声音强弱，不是物理距离传感器。

## 本次内容

- 不依赖电脑的 Bluetooth 麦克风输出。
- Push to talk 与 Normal 两种模式。
- 五个垂直推子，以及独立的拾音门限和输出增益。
- 平滑门控、支持设备上的 Android 降噪、85 Hz 低频过滤和峰值限制。
- 保留 USB / Wi-Fi 到电脑的音频传输和可选 Windows 音乐音量桥。
- 保留独立的实验性回声消除选项，默认关闭。

## 验证与范围

- 24 个 JVM 单元测试通过。
- 两个实体 Android 原生信号测试通过。
- Debug APK 构建、Release R8 收缩检查和 Git 空白检查通过。
- 已在实体 Samsung SM-F9710 与 Samsung Q70T 上验证路由、按住/松开、静音重连和实际试听；使用者确认声音明显改善。
- Bluetooth 仍有延迟与音质差异，没有把应用缓冲区大小当作端到端声学延迟。
- 此为调试签名预览版，可与官方应用并存；不是官方 MicYou 发布，也不是 Google Play 发布。

上游源代码、许可证与署名已保留。详细技术边界见 [混音器说明](mobile-mixer-preview.md) 和 [无线音频调查](samsung-wireless-audio-research.md)。
