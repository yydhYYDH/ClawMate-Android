package com.clawmate.platform.gui

/** Ephemeral exact-app grant. No persisted allowlist or model-controlled methods. */
class GuiControlSession(val target: String, private val started: Long) {
    private var stopped = false
    private var actions = 0
    init { require(target.matches(Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+"))) }
    fun available(now: Long): Boolean = !stopped && now >= started && now-started < 120000 && actions < 20
    fun requireTarget(now: Long, targetPackage: String, unlocked: Boolean, appWindows: Int) {
        check(available(now)) { "控制会话已停止、到期或达到20次动作上限" }
        if (!unlocked || appWindows != 1 || targetPackage != target) stop()
        check(!stopped) { "目标应用已变化、锁屏或处于多窗口；请重新开启会话" }
    }
    fun consume(now: Long, targetPackage: String, unlocked: Boolean, appWindows: Int) {
        requireTarget(now,targetPackage,unlocked,appWindows);actions++
    }
    fun stop() { stopped = true }
    companion object {
        fun mapPoint(x: Float, y: Float, viewWidth: Int, viewHeight: Int, imageWidth: Int, imageHeight: Int): Pair<Float,Float> {
            require(listOf(x,y).all { it.isFinite() } && minOf(viewWidth,viewHeight,imageWidth,imageHeight)>0)
            val scale=minOf(viewWidth.toFloat()/imageWidth,viewHeight.toFloat()/imageHeight)
            val px=(x-(viewWidth-imageWidth*scale)/2)/scale
            val py=(y-(viewHeight-imageHeight*scale)/2)/scale
            require(px>=0 && py>=0 && px<imageWidth && py<imageHeight) { "请点按截图内容，留白区域不可操作" }
            return px to py
        }
    }
}
