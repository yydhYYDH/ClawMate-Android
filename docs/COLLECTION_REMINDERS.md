# 每日采集提醒移植

参考版本为`doulujiyao12/mobiinfra-oh@cd69b68bf16b9ed352be5073939eb24d042de583`。
复用原`CollectionReminderPanel`、`CollectionReminderPolicy`与`TaskPage`；
原[提醒服务](https://github.com/doulujiyao12/mobiinfra-oh/blob/cd69b68bf16b9ed352be5073939eb24d042de583/entry/src/main/ets/utils/CollectionReminderService.ets)
的Harmony通知实现由Android适配替代，参考工程保持只读。

## 状态与系统边界

`android-capabilities`的`CollectionReminderEngine`独占私有提醒偏好，包括时间、开启状态、
计划token、下次到期、实际已发出通知的到期值、已处理日期和最近成功采集时间。
ArkTS服务通过v1 JSON读取快照，沿用原面板类型和策略；不另写一份持久化状态。
首次初始化只迁移旧适配器的小时/分钟，默认关闭；不导入其他包的数据或授权。

共享宿主单独声明通知/开机权限和非导出接收器，Compose基线不新增这些权限。
Android13以上通过系统通知权限对话框授权；未授权或渠道关闭时返回明确错误和未注册状态。
允许权限后需再次打开提醒。应用前台刷新会核对实际权限和已有PendingIntent，
不会把等待系统延迟投递的闹钟直接跳到次日。

使用`setAndAllowWhileIdle(RTC_WAKEUP)`注册单次近似闹钟，每次接收后按本地日历安排下一次。
不申请精确闹钟权限；省电策略可能推迟投递，界面明确提示“设定时间附近”。
开机、包更新、时钟和时区变化触发重新规划及token失效；强行停止应用后仍需用户重新打开。
该实现遵循Android[闹钟约束](https://developer.android.com/develop/background-work/services/alarms)
与[通知权限](https://developer.android.com/develop/ui/compose/notifications/notification-permission)。

## 通知到任务

1. 私有接收器验证当前计划token及确切到期值，安排后续闹钟并发布真实系统通知。
2. 通知使用不可变PendingIntent直接进入共享Activity，锁屏公开内容只提示解锁查看。
3. 宿主再次核对token、实际已发出到期值、48小时有效期和当日未处理；界面须在前台且已解锁。
4. ArkTS任务页显示执行确认。取消不启动任务；从子页面跳转会先提示离开及未保存编辑风险。
5. 确认后先同步提交原生领取收据，随后执行已开启的原有有界任务。重复点击/重建不能再次领取当日请求。

接收器不采集数据、不请求模型、不执行GUI/HDC。任务页仍保留停止与明确的受限能力结果。
领取后进程立即退出可能导致本次任务未执行，但收据仍防止重复；不宣称恰好一次执行或自动重试。
正常批量运行失败后仍可在任务页手动执行。

最近成功采集时间只在`AndroidWorkflow`保存真实非空summary日志后更新。
echo、空摘要、失败和取消不更新；不依据旧文件修改时间推断历史采集成功。
原生状态损坏或存储失败会报错，不生成虚假成功记录。

## 验证范围

新增14项主机检查、4项JVM策略测试与3项真机测试。
真机验证拒绝权限、真实AlarmManager注册/改时/撤销、旧计划拒绝、受控时区接收器恢复，
以及受控触发真实接收器后发布系统通知、通知栏点击进入ArkTS确认、取消不执行和持久化领取防重。
测试结束恢复提醒偏好并撤销临时闹钟/通知；通知授权恢复为关闭，系统保留用户选择标记。

自然调度尝试曾等待约645秒，流程到达确认界面，但测试因OCR“已/己”定位失败；
不能作为独立、可靠的自然调度端到端通过证据。最终使用受控接收器触发及确认按钮定位，
不把该测试算作准点投递验收。真实重启、Doze、省电白名单差异、多厂商与锁屏交互仍待专项测试。
精确命令、失败记录和证据哈希见[验证报告](../shared-frontend/verification-reminders.json)。
