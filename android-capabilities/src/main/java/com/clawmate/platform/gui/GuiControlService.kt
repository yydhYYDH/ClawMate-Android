package com.clawmate.platform.gui

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.app.AlertDialog
import android.app.KeyguardManager
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.WindowInsets
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityWindowInfo
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import java.security.MessageDigest

/** User-operated exact-app session. Shared/model code has no action-dispatch API. */
class GuiControlService : AccessibilityService() {
    companion object {
        @Volatile var connected: GuiControlService? = null
            private set
        @Volatile var lastStatus: String = "尚未开启控制会话"
            private set
    }
    private val handler=Handler(Looper.getMainLooper())
    private var grant: GuiControlSession?=null
    private var panel: LinearLayout?=null
    private var dialog: AlertDialog?=null
    private var preview: Bitmap?=null
    private val overlayOwnership=GuiOverlayOwnership()
    private var busy=false
    private var epoch=0
    private var armedUntil=0L
    private var captureAt=0L
    private var targetLabel=""
    private val watchdog=object:Runnable {
        override fun run() {
            if(grant==null)return
            try {
                check(grant!!.available(SystemClock.elapsedRealtime())) { "控制会话已到期或达到动作上限" }
                check(!getSystemService(KeyguardManager::class.java).isKeyguardLocked) { "手机已锁屏，控制已停止" }
                if(panel!=null) currentWindow() else if(SystemClock.elapsedRealtime()>armedUntil) error("目标应用未进入前台")
            } catch(failure:Exception){stop(failure.message ?: "控制会话已停止");return}
            handler.postDelayed(this,250)
        }
    }
    override fun onServiceConnected(){connected=this;lastStatus="服务已连接；尚未授权任何应用"}
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if(grant==null)return
        handler.post {
            if(grant!=null && panel==null) {
                // Launch events can still describe the host or an OEM confirmation.
                // Wait for the exact target within the existing watchdog grace period;
                // do not consume/revoke a grant by validating the previous window.
                val appWindows = windows.filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
                val targetPackage = appWindows.singleOrNull()?.root?.packageName?.toString()
                if (targetPackage != grant?.target) return@post
                try { currentWindow();showPanel() } catch(_:Exception) { /* short launch grace only */ }
            }
        }
    }
    override fun onInterrupt(){stop("系统中断控制会话")}
    override fun onUnbind(intent:Intent?):Boolean{stop("无障碍服务已断开");if(connected===this)connected=null;return super.onUnbind(intent)}
    override fun onDestroy(){stop("无障碍服务已销毁");if(connected===this)connected=null;super.onDestroy()}
    fun begin(target:String,label:String,launch:Intent) {
        check(Build.VERSION.SDK_INT>=34) { "跨应用窗口截图需要Android 14及以上" }
        stop("正在开启新会话")
        check(!getSystemService(KeyguardManager::class.java).isKeyguardLocked) { "请先解锁手机" }
        grant=GuiControlSession(target,SystemClock.elapsedRealtime());targetLabel=label.take(40);armedUntil=SystemClock.elapsedRealtime()+5000
        lastStatus="等待目标应用进入前台：$targetLabel"
        try{startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));handler.post(watchdog)}catch(failure:Exception){stop("无法打开目标应用");throw failure}
    }
    fun stop(reason:String="用户停止控制会话") {
        val active=grant!=null
        overlayOwnership.retain(emptySet())
        epoch++;grant?.stop();grant=null;handler.removeCallbacks(watchdog);busy=false
        dialog?.dismiss();dialog=null;preview?.recycle();preview=null
        panel?.let{runCatching{getSystemService(WindowManager::class.java).removeView(it)}};panel=null
        lastStatus=reason
        if(active)android.widget.Toast.makeText(this,reason,android.widget.Toast.LENGTH_LONG).show()
    }
    private fun currentWindow():AccessibilityWindowInfo {
        val visibleWindows=windows
        overlayOwnership.retain(visibleWindows.filter{it.type==AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY}.map{it.id}.toSet())
        val apps=visibleWindows.filter{it.type==AccessibilityWindowInfo.TYPE_APPLICATION}
        val window=apps.singleOrNull() ?: error("多窗口或目标窗口不可见，控制已停止")
        val root=window.root ?: error("无法识别目标应用窗口")
        val pkg=root.packageName?.toString().orEmpty()
        grant?.requireTarget(SystemClock.elapsedRealtime(),pkg,!getSystemService(KeyguardManager::class.java).isKeyguardLocked,apps.size) ?: error("会话已结束")
        check(window.displayId==0) { "仅允许主屏幕操作" }
        val safe=safeBounds(Rect().also{window.getBoundsInScreen(it)})
        for(other in visibleWindows) {
            if(other.id==window.id)continue
            if(other.type==AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY &&
                overlayOwnership.isOwned(other.id,other.root?.packageName?.toString(),packageName))continue
            val covered=Rect().also{other.getBoundsInScreen(it)}
            check(!Rect.intersects(safe,covered)) { "输入法、系统面板或其他浮层遮挡目标，控制已停止" }
        }
        return window
    }
    private fun safeBounds(window:Rect):Rect {
        val metrics=getSystemService(WindowManager::class.java).currentWindowMetrics
        val insets=metrics.windowInsets.getInsetsIgnoringVisibility(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
        val safe=Rect(metrics.bounds).apply{left+=insets.left;top+=insets.top;right-=insets.right;bottom-=insets.bottom}
        check(safe.intersect(window) && !safe.isEmpty) { "无法取得安全的应用内容区域" };return safe
    }
    private fun overlayDialog(builder:AlertDialog.Builder):AlertDialog {
        val value=builder.create();value.window?.setType(WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY)
        value.setOnCancelListener{busy=false};dialog=value;value.show();return value
    }
    private fun showPanel() {
        if(panel!=null)return
        val layout=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(8,8,8,8);setBackgroundColor(0xee18304f.toInt())}
        layout.addView(TextView(this).apply{text="ClawMate · $targetLabel\n120秒 / 最多20次动作";setTextColor(-1);textSize=11f})
        fun button(label:String,action:()->Unit){layout.addView(Button(this).apply{text=label;textSize=12f;minHeight=0;setOnClickListener{if(!busy)runCatching{currentWindow();action()}.onFailure{stop(it.message ?: "目标应用已变化")}}})}
        button("截图 / 点按"){capturePreview()}
        button("向上滑动"){confirmGesture("向上滑动",null)}
        button("向下滑动"){confirmGesture("向下滑动",null)}
        button("返回"){confirmGesture("返回",null)}
        layout.addView(Button(this).apply{text="停止控制";setOnClickListener{stop()}})
        val params=WindowManager.LayoutParams(WindowManager.LayoutParams.WRAP_CONTENT,WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,android.graphics.PixelFormat.TRANSLUCENT)
        params.gravity=Gravity.TOP or Gravity.END;params.x=8;params.y=80
        params.width=minOf((150*resources.displayMetrics.density).toInt(),resources.displayMetrics.widthPixels/3)
        getSystemService(WindowManager::class.java).addView(layout,params);panel=layout;lastStatus="控制会话进行中：$targetLabel；全部操作由用户逐次确认"
    }
    private fun screenshot(receive:(Bitmap,Rect,Int)->Unit) {
        check(Build.VERSION.SDK_INT>=34)
        check(SystemClock.elapsedRealtime()-captureAt>=1000) { "截图过于频繁，请稍后重试" }
        captureAt=SystemClock.elapsedRealtime()
        val window=currentWindow();val bounds=Rect().also{window.getBoundsInScreen(it)};val turn=epoch;val windowId=window.id
        check(bounds.width()>0 && bounds.height()>0 && bounds.width().toLong()*bounds.height()<=8000000)
        takeScreenshotOfWindow(windowId,mainExecutor,object:TakeScreenshotCallback {
            override fun onSuccess(result:ScreenshotResult) {
                var image:Bitmap?=null
                try {
                    val buffer=result.hardwareBuffer
                    try{val hardware=Bitmap.wrapHardwareBuffer(buffer,result.colorSpace) ?: error("截图内容不可读");try{image=hardware.copy(Bitmap.Config.ARGB_8888,false)}finally{hardware.recycle()}}finally{buffer.close()}
                    check(turn==epoch && currentWindow().id==windowId) { "截图期间窗口已变化" }
                    val latest=Rect().also{currentWindow().getBoundsInScreen(it)}
                    check(latest==bounds && image!!.width==bounds.width() && image!!.height==bounds.height()) { "窗口尺寸已变化或坐标不匹配" }
                    receive(checkNotNull(image),bounds,windowId);image=null
                }catch(failure:Exception){if(turn==epoch)stop(failure.message ?: "截图失败，控制已停止")}
                finally{image?.recycle()}
            }
            override fun onFailure(errorCode:Int){if(turn==epoch)stop("窗口截图被系统拒绝（$errorCode），请检查是否为受保护页面")}
        })
    }
    private fun fingerprint(bitmap:Bitmap):String {
        val digest=MessageDigest.getInstance("SHA-256");val row=IntArray(bitmap.width);val bytes=ByteArray(bitmap.width*4)
        for(y in 0 until bitmap.height){bitmap.getPixels(row,0,bitmap.width,0,y,bitmap.width,1)
            row.forEachIndexed{x,pixel->for(i in 0..3)bytes[x*4+i]=(pixel ushr (i*8)).toByte()};digest.update(bytes)}
        return digest.digest().joinToString(""){"%02x".format(it)}
    }
    private fun capturePreview() {
        busy=true
        screenshot { image,bounds,_ ->
            val originalHash=fingerprint(image)
            preview?.recycle();preview=image
            val scale=minOf(1.0,1280.0/maxOf(image.width,image.height))
            val display=Bitmap.createScaledBitmap(image,maxOf(1,(image.width*scale).toInt()),maxOf(1,(image.height*scale).toInt()),true)
            val view=ImageView(this).apply{setImageBitmap(display);adjustViewBounds=true;scaleType=ImageView.ScaleType.FIT_CENTER;minimumHeight=300;contentDescription="跨应用本机截图预览"}
            val dialogValue=overlayDialog(AlertDialog.Builder(this).setTitle("本机截图：$targetLabel").setMessage("截图不上传、不保存。可点按预览选择位置，再逐次确认。")
                .setView(view).setNegativeButton("关闭"){_,_->busy=false})
            dialogValue.setOnDismissListener{if(display!==image && !display.isRecycled)display.recycle();if(preview===image){preview=null;if(!image.isRecycled)image.recycle()};busy=false}
            view.setOnTouchListener { v,event ->
                if(event.action==MotionEvent.ACTION_UP) {
                    runCatching {
                        val point=GuiControlSession.mapPoint(event.x,event.y,v.width,v.height,image.width,image.height)
                        val click=point.first+bounds.left to point.second+bounds.top
                        val turn=epoch;dialogValue.dismiss();busy=true
                        handler.postDelayed({if(turn==epoch)runCatching{confirmGesture("点按 (${click.first.toInt()}, ${click.second.toInt()})",click,originalHash)}.onFailure{stop(it.message ?: "点按已取消")}},1100)
                    }.onFailure{android.widget.Toast.makeText(this,it.message ?: "请选择截图中的位置",android.widget.Toast.LENGTH_SHORT).show()}
                };true
            }
        }
    }
    private fun confirmGesture(action:String,point:Pair<Float,Float>?,expected:String?=null) {
        busy=true
        screenshot { image,bounds,windowId ->
            val hash=fingerprint(image);image.recycle();val turn=epoch
            if(expected!=null && hash!=expected){stop("预览后画面已变化，未执行点按；请重新开启会话");return@screenshot}
            overlayDialog(AlertDialog.Builder(this).setTitle("确认本次操作？").setMessage("仅对 $targetLabel 执行：$action。请确认当前页面和位置。切换应用或画面变化将拒绝执行。")
                .setNegativeButton("取消"){_,_->busy=false}.setPositiveButton("确认执行"){_,_->
                    // Allow screenshot interval to elapse; never reuse a confirmation
                    // after a session/window change. The fresh image excludes overlays.
                    handler.postDelayed({
                        if(turn!=epoch)return@postDelayed
                        runCatching { screenshot freshShot@ { fresh,current,id ->
                            val matches=id==windowId && current==bounds && fingerprint(fresh)==hash;fresh.recycle()
                            if(!matches){stop("确认期间画面已变化，未执行动作；请重新开启会话");return@freshShot}
                            dispatch(action,point,bounds)
                        }}.onFailure{stop(it.message ?: "操作已取消")}
                    },1100)
                })
        }
    }
    private fun dispatch(action:String,point:Pair<Float,Float>?,bounds:Rect) {
        val window=currentWindow();val current=Rect().also{window.getBoundsInScreen(it)};check(current==bounds)
        val safe=safeBounds(bounds)
        val session=checkNotNull(grant)
        if(action=="返回"){session.consume(SystemClock.elapsedRealtime(),session.target,true,1);check(performGlobalAction(GLOBAL_ACTION_BACK)){"系统拒绝返回"};busy=false;lastStatus="已请求返回；不代表业务任务成功";return}
        val overlay=Rect();panel?.let{val position=IntArray(2);it.getLocationOnScreen(position);overlay.set(position[0],position[1],position[0]+it.width,position[1]+it.height)}
        val path=Path()
        if(point!=null){check(safe.contains(point.first.toInt(),point.second.toInt())){"目标位置不在应用内容区域，未执行动作"};check(!overlay.contains(point.first.toInt(),point.second.toInt())){"目标位置被控制面板遮挡，未执行动作"};path.moveTo(point.first,point.second)}
        else {val x=bounds.exactCenterX();val top=bounds.top+bounds.height()*0.3f;val bottom=bounds.top+bounds.height()*0.7f
            check(safe.contains(x.toInt(),top.toInt()) && safe.contains(x.toInt(),bottom.toInt())){"滑动路径不在应用内容区域"}
            check(!(x>=overlay.left && x<overlay.right && top<overlay.bottom && bottom>overlay.top)){"滑动路径被控制面板遮挡，未执行动作"}
            if(action=="向上滑动"){path.moveTo(x,bottom);path.lineTo(x,top)}else{check(action=="向下滑动");path.moveTo(x,top);path.lineTo(x,bottom)}}
        session.consume(SystemClock.elapsedRealtime(),session.target,true,1)
        busy=true
        val turn=epoch
        check(dispatchGesture(GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(path,0,if(point==null)400 else 80)).build(),object:GestureResultCallback(){
            override fun onCompleted(gestureDescription:GestureDescription?){if(turn==epoch){busy=false;lastStatus="系统确认手势完成；不代表业务任务成功"}}
            override fun onCancelled(gestureDescription:GestureDescription?){if(turn==epoch)stop("系统取消了手势")}
        },handler)){"系统拒绝手势"}
    }
}
