# ClawMate Android

[ClawMate HarmonyOS](https://github.com/doulujiyao12/mobiinfra-oh) 的 Android 迁移项目。
当前主前端采用 **ArkTS/ArkUI + ArkUI-X**，跟随鸿蒙页面与业务源码；Android 系统能力使用 Kotlin。

普通用户请从[图文用户手册](docs/user-manual/index.html)开始：首次配置、聊天与图片、图库与记忆、任务提醒、会议和数字分身均有操作说明。手册支持离线阅读、搜索、图片放大与打印；下载时保留整个 `docs/user-manual/` 目录。维护方法见[手册说明](docs/user-manual/README.md)。

## 当前版本

分支与历史PR见[分支和PR导航](docs/BRANCHES_AND_PRS.md)。当前完整共享版在
`codex/shared-frontend`；`main`跟随上游。先完成技术路线闭环，再由用户与 fellow 讨论是否递交 PR。

共享版为 `com.clawmate.shared`，名称“ClawMate 共享前端”，版本 `0.3-integrated`。
包含引导、五入口、首页建议/陪伴、会话与流式聊天、图片聊天、query_events、长期记忆管理、
图库/汇总、工作流编辑与有界运行、设置和系统 OCR/ASR/TTS。
云端能力需用户自行配置和授权；真实模型质量与平台待办不能视为已验收。

| 模块 | 职责 |
| --- | --- |
| `shared-frontend/` | 上游锁、共享 ArkTS 组装、平台接口与可审查的源码适配 |
| `shared-app/` | ArkUI-X Android 宿主、生命周期、系统 API 和桥接 |
| `android-capabilities/` | 两版共用的 OCR/ASR/TTS、图片准备、有界工作流执行与受限协议 |
| `app/` | 保留的 Compose 回归基线；原正式包及其数据不被共享版覆盖 |

根 Gradle 管理所有 Android 模块。共享包的配置/会话/图片/索引由 ArkTS 管理，
旧 Room/DataStore 不双写，也不自动继承旧授权或密钥。

## 构建与检查

Windows 工具链和只读源输入的准备步骤见 [共享前端说明](shared-frontend/README.md)。
使用锁定的 OpenHarmony API20 `6.0.0.47` 与 ArkUI-X API20 `6.0.0.103`，JDK17；
SDK、运行库和生成工程留在忽略目录，本机路径只作参数：

```powershell
./shared-frontend/build.ps1 -JavaHome '<JDK 17>' -AndroidSdk '<Android SDK>' -MeetingSdkRoot '<on-device-meeting-notes>' -Offline
node shared-frontend/verify.mjs
node shared-frontend/verify-native.mjs
node shared-frontend/verify-integration.mjs
node shared-frontend/verify-meeting.mjs
node experiments/arkui-x/verify-contracts.mjs --source-root .arkui-x-validation/reference
./gradlew.bat :android-capabilities:testDebugUnitTest :app:testDebugUnitTest
```

脚本按输入哈希验证、ArkTS 编译、运行库导出、Android 打包执行，失败即停止。
共享 APK：`shared-app/build/outputs/apk/debug/shared-app-debug.apk`。
当前调试包为 arm64、最低 API26；发布签名、包体裁剪与发布等级尚未验收。

共享版新增“汇总 → 本机会议 · 录音与纪要”，包含离线转写、会议管理和主动保存到本机记录。
源码依赖、模型准备及数据归属见[会议集成说明](docs/MEETING_INTEGRATION.md)。

```powershell
adb install -r shared-app/build/outputs/apk/debug/shared-app-debug.apk
adb shell am start -n com.clawmate.shared/.SharedActivity
```

Compose 基线仍可运行 `./gradlew.bat :app:assembleDebug`；不要用日常测试覆盖原包及其用户数据。
历史验证与阶段索引统一见 [迁移日志](MIGRATION_STATUS.md)，构建约定见 `AGENTS.md`。

## 配置和能力边界

共享版在“我的”填写 HTTPS 模型地址、模型名和密钥，再主动授权云服务；
图片发送及聊天记忆另有数据范围确认。拒绝/撤回授权会阻断新请求并取消已注册请求。
系统 ASR 使用用户选择的识别服务，其是否联网由该服务决定。
旧 Compose 的可选配置模板为 `app/src/main/res/raw/config_example.properties`。
凭据、签名、私有数据库、SDK路径和设备状态禁止提交。

本机工作流支持有界等待、条件、循环、echo/summary 和取消；共享版另接本机图片`vlm_qa`，
使用配置的Decider视觉模型并逐次确认；GUI和其他模型工具仍明确受限。
每日采集提醒与聊天自动摘要已接通，实际后台送达与模型质量仍需验收。
HDC/Python、微信采集、MiMO、MNN/HiAI、照片参考分身等仍有待办。
当前证据来自一台 OPPO 手机，不能推断小米等厂商或 iOS 已通过。

## 开发与验证记录

- [当前状态与实际检查](MIGRATION_STATUS.md)
- [共享架构与数据归属](docs/SHARED_ARCHITECTURE.md)
- [共享页面对齐边界](docs/FRONTEND_PARITY.md)
- [待办](MIGRATION_BACKLOG.md)

鸿蒙上游只读；更新时审查锁与适配并重新编译/回归。通用 UI/业务留在 ArkTS，
Android 差异集中到适配层与共用能力库。每个有意义的阶段提交并记录真实验证结果；
不使用演示数据冒充模型回复、采集或执行成功。
