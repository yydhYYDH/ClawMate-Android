# Compose 基线：鸿蒙 6 前端对齐历史

**适用范围：截至2026-10-25的 `app/` Compose设计对照。** 共享版以[共享页面规范](docs/FRONTEND_PARITY.md)和[迁移状态](MIGRATION_STATUS.md)为准。没有双端同状态截图/录屏，像素与原生动画尚未完成验收。

## 对照基准

- 用户选择：鸿蒙 6 / 较新系统的悬浮胶囊底栏。
- 源码：`doulujiyao12/mobiinfra-oh` / `clawmate_dev` / `cd69b68bf16b9ed352be5073939eb24d042de583`。
- 参考路径前缀：`entry/src/main/ets/`；资源来自 `entry/src/main/resources/`。
- Android 为真实应用，继续使用原有数据和回调。空数据、未配置、错误不能当成成功结果。
- 本阶段优先前端；不连接新后端、不执行 MNN 推理、不提交上游 PR。

## 验收含义

“源码对齐”表示布局参数、素材和状态逻辑已根据该提交实现；“手机验证”只表示 Android 真机实际观察结果。只有取得同状态、同尺寸的鸿蒙截图/录屏并比较后，才能宣称视觉或动画完全一致。目前没有鸿蒙运行截图/录屏，不能把代码检查称为像素级验收。

鸿蒙 HDS 的 `EXQUISITE/IMMERSIVE` 材质、渐变模糊与系统动画曲线是系统实现，应用源码不含其全部参数。Android 12+ 使用 GPU RenderNode/RenderEffect 对页面背景模糊，前景控件保持清晰；旧 Android 保留半透明表面。两者的系统字体栅格化、阴影和原生弹窗仍需逐项对照。

## 页面及状态清单

| 范围 | 源码依据 | 必须核对的内容 | 当前状态 |
|---|---|---|---|
| 共用颜色 | `utils/AppTheme.ets`、base/dark `color.json` | 文字、背景、边框、8 类强调色、系统深色切换 | 已导入资源和颜色映射；验证中 |
| 顶栏 | `components/AppHeader.ets` | 56 高、居中标题 20/28、左右 20、头像 40/34、返回/操作/PC 指示器 | 已实现；验证中 |
| 毛玻璃页面 | `components/FrostedPage.ets` | 固定顶栏、滚动穿透、24 模糊、32 渐隐、安全区 | Android 适配；HDS 材质待实机对照 |
| 悬浮底栏 | `pages/main/FloatingMainTabs.ets`、`MainTabConfig.ets` | 64 高、左右 20、底部 12、88 内容避让、五项图标/选中状态 | 已实现；系统选择动画为 Android 近似，待对照 |
| 首页概览 | `pages/home/HomePage.ets:159` | 原始背景、待处理数、完整度、四领域、头像入口、宽屏分支 | 已重建；验证中 |
| 为你提醒 | `pages/home/HomePage.ets:1099` | 连续列表、空态猫图、过滤、分页、详情覆盖层 | 源码布局及真实回调已接入，真机通过；系统面板动效待对照 |
| 猜你喜欢 | `pages/home/HomePage.ets:1755` | 三列任务建议、刷新按钮、加载/错误、任务详情 | 已重建并通过真机测试；未接入执行能力明确报错 |
| 帮你解闷 | `pages/home/HomeComfortSection.ets` | 话题列表、空态、全屏聊天覆盖层 | 已重建并通过真机测试；真实后端响应未验收 |
| 汇总 | `pages/collection/CollectionPage.ets` | 下划线标签、足迹概览、时间线、分页、两行入口 | 源码布局已重建并通过真机测试；详情内部继续对齐 |
| 聊天 | `pages/chat/ChatPage.ets`、`ChatMessageList.ets`、`ChatSessionDrawer.ets` | 空态、模式标签、消息、工具状态、底部输入、键盘、会话抽屉 | 欢迎/输入/抽屉/气泡已重建并验证；深度思考参数与精细工具模块尚未接入 |
| 任务 | `pages/task/TaskPage.ets` | 执行概览、设备控制、提醒、任务列表、配置/编辑/文件管理 | 执行页、新建、图编排/JSON、常用属性、只读文件管理已重建并真机验证；快捷联系人/整任务复制删除/文件清理暂停 |
| 我的 | `pages/settings/SettingsCenterPage.ets` | 账号、Debug、桌面/HDC 入口、五组设置、详情表单 | 主列表、表单尺寸及保存交互已重建并验证；HMS/HDC 为明确受限适配 |
| 图库及详情 | `pages/gallery/`、`pages/collection/CollectionGroupDetailPage.ets` | 分类、分析状态、图像预览、分组详情、返回 | 主界面与图片/事件详情弹层已重建并真机验证；索引缺少来源图时明确提示，月度云结果继续对齐 |
| 引导与隐私 | `pages/onboarding/`、相关隐私页 | 分页、选择、按钮、许可和确认弹窗 | 首次四页引导已对齐并真机验证；隐私/许可二级面板继续对齐 |
| 数字分身 | `pages/chat/DigitalAvatarView.ets`、首页分身覆盖层 | 呼吸 760ms、按压/阴影、生成/历史/版本 | 猫动画及首页生成/历史/版本弹层已实现并真机验证；照片参考生成与历史完整快照受数据层限制 |

## 素材来源

`home_overview_background*.png`、`home_category_icons.png`、`home_explorer_mascot.png` 原样取自上述参考提交；`tab_mine.xml` 为同名 SVG 路径的 VectorDrawable 转换。分类图标沿用源码 362px 图集切片和视觉中心偏移。没有读取或复制源项目配置、凭据或设备数据。


验证记录统一见 [迁移日志](MIGRATION_STATUS.md)，本文件仅保留Compose历史设计对照。
