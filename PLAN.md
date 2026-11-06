# Qwen3 TTS Android Plan

## llama.cpp 完整生成 APK

在独立应用包名下复用现有 Compose 界面、录音、历史和 WAV 导出。用 b12138 和本地 mtmd 运行已验证的 Q4_K_M 主模型与 Q8_0 mmproj。设置默认 CPU，用户可选 Vulkan 声码器。已加载引擎在同一 App 会话内复用，切换模式时重载，生成完成后才交付完整音频。

构建使用 b12138、本仓库的 native 补丁、ARM CPU 变体和按需加载的 Vulkan 插件。依赖版本和构建命令见 [README](README.md)。包名为 `com.qwen.tts.android.llama`，最低 Android 12，目标架构为 arm64-v8a。

## 验证摘要

- 在一加 12 上测试了 CPU 和 Vulkan 声码器。两种模式分别复用同一个已加载模型连续生成两次，输出均为 3.52 秒的 24 kHz 单声道 WAV。Vulkan 模式确认在 App 进程内加载了 Turnip，按 44 个实际帧解码。
- v0.1.1 将新生成的音频保留为缓存预览。真机验证了试听、取消系统文件选择和手动保存。取消后预览仍可播放，确认保存得到与缓存一致的 WAV。
- APK 已通过签名与包结构检查。模型文件由 App 单独下载，两个下载地址的文件长度与模型清单一致。
- 发布版启动图标采用提供的千问图标。直接提取原 ICO 的 256 像素 PNG 帧供 Android 资源使用。

发布 APK 由本地构建后手动上传。原自动发布工作流已移除。
