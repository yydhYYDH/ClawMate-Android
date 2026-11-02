# ArkUI-X 复用验证

目标：跟随鸿蒙版本维护 Android，尽量共享真实源代码，并保留其他系统的适配边界。
本目录是独立实验，不替换现有 Compose 应用，不修改鸿蒙参考仓库。

**当前结论（2026-10-25）：共享 ArkTS 源码编译为 Android 已在真机验证可行，值得继续验证主链路。**
原始首页和经少量平台适配的聊天页已运行；这不代表整个鸿蒙应用可以直接打包，
也不代表完整产品迁移已经完成。最终逐项结果和产物哈希见 `verification-results.json`。

## 可重复检查

上游版本及只读输入文件的 Git blob 哈希见 `upstream-lock.json`。
从仓库根目录运行（主机契约检查使用带 `stripTypeScriptTypes` 的 Node 24）：

```powershell
node experiments/arkui-x/verify-contracts.mjs --source-root <鸿蒙只读源码目录>
node experiments/arkui-x/audit.mjs --source-root <鸿蒙只读源码目录>
```

也可使用通过 GitHub 连接生成的、不含配置值的导入元数据重放静态审计：

```powershell
node experiments/arkui-x/audit.mjs --inventory .arkui-x-validation/source-inventory.json
```

检查在内存中擦除 TypeScript 类型并包装导出，不改写算法。每个输入文件先验证
Git blob 哈希，版本漂移会失败。主机检查通过不能证明 ArkTS 编译或 Android 运行通过。
`audit-summary.json` 是保守的导入依赖图，不区分仅用于类型的普通导入，也不覆盖隐式
框架全局变量；文件数量不能当作代码复用率或待重写量。

## 2026-10-25 第一阶段结果

- 源版本：`cd69b68bf16b9ed352be5073939eb24d042de583`。
- 扫描全部 197 个生产 `.ets` 文件：90 个直接导入平台模块，107 个未直接导入；
  38 个包含 UI 装饰器。保守首页依赖闭包为 61 个文件，聊天页为 34 个，云模型客户端为 14 个。
- 7 个原始纯逻辑模块通过 8 项主机检查，覆盖流式展示、会话切换、持久化节流、
  首页网格、取消、记忆分类、引导与 JSON 文本规范化。
- 已下载 ArkUI-X Windows SDK 6.0.2.118 / API 22，官方 SHA-256 校验通过：
  `b72867f9658a72f0d2fa4d7775344978cab2cc52450e06ccd35995ad0e0e600f`。
- Android SDK、JDK 17 和 PKB110 真机可用；ArkTS 编译链还在准备。
- 旧版官方 `ohsdkmgr` 实际只列出 API 7–9，不能用它宣称已安装 API 22。
- Windows 工作目录已获用户明确授权。两个已有远程 SSH 连接均超时。

## 已识别的适配边界

| 源端依赖 | 验证方向 |
| --- | --- |
| `HomePage.ets` 默认值依赖 `DataManagerFacade` | 将展示模型与数据工厂解耦，避免页面拉入整套存储/模型服务 |
| `FrostedPage.ets`、主导航的 `@kit.UIDesignKit` | 验证标准 ArkUI 外壳；运行时 `canIUse` 不等于编译时可忽略不支持的导入 |
| `ChatPage.ets` 的 `@kit.IMEKit` | 将输入法收起等操作放进平台接口 |
| `CloudModelClient.ets`、`PrivacyConsent.ets` | 保留源端 SSE 解析、取消和隐私撤回；逐方法验证 HTTP 插件 |
| `AppLogger` → `DebugSettings` → `libentry.so` | 拆出日志/诊断边界，不能因为原生日志依赖迫使云聊天链接 MNN |
| 华为账号、CoreVision OCR/主体分割、提醒、跨应用控制 | 核对具体 API，缺口通过原生适配或明确不可用状态处理 |

SDK、参考输入、下载包、构建产物、截图和详细日志位于被忽略的
`.arkui-x-validation/`。未复制源端疑似凭据配置、签名材料或用户数据。
没有实际编译/运行输出前，不将 Android 页面、SSE 或桥接标记为通过。

## 第二阶段：真实编译、Android 运行与共享源码同步

### 实际范围

- 固定上游同一提交，`ui-upstream-lock.json` 锁定 77 个只读 ArkTS 输入的 Git blob。
  `prepare-project.mjs` 先验证所有输入，再在隔离目录生成页面和适配代码。
- 最终基准样本为 53 个 ArkTS 文件：41 个上游文件逐字不变、8 个文件适配、
  4 个自编验证入口/平台接口文件。样本不是完整应用，文件数量不能换算成产品复用率。
- `HomePage.ets` 原样使用；`ChatPage.ets` 改输入法和焦点接口。页面实际渲染，
  没有用截图、HTML 或 Compose 重画。`ChatSessionStorage.ets` 和 `PrivacyConsent.ets` 原样使用。
- 独立 Android 包名 `com.clawmate.reuseprobe`；原应用及用户数据未覆盖。
  首页显示原始空态；聊天数据是显式标注的本地协议夹具，不是实际模型回复。
- 登录配置仍要求登录。先验证未登录会拒绝请求；随后仅对进程内 `127.0.0.1`
  HTTP 夹具临时设置测试会话，结束后恢复未登录并撤回测试隐私授权。未接入真实账号。

### 已确认的适配

| 文件/能力 | 最终处理 |
| --- | --- |
| FrostedPage / HDS | 提取上游已有标准 ArkUI Navigation 分支；系统栏改 Android Activity 设置；未认证 HDS 材质一致 |
| DataManagerFacade | 仅提取上游 4 个空展示模型工厂，避免 UI 样本拉入所有服务；完整数据管理器未迁移 |
| DebugSettings | 保留日志开关，移除本实验不具备的 MNN 诊断入口 |
| HomeComfortSection / ChatPage | 焦点、输入法、键盘高度经 ArkUI-X Bridge 调用 Android；高度轮询为验证用方案 |
| ChatMessageList | 剪贴板写入走 Android 桥接，错误不伪装成成功 |
| GalleryPhotoPicker | 明确抛出“尚未接入 Android 原生适配”；未声明相册选取可用 |
| CloudModelClient | 保留原 HTTP 请求/SSE/工具调用解析；补流式 UTF-8 解码状态及主动取消完成回调 |

首次设备检查中，原解码器在跨包中文处产生替换字符；SDK `@ohos.util.d.ts`
明确 `decodeWithStream` 的 `stream` 默认是 false。实验适配设为 true，并在 dataEnd 刷新。
原 `cancelActiveRequests()` 只销毁传输，Android 上观察到 Promise 超过 3.5 秒未结束；
实验增加每请求取消回调，以“请求已取消”结束并清理监听。未修改上游仓库，也未证明
这两个问题在 HarmonyOS 上同样发生。`--original-transport` 可生成未修正传输的对照版本。

首轮另有验证代码错误：撤回隐私导致请求先结束，验证器未等待偏好保存就重新授权。
已修正验证器等待顺序，`PrivacyConsent.ets` 没有为此修改。

### 工具链与实际结果

选择匹配的 **OpenHarmony API 20 / 6.0.0.47 + ArkUI-X API 20 / 6.0.0.103**，
验证源项目声明的最低兼容 API 子集。先前 API 23 编译器混搭 API 22 ArkUI-X 被官方插件拒绝；
API 22 完整编译链未就绪，因此本次不能称为 API 22 认证。下载地址、版本和实际校验过的
6 个 SDK 压缩包 SHA-256 见 `toolchain-lock.json`。

| 实际命令/操作 | 结果与限制 |
| --- | --- |
| `node experiments/arkui-x/verify-contracts.mjs --source-root .arkui-x-validation/reference` | 7 个原始模块，8 项主机契约通过 |
| `node experiments/arkui-x/prepare-project.mjs` | 77 个输入哈希检查通过；生成 53 个样本文件 |
| Hvigor `assembleHap`，未适配平台 API 的样本 | 失败，识别焦点、键盘、系统栏、相册与剪贴板等跨平台缺口；不计为通过 |
| Hvigor `assembleHap`，传输修正后 | 成功，27.752 秒；仍有上游废弃 API 警告 |
| Gradle `:app:assembleDebug --offline`，传输修正后 | 成功，27 秒；首次离线依赖缺失，联网下载后离线构建通过 |
| `adb install -r` / `am start -W` | PKB110 / Android 16 / API 36 安装成功；进程重启冷启动 660ms |
| 页面按钮运行本地协议夹具 | 最终 12 项全部通过，详见下方；原始失败保留在结果 JSON 中 |
| 本包 `am force-stop` 后重新启动 | 页面重新读取 2 条消息；使用原始文件存储代码 |
| `prepare-project.mjs --simulate-upstream` 后重建并更新 APK | HAP 27.390 秒、Gradle 27 秒均成功；真机 XML 和截图显示同步文字；4 个 Android 原生源文件哈希不变 |
| `git diff --check` / Node 脚本语法检查 | 通过 |

12 项设备检查覆盖：双向平台桥接和 Unicode、剪贴板写入/读取并恢复、原首页布局函数、
未登录拒绝、故意以 11 字节块发送的 SSE 中文/emoji、工具参数跨块拼接、原文件存储往返、
HTTP 429、主动取消、隐私撤回、读取超时。夹具监听在应用进程自己的回环地址，
不连接电脑或外部服务。`ProbeFixture.java` 是测试服务，不能用作生产模型实现。

同步实验仅将**生成副本** `HomePage.ets` 的“今日概览”替换为“今日概览 · 同步验证”，
没有改 Android 页面或鸿蒙参考输入。该实验版本是 40 个不变、9 个修改、4 个新文件，
不能混入上述基准 41/8/4 的统计。当前手机保留带同步标记的实验包。

APK 为 arm64 调试包，包含 129 个 SDK 库/插件文件，约 86 MiB；为定位兼容性使用全量插件，
未做正式依赖裁剪、发布签名、体积或性能验收。原始失败、修正后结果、截图和 APK 的哈希
已记录；二进制、构建日志及截图仍在被忽略的 `.arkui-x-validation/` 中。

### 重建入口

脚本不是 SDK 安装器。需要先准备官方 ACE OpenHarmony 空项目到
`.arkui-x-validation/project`，包名为 `com.clawmate.reuseprobe`；API 20 SDK 放在
`openharmony-sdk/20` 和 `sdk/20/arkui-x`，Node 20.19.5 放在 `node/`。
项目根 build-profile 的 compile/compatible/targetSdkVersion 均为 20，runtimeOS 为
OpenHarmony；`.arkui-x/arkui-x-config.json5` 为 `crossplatform: true, modules: ['entry']`。
根 hvigorfile 导出插件 `AppTasksForArkUIX`，entry 导出 `HapTasks`。
Hvigor / OHOS 插件为 6.22.7，ArkUI-X 插件为 4.22.7；依赖装在忽略目录 `build-tools/`。
需按锁定提交取回 77 个源文件至 `reference/`，并将原 base/dark 的字符串、颜色、尺寸及
首页/聊天依赖媒体资源放进生成项目。工具链、参考文件和资源不自动提交或下载。

以下从仓库根目录运行，环境路径由使用者提供，不能写入应用源码：

```powershell
$env:JAVA_HOME = '<本机 JDK 17>'
$env:ANDROID_HOME = '<本机 Android SDK>'
$env:GRADLE_USER_HOME = "$env:USERPROFILE\.gradle"
$probeRoot = (Resolve-Path .arkui-x-validation).Path
$env:ARKUIX_SDK_HOME = "$probeRoot\sdk"
$env:OpenHarmony_HOME = "$probeRoot\openharmony-sdk"
$env:PATH = "$probeRoot\node\node-v20.19.5-win-x64;$env:JAVA_HOME\bin;$env:PATH"
node experiments/arkui-x/prepare-project.mjs
Push-Location .arkui-x-validation/project
node ../build-tools/node_modules/@ohos/hvigor/bin/hvigor.js --mode module -p module=entry@default -p product=default -p buildMode=debug assembleHap --no-daemon --no-parallel
Pop-Location
node experiments/arkui-x/configure-android.mjs --project .arkui-x-validation/project --android-sdk $env:ANDROID_HOME
python experiments/arkui-x/package-android.py --project .arkui-x-validation/project --sdk .arkui-x-validation/sdk/20/arkui-x
./gradlew.bat -p .arkui-x-validation/project/.arkui-x/android :app:assembleDebug --no-daemon --console=plain
```

安装生成的 APK，普通启动后在“验证”页点击“运行本地夹具检查”。无需端口映射、启动额外参数
或外部模型。报告在本包私有 `files/probe-result.json`。同步实验只给 prepare 命令增加
`--simulate-upstream`，之后重编译、打包、安装即可。

安装期间的手机人机拼图由用户手动完成。自动审批曾拒绝电脑端口映射与自动启动的组合命令，
只返回 `blocked by policy`；随后采用应用内回环夹具、普通启动和页面按钮执行。
未重试被拒组合命令，未建立端口映射，未绕过手机安全验证；旧电脑夹具进程已停止。

### 对产品路线的建议和剩余边界

优先将 ArkTS 页面、展示状态和业务协议整理为共享模块，平台功能集中在接口层，
再扩展图库、工作流和设置主链路。这样共享页面改动可以直接进入 Android 编译，
有望减少长期双端重复维护。现有 Kotlin/Compose 产品暂保留作功能参考和回归基线，
本实验没有作出替换生产前端的变更。

仍需验证真实云服务/鉴权与重试、多会话及完整代理循环、图库/OCR/向量存储、完整数据管理、
工作流执行、华为账号替代、语音/数字分身、后台生命周期、Android 系统权限及发布工具链。
HDC/GUI、Python、C++/MNN 均未连接；照片选择明确不可用。iOS 和任何其他系统未运行，
需独立核对引擎与 API 支持。没有鸿蒙同状态运行截图/录屏，不能宣称像素或动画一致。

官方依据：[SDK 6.0.2 发布说明](https://github.com/arkui-x/docs/blob/master/zh-cn/release-notes/ArkUI-X-v6.0.2-release.md)、
[跨平台 API](https://github.com/arkui-x/docs/blob/master/zh-cn/application-dev/reference/apis/README.md)、
[平台桥接](https://github.com/arkui-x/docs/blob/master/zh-cn/application-dev/quick-start/platform-bridge-introduction.md)。
