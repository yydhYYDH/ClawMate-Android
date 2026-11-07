# 本机会议接入

在共享版「汇总」的日历分析和邮箱分析下方打开「本机会议」。主页面与业务存储使用 ArkTS。Kotlin
`MeetingBridge` 连接会议 SDK、Android 前台录音、系统文件选择和音频回听。

## 源码依赖与构建

先准备 [on-device-meeting-notes](https://github.com/JKSOBBING/on-device-meeting-notes/tree/codex/android-meeting-sdk)
的 `codex/android-meeting-sdk` 分支，按其 Android 构建说明准备 Python 3.14、离线 wheel 和原生库。
本阶段验证的 SDK 提交为 [93b58ad](https://github.com/JKSOBBING/on-device-meeting-notes/commit/93b58adc901268a7eed052ce6b849e9be870060f)。
模型权重不进入 Git/APK，应用内按组下载；没有下载清单的模型会明确显示需要手动准备。

根 Gradle 从 `-PmeetingSdkRoot=<仓库根目录>` 或 `MEETING_SDK_ROOT` 引入
`android/meeting-sdk` 源码。SDK 与宿主使用同一套 Kotlin/AGP，避免直接消费较新 Kotlin 编译的
AAR 元数据。不要同时在两个仓库运行 Gradle 写同一个 SDK 的 build 目录。

Windows 按 `shared-frontend/README.md` 准备锁定的 ArkUI-X/OpenHarmony API20，运行：

```powershell
./shared-frontend/build.ps1 -JavaHome '<JDK17>' -AndroidSdk '<Android SDK>' -MeetingSdkRoot '<on-device-meeting-notes>' -Offline
node shared-frontend/verify.mjs
node shared-frontend/verify-native.mjs
node shared-frontend/verify-integration.mjs
node shared-frontend/verify-meeting.mjs
node experiments/arkui-x/verify-contracts.mjs --source-root .arkui-x-validation/reference
./gradlew.bat :android-capabilities:testDebugUnitTest :app:testDebugUnitTest :meeting-sdk:testDebugUnitTest
```

Android SDK 需要 platform 34/36、build-tools 34/36。共享宿主 compileSdk36，targetSdk34、minSdk26
不变；当前为 ARM64 调试包。Gradle 8.9、AGP8.5.2、Kotlin1.9.24 与 Chaquopy17.1 已共同构建验证，
AGP 对 compileSdk36 的警告不等于正式发布兼容性认证。JDK/SDK 路径只作为本机参数。

SH-16 在宿主构建中从固定源码重建会议复数数学库，替换原4KB对齐预编译输入；
会议仓库源码保持不变。构建结束强制检查原生ABI、全APK ELF及ZIP 16KB对齐，
ZIP检查使用build-tools36.0.0。依赖构建输出位于宿主忽略目录，完整方法和运行验收边界见
[会议原生库16KB检查](MEETING_NATIVE_16KB.md)。

APK：`shared-app/build/outputs/apk/debug/shared-app-debug.apk`。可以用会议仓库的构建 Python
执行 `android/verify_host_apk.py <此APK>`，检查当前 Python/网页源码、单份 Python 负载、
ARM64 原生库哈希、ArkTS 页面及 Kotlin 服务。

## 生命周期与资源

进入会议页才初始化 SDK，API/摘要端口分别为 18090/18091，线程数2，存储在当前应用
`files/meeting`。页面不访问独立会议 APK 的私有目录。只对 127.0.0.1 开放明文例外。
首次使用录音时申请麦克风权限；授予后再次按开始按钮。会议录音与聊天 ASR 互斥，开始会议前停止
ASR/TTS/回听。录音由前台服务持有；页面关闭取消自己的 HTTP 和订阅、停止回听，保留正在录音的服务。
回到页面会从 SDK 状态恢复当前录音。ASR/摘要空闲后释放，生成纪要时才启动摘要模型。

页面支持基础转写、精修模型选择、模型下载状态、暂停/继续/结束、历史/搜索/回收站、保存会议、
正文/说话人编辑、历史版本恢复、按段回听、精修/纪要任务与取消/重试，以及系统文件导入导出。
文件上传透传用户选择的本地精修配置。人工编辑携带打开编辑器时的版本/hash，冲突保留编辑内容并报错。

## 数据归属

会议数据库、音频和正文版本由 SDK 后端独占写入。用户按“保存到本机记录”才生成指定版本的
交接副本；也可选择其他应用导出的交接 JSON，先预览再保存。Kotlin 校验 SHA256、版本、来源和大小；
ArkTS 独占写入 ClawMate 的 Markdown、`.state.json`、索引和导入收据，归入已有“文档”类别。
索引生成显式关闭网络模型。

确定文件名 `meeting-<export_id>.md`，重复同一副本不新增记录；失败后重试修复同名文件。
已被用户改动的 Markdown 保留并提示另存版本。收据与原始交接包放在 DataManager 的
`meeting_imports`，保留来源实例、会议、音频ID、正文版本/hash。副本不含录音，不自动跟随原文更新，
不自动创建任务或上传云记忆。原会议与副本分别拥有自己的修改生命周期。

## 验证与边界

真机专项测试需要先在共享包准备公开的基础/说话人权重及 SenseVoice 公开 `test_wavs/zh.wav`；
不会复制另一个包的会议数据库。运行：

```powershell
./gradlew.bat :shared-app:assembleDebugAndroidTest
adb install -r shared-app/build/outputs/apk/debug/shared-app-debug.apk
adb install -r shared-app/build/outputs/apk/androidTest/debug/shared-app-debug-androidTest.apk
adb shell am instrument -w -r -e class com.clawmate.shared.MeetingIntegrationDeviceTest com.clawmate.shared.test/androidx.test.runner.AndroidJUnitRunner
```

该测试使用公开中文录音，实际加载 ArkUI-X/Python/ONNX，打开会议页面并保存副本，检查去重、来源和
Activity 关闭后 SDK 状态。ArkUI-X 条件布局可能保留过期的无障碍节点，测试用现有离线中文 OCR
定位实际渲染的按钮，再注入触摸。它不启动麦克风；长时间锁屏录音、来电/音频焦点、其他厂商、16KB 页大小、
发布签名/包体与完整隐私数据迁移仍需专项验收。真实本地纪要按需加载验证见会议仓库的第四步记录。

## 与鸿蒙录音分析的差异（2026-11-07）

`coevomind-app` 的 [`84535ff3`](https://github.com/doulujiyao12/mobiinfra-oh/commit/84535ff3) 新增独立的「录音分析」，已在核对的分支头 `0bc30157` 中。以下为源码和文档对照。

| 范围 | 鸿蒙 `RecordingPage` | Android `MeetingPage` |
| --- | --- | --- |
| 录音来源 | 麦克风、系统音及混音，支持 WAV 文件分享/导入、Wear Engine 接收华为手表录音 | Android 前台麦克风录音、暂停/继续、系统文件选择器导入音频 |
| 转写与说话人 | 默认边录边用 Silero VAD + SenseVoice 转写。文件分析可开启 Pyannote 分段和 3D-Speaker 声纹匹配与比较。长录音分块保存进度 | Meeting SDK 提供实时文本事件和本地 SenseVoice 转写，并提供 FireRed、Qwen 离线精修配置，以及正文、说话人标签和历史版本编辑 |
| 纪要 | 默认用现有云端 Planner，仅提交转写文字。也可用应用已下载的大模型分段生成本地结构化摘要与待办，支持继续 | 会议页通过独立 SDK 的本地纪要任务生成文本，通过 SDK 管理任务状态、取消和重试 |
| 保存 | 独立录音库保存 WAV、转写、纪要和声纹。可只删除音频并保留文字结果 | 会议 SDK 保管音频、数据库和版本。可导出 Markdown、JSON 或交接副本。主动保存副本后，以可去重的 Markdown 记录进入「汇总 → 文档」 |

鸿蒙说明的操作清单仍写着录制后转写，后文和当前页面代码均已接入边录边转写，页面默认开启。系统内录和混音、手表接收、声纹比较、分段续跑和鸿蒙摘要结构列入会议最终阶段核查。 [鸿蒙录音分析设计](https://github.com/doulujiyao12/mobiinfra-oh/blob/0bc301573e47e5e6f6d0fd03fdd9f54143f298df/docs/recording-analysis.md) · [鸿蒙录音页面](https://github.com/doulujiyao12/mobiinfra-oh/blob/0bc301573e47e5e6f6d0fd03fdd9f54143f298df/entry/src/main/ets/pages/recording/RecordingPage.ets)
