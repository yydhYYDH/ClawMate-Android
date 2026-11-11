# Android 后台屏试验

后台屏能力位于 `:android-capabilities`。ClawMate 通过 Binder 向同 APK 的 Android shell 助手交付已确认的应用入口，取得本次会话的显示器 ID。辅助进程负责创建可信虚拟屏、在指定显示器启动应用和释放显示器。操作方按显示器 ID 读取无障碍窗口。调用需要同一应用 UID，释放应用会话或应用进程退出时回收显示器。显示器另有 125 秒的回收时限。

目前接入的业务用例是小红书公开搜索。小红书页面识别、模型可选动作和摘录保存属于工作流层。后台屏接口也接受目标应用的启动入口，可由其他预设或聊天入口在按次确认后复用。聊天提示词转任务尚待接入。

## 开发设备启动

使用 Android 14 及以上的解锁设备。安装 ClawMate 测试包，在手机上按系统提示授权 USB 调试。电脑使用 SDK 自带的 `adb`，设备列表只保留本次测试设备。下面使用共享版应用 ID，独立测试包请改为实际包名。

```powershell
$package = 'com.clawmate.shared'
$apk = ((adb shell pm path $package | Select-Object -First 1) -replace '^package:', '').Trim()
adb shell "CLASSPATH=$apk app_process /system/bin com.clawmate.platform.display.DisplayHostMain $package"
```

终端显示 `ClawMate background display host connected` 后，在 ClawMate 内开启按次界面控制，运行小红书公开笔记任务，在确认页选「在后台屏采集」。状态浮窗显示已读页数和条数，并提供「停止采集」。预览确认后写入现有本机采集记录。结束终端中的助手后可按需重新连接。当前试验通过已授权的调试连接启动，应用内的无线配对入口待接入。

## 设备验证

`BackgroundDisplayDeviceTest` 支持 `backgroundDisplayProbe=fixture` 和 `backgroundDisplayProbe=xhs`。启动 instrumentation 后再连接同包名的助手，因为 instrumentation 启动时会重启测试应用进程。使用自建夹具检查指定显示器上的窗口读取、滚动、主屏焦点及释放。小红书检查公开搜索窗口与焦点，模型决策、浮窗和保存流程另外验收。

实现参考 Mika 对 Android 可信显示器与 shell 进程的使用。系统对可信屏权限和指定显示器启动应用的检查仍由 Android 执行。 [Mika 后台屏架构](https://github.com/wangzan101/Mika/blob/f8f4be0/docs/dev/architecture.md) · [Android 多屏启动规则](https://source.android.com/docs/core/display/multi_display/activity-launch)
