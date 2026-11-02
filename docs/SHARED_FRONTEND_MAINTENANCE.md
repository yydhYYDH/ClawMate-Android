# 共享前端长期维护评估

2026-10-26，SH-10。结论：继续采用 ArkTS/ArkUI + ArkUI-X、Kotlin 平台适配的路线，
保留 Compose 回归基线；当前证据支持继续开发，生产替换仍有验收条件。
本轮重点是构建与升级时能否及时发现问题，未增加新的业务入口。

2026-10-27 更新：用户决定先完成技术路线闭环，再讨论是否递交 PR；上游共享整合 PR 已关闭，开发仅在 fork 的 `codex/shared-frontend` 继续。SH-15 的干净源码目录引导见[重建说明](SHARED_BOOTSTRAP.md)。下文 SH-10 的数量和结果属于当时历史。

## 本轮改进

- 两层源码准备脚本的文本替换统一校验：没有命中即失败；单次替换出现多个候选也失败。
  全量替换显式声明，可指定预期数量。版本锁继续校验上游 Git blob。
- 每次准备先清理忽略目录内上一轮生成的 ArkTS overlay，防止删除适配后旧文件继续覆盖上游。
  清理拒绝越界路径和符号链接，不删除源码目录。
- `prepared-state.json` 对作者源码、锁定参考输入、模板、资源与最终编译输入记录 SHA256。
  ArkTS 编译与运行库导出后生成 `compiled-state.json`，绑定这些输入与运行库内容。
  Android `preBuild` 强制检查，直接运行 Gradle 时也拒绝使用陈旧输入或运行库。
- Compose 和共用能力的 Gradle 配置不再强制依赖会议源码目录；共享宿主构建缺少该依赖时明确失败。
- APK 审计覆盖 `lib/` 以及 Chaquopy `.imy` 内嵌 Python 扩展，报告体积、来源匹配与每个 ELF
  PT_LOAD 段的 16 KB 对齐。`--require-16kb` 将不兼容结果作为失败返回，供发布检查使用。

源码准备摘要是文件统计，不是复用代码行数；SH-09 后当前为139个 ArkTS 编译输入，
78个原样复用、35个适配、26个新文件。上游升级仍需审查补丁语义，匹配成功不代表业务语义不变。
准备可重复不等于 APK 位级可重复；本轮也未宣称跨版本 SDK 升级成功。

## 复测

先按 [构建说明](../shared-frontend/README.md) 配置 SDK/JDK/会议源码路径。
主机语义检查使用 Node24；实际构建仍使用锁定的 Node20。

```powershell
node shared-frontend/verify-maintenance.mjs --prepare-twice
python -B shared-frontend/verify-apk-audit.py
./shared-frontend/build.ps1 -JavaHome '<JDK17>' -AndroidSdk '<Android SDK>' -MeetingSdkRoot '<会议仓库>' -Offline
node shared-frontend/build-state.mjs verify
python shared-frontend/audit-apk.py shared-app/build/outputs/apk/debug/shared-app-debug.apk --runtime-root .arkui-x-validation/application/runtime --output .arkui-x-validation/application/apk-audit.json --require-16kb
```

`--prepare-twice` 在忽略目录注入一个旧的引导页 overlay，重新生成后应清除该文件，
两次全部输入/输出摘要必须一致。独立临时工程还验证源码、资源、生成文件和二进制变化均能触发失败。
测试不修改锁定参考源码。不要在源码生成、编译或打包期间并行运行另一轮 prepare。
`-Offline` 只限制 Gradle 依赖解析；会议 SDK 的 Python 安装器有自己的缓存和联网行为，
当前不构成全流程断网构建保证。

## 维护判断与生产条件

| 维度 | 判断与下一步 |
| --- | --- |
| 页面与业务共享 | 现有聊天、记忆、图库、工作流和会议入口已经使用共享前端。继续把厂商/系统差异收敛到 Kotlin 接口 |
| 上游漂移 | 本轮补齐失败检测与生成残留防护；仍需用一次真实上游变更和一次 SDK 升级演练验证升级成本 |
| 工具链 | API20 锁定组合能编译；首次环境依赖忽略目录内模板、SDK和只读输入，尚缺新机器完整引导验证 |
| 无障碍 | 已知 ArkUI-X 条件布局可能保留过期节点；OCR辅助设备测试不能替代 TalkBack 验收，是生产前必须解决的风险 |
| 原生依赖 | 当前包含会议/Python/推理库；16 KB 静态失败须修复后在真实16 KB环境复测，不能用4 KB手机代替 |
| 体积与性能 | 当前全插件 ARM64 调试包不适合作为发布体积目标；先按能力裁剪/拆分，再测release包、首屏可交互、内存和耗电 |
| 平台覆盖 | 现有真机仅 OPPO Android16；需增加其他厂商与较旧Android。iOS并未验收 |
| 产品与数据 | 真实云服务、完整私人图库授权、正式签名及版本化旧数据导入/校验/回退仍需独立验收 |

## 技术路线闭环的跟踪范围

讨论是否提交整合 PR 前，需有可复核证据回答：干净源码是否可重建、核心共享流程是否真实可用、取消/失败/重启是否可恢复、固定上游和SDK的维护成本是否可接受、原生依赖和无障碍是否有可执行的解决方案。
这不要求同时移植所有可选集成，也不替代正式包切换和发布验收。当前优先项是输入/工具链引导、真实上游/SDK升级演练、16KB依赖阻塞以及真实服务与多设备验证；不能仅凭构建成功宣布路线闭合。

[Android官方指南](https://developer.android.com/guide/practices/page-sizes)要求检查所有原生依赖的
ELF 对齐、APK ZIP 对齐和16 KB环境运行。本轮静态工具只覆盖第一项；不会把单项通过当作发布认证。
[ArkUI-X官方列出的多平台支持](https://github.com/arkui-x/docs/blob/master/en/ArkUI-X-Overview.md)
也不代替本项目的实际平台验收。

精确命令、测量结果和限制见 [SH-10证据](../shared-frontend/verification-maintenance.json)，
阶段结论统一写入 [迁移状态](../MIGRATION_STATUS.md)。
