# 共享前端干净源码目录重建

SH-15，2026-10-27。目标是去掉旧生成工程对构建的隐式依赖，不是实现 SDK 自动安装器。
SDK、Node、JDK、Android SDK、会议源码及其 Python/wheel 依赖仍需事先准备。

## 固定输入

- `shared-frontend/template/` 保存本项目的最小构建配置和启动 Ability，不再读取之前实验生成的 `.arkui-x-validation/project/`。
- 两份 ArkTS 锁共同指定 120 个源文件，`resource-lock.json` 另锁定 59 个资源：页面元素/媒体、应用图标和 11 份原版默认工作流。
- 所有输入固定到鸿蒙提交 `cd69b68bf16b9ed352be5073939eb24d042de583`，逐项验证 Git blob。默认服务配置、账号配置、签名、模型、数据库及未列入清单的文件不会复制。
- 原始资源只写入忽略目录。没有改动鸿蒙参考工程；默认工作流的 GUI/HDC 步骤继续由现有 Android 能力边界阻断，不会因资源补齐而自动获得执行权限。
- 准备/编译摘要现在覆盖作者模板和固定资源，资源或模板变更同样要求重编。不会把旧运行库当作新构建结果。
- SDK/HAP导出先验证并写入临时目录，再替换受控的assets/libs，清除已删除资源和旧库；拒绝越界或带链接的目标、非法ZIP路径及SDK内同名不同内容的库。导出中断需重建，不宣称跨目录掉电事务。

## Windows

准备完整的只读源目录或仅含上述清单的字节一致导出。Windows Git 换行转换会改变 Git blob；新克隆需关闭 `core.autocrlf`，或用 `git archive` 导出固定提交。不要在已有只读鸿蒙工程中切分支或改文件。

工具版本见 `experiments/arkui-x/toolchain-lock.json`：OpenHarmony 6.0.0.47 / API20 Release、ArkUI-X 6.0.0.103 / API20 Release、Hvigor/OHOS 6.22.7、ArkUI-X 插件 4.22.7、构建 Node 20.19.5。SDK 参数应指向包含 `20/` 的父目录；BuildTools 包含 `node_modules/@ohos/`，BuildNode 指向实际可执行文件。

```powershell
./shared-frontend/build.ps1 -JavaHome '<JDK17>' -AndroidSdk '<Android SDK>' `
  -MeetingSdkRoot '<会议源码>' -SourceRoot '<固定版本的只读源目录>' `
  -ArkuiXSdk '<ArkUI-X SDK根>' -OpenHarmonySdk '<OpenHarmony SDK根>' `
  -BuildTools '<Hvigor安装根>' -BuildNode '<Node20/node.exe>' -Offline
```

首次调用验证全部源和目标后才复制白名单文件；现存参考文件若不同则保留并报错。不会自动覆盖旧参考版本。
工具路径只保存在忽略文件 `.arkui-x-validation/toolchain.local.json`，后续可省略四个工具参数和 SourceRoot。
`-Offline` 只约束 Gradle，不能据此声称首次 pip/wheel 安装或整个流程可以断网运行。

也可先独立检查/导入：

```powershell
node shared-frontend/bootstrap.mjs --source-root '<只读源>' --arkuix-sdk '<SDK根>' --openharmony-sdk '<SDK根>' --build-tools '<Hvigor根>' --build-node '<Node20/node.exe>'
node shared-frontend/verify-bootstrap.mjs
node shared-frontend/verify-maintenance.mjs --prepare-twice
python -B shared-frontend/verify-runtime-export.py
```

主机语义检查仍用 Node24；实际 ArkTS 编译使用显式指定的 Node20。

## Linux

`scripts/build-shared.sh` 现在调用同一套 bootstrap、prepare、Hvigor、导出、seal 和 Gradle 阶段。
它不再事后改写已经记录摘要的配置或整目录复制鸿蒙资源；SDK版本不符时明确拒绝。

```bash
source scripts/arkui-env.sh
MEETING_SDK_ROOT='<会议源码>' scripts/build-shared.sh --upstream '<只读源>' --node '<Node20>' --offline
```

本阶段 Windows 干净目录实际重建和 Linux 脚本语法检查分别记录；未运行 Linux 端到端构建。

## 真机回归

主包构建后运行 `:shared-app:assembleDebugAndroidTest`，解锁一台已授权的测试手机，再运行：

```powershell
./scripts/test-shared-device.ps1 -Adb '<Android SDK/platform-tools/adb.exe>' -EvidenceName '<新的小写日志名称>'
```

脚本安装共享包与测试包，原生/会议、聊天、任务三个组分别停止并重启目标进程。直接写测试偏好的夹具必须隔离，不能沿用前一组的ArkTS单例设置；结束后也停止目标进程，丢弃夹具的内存缓存。脚本同时检查ADB结果和JUnit文本，不能把ADB退出0视作测试通过。
任务夹具只为已观察到的PKB110蓝色“发送图片”→“发迭图片”OCR误识别加了精确别名，并支持对话框同一行内的文字片段；仍须真实点击并校验两份失败运行记录。该驱动适配不代表产品TalkBack/条件节点问题已经解决。

## 本阶段验证的含义

干净目录只复制受版本管理的源码和本阶段新增作者文件，不带旧 `.arkui-x-validation`、APK、运行库、Gradle项目状态或手工模板；再用外部已安装的工具和当前机器依赖缓存重建。
这验证干净源码重建，不代表新机器安装、全流程离线或 APK 位级可重复。更换 SDK/上游版本、真实服务及16KB设备仍需独立演练。
精确命令、结果和证据见 `shared-frontend/verification-bootstrap.json` 与 `MIGRATION_STATUS.md`。
