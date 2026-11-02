# 工作流视觉问答

参考`mobiinfra-oh@cd69b68bf16b9ed352be5073939eb24d042de583`的
`WorkflowTypes.ets`、`TaskPage.ets`、`WorkflowStorage.ets`及
[WorkflowRunner.runTool](https://github.com/doulujiyao12/mobiinfra-oh/blob/cd69b68bf16b9ed352be5073939eb24d042de583/entry/src/main/ets/utils/WorkflowRunner.ets)
（blob `7a76bf0fd0bfe0633aa7306dd438ff3648209444`）。原工程保持只读。

## 使用

在“我的”配置HTTPS Decider服务、视觉模型名和密钥，并授权云服务。
在工作流编辑器添加`tool`节点、工具名`vlm_qa`；选择`answer`或`summary`，
填写问题，按“选择本机图片”将系统选中的图片重编码为去元数据JPEG并保存到共享包私有目录。
保存工作流后运行。每个模型步骤都会再次显示目标地址、模型、图片引用和完整问题，确认才上传。
问题可引用原`context`和之前的`steps.<id>.output`；循环问题支持`loop.index/last`。

`answer`的实际回答进入运行记录的步骤`output.response`，不会直接写采集日志。
`summary`仅在整个工作流成功时由ArkTS写入采集日志；后续步骤阻断、失败或取消不写成功日志。
返回文本和`tool_name/mode/question/structured_output`可供后续步骤引用。
模型结果以`android_workflow_model`记录来源；同一天后续追加确定性结果也保留已有模型来源标记。

## 职责

- 共用Kotlin执行器继续负责解析、顺序、条件/循环、步数和60秒总预算。
  `WorkflowModelTool`是可选接口；Compose基线不注入，仍返回NOT_CONFIGURED。
- 共享Android宿主为每次运行建立单请求邮箱。随机请求ID、所属运行ID、单次回复、结束/超时清理
  防止重复分发或迟到结果串入下一次运行；取消会结束等待，不占用Android界面线程。
- ArkTS `WorkflowVlm`复用现有云客户端、Decider设置、隐私授权与本地图片准备。
  原生不持有云凭据、不新增网络客户端、不写第二份工作流/日志数据。
- 发送前与结果提交前核对授权、配置和前台解锁状态；网络期间轮询门控，失去条件时取消请求。
  模型输出仅作为数据，不转成GUI命令或递归展开变量。

## 兼容与边界

- 保留`vlm_qa`、`answer/summary`、`question/image/json_schema`输入契约。
  图片仅接受应用私有`file://`引用；系统选图由编辑器显式导入。
  原始Base64、远程图片URL及其他包私有文件不接受，未解析变量明确失败。
- `json_schema`作为有界JSON输出要求传给模型；返回值须严格为JSON对象。
  当前未实现完整JSON Schema验证或鸿蒙的字段别名/类型归一化，不能将此描述为schema认证。
- Android按顺序等待模型；不复刻`blocking:false`的后台并行摘要/滑动队列或自动重试。
  总预算包含模型确认和准备时间，超时需要重新运行；不保证任何模型都可在预算内完成。
- 运行目录和成功日志由ArkTS在收尾时创建；模型步骤不提前提供原端`step_dir/daily_log_path`。
  引用不存在的输出会明确失败，不生成虚假路径。
- 原GUI截图、HDC、VLM规划循环仍受限，默认含这些节点的任务会明确阻断；
  本项支持用户配置的本机图片问答，不代表自动微信采集已接通。
- 图片导入不上传；放弃编辑后已经完成的私有图片副本目前保留，未做引用追踪与自动垃圾回收。
- 真实云模型质量与多厂商生命周期另需验收；受控回复和`.invalid`失败场景不算真实模型成功。

实际验证、失败修正和产物哈希见本阶段验证报告与[MIGRATION_STATUS](../MIGRATION_STATUS.md)。
