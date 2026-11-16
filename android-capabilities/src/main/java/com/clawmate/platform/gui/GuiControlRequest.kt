package com.clawmate.platform.gui

import android.content.Intent

/** An app-owned launch with a single target and a known set of collection actions. */
internal class GuiControlRequest(
    val label: String,
    launch: Intent,
    val task: GuiControlTask,
) {
    val launch = Intent(launch)
    val target: String = requireNotNull(this.launch.component?.packageName ?: this.launch.`package`) {
        "手机控制需要指定应用"
    }
    val supportsBackground: Boolean
        get() = task is GuiControlTask.Weibo || task is GuiControlTask.Xhs || task is GuiControlTask.Mika
}

internal sealed interface GuiControlTask {
    data object ManualControl : GuiControlTask
    data object ManualCollection : GuiControlTask
    data class Pages(val run: PageGuiRun) : GuiControlTask
    data class Weibo(val run: WeiboGuiRun) : GuiControlTask
    data class Xhs(val run: XhsGuiRun) : GuiControlTask
    data class Mika(val run: MikaPhoneRun) : GuiControlTask
}
