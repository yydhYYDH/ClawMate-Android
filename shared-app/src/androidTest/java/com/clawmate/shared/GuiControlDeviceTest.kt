package com.clawmate.shared

import android.Manifest
import android.app.UiAutomation
import android.content.ComponentName
import android.content.pm.PackageManager
import android.graphics.Rect
import android.provider.Settings
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.clawmate.platform.gui.GuiControlService
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class GuiControlDeviceTest {
    private val instrumentation=InstrumentationRegistry.getInstrumentation()
    private val context=instrumentation.targetContext
    private var observedBridge:PlatformBridge?=null
    private fun automation()=instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
    private fun await(message:String,condition:()->Boolean) {
        val deadline=System.currentTimeMillis()+15000
        while(System.currentTimeMillis()<deadline){if(condition())return;Thread.sleep(80)}
        instrumentation.sendStatus(2,android.os.Bundle().apply{putString("guiControlFailure",runCatching{observedBridge?.guiControlStatus() ?: "No host bridge yet; test copy connected=${GuiControlService.connected!=null}"}.getOrDefault("Host bridge unavailable"))})
        error(message)
    }
    private fun find(root:AccessibilityNodeInfo?,text:String,exact:Boolean=false):AccessibilityNodeInfo? {
        if(root==null)return null
        val label=root.text?.toString();val description=root.contentDescription?.toString()
        if(root.isVisibleToUser && (if(exact)label==text || description==text else label?.contains(text)==true || description?.contains(text)==true))return root
        for(i in 0 until root.childCount)find(root.getChild(i),text,exact)?.let{return it};return null
    }
    private fun click(text:String,exact:Boolean=false) {
        await("Missing UI: $text") {
            val ui=automation();if(android.os.Build.VERSION.SDK_INT>=33)ui.clearCache()
            fun allowed(root:AccessibilityNodeInfo?)=root?.packageName?.toString() in setOf("com.clawmate.shared","com.clawmate.shared.test")
            val root=ui.rootInActiveWindow
            val node=(if(allowed(root))find(root,text,exact)else null) ?: ui.windows.firstNotNullOfOrNull{val candidate=it.root;if(allowed(candidate))find(candidate,text,exact)else null} ?: return@await false
            val rect=Rect().also{node.getBoundsInScreen(it)};tap(rect.exactCenterX(),rect.exactCenterY());true
        }
    }
    private fun tap(x:Float,y:Float) {
        check(automation().rootInActiveWindow?.packageName?.toString() in setOf("com.clawmate.shared","com.clawmate.shared.test")){"Refusing test input outside shared host or synthetic fixture"}
        android.os.ParcelFileDescriptor.AutoCloseInputStream(automation().executeShellCommand("input tap ${x.toInt()} ${y.toInt()}")).use{it.readBytes()};Thread.sleep(160)
    }
    private fun port(scenario:ActivityScenario<SharedActivity>):PlatformBridge {
        lateinit var value:PlatformBridge
        scenario.onActivity{value=SharedActivity::class.java.getDeclaredField("platform").apply{isAccessible=true}.get(it) as PlatformBridge};observedBridge=value;return value
    }
    @Test fun serviceManifestRequiresSystemBindingAndDeclaredCapabilities() {
        val info=context.packageManager.getServiceInfo(ComponentName(context,GuiControlService::class.java),PackageManager.GET_META_DATA)
        assertEquals(Manifest.permission.BIND_ACCESSIBILITY_SERVICE,info.permission);assertTrue(info.exported)
        val parser=context.resources.getXml(info.metaData.getInt("android.accessibilityservice"))
        parser.use { while(it.eventType!=org.xmlpull.v1.XmlPullParser.START_TAG)it.next()
            val ns="http://schemas.android.com/apk/res/android"
            for(key in listOf("canRetrieveWindowContent","canTakeScreenshot","canPerformGestures"))assertEquals("true",it.getAttributeValue(ns,key))
        }
    }
    @Test fun missingServiceNeverStartsSessionAndDisclosureCancelChangesNoPermission() {
        assumeTrue("This negative fixture requires the service not enabled",GuiControlService.connected==null)
        automation();instrumentation.waitForIdleSync();Thread.sleep(200)
        val resolver=context.contentResolver
        val before=Settings.Secure.getString(resolver,Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
        val enabled=Settings.Secure.getString(resolver,Settings.Secure.ACCESSIBILITY_ENABLED)
        ActivityScenario.launch(SharedActivity::class.java).use{scenario->
            val bridge=port(scenario);Thread.sleep(800)
            bridge.guiControlOpen();click("知道了")
            assertFalse(JSONObject(bridge.guiControlStatus()).getBoolean("connected"))
            bridge.guiControlSettings();click("取消")
            assertTrue("Enabled services changed after cancelling disclosure",before==Settings.Secure.getString(resolver,Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES))
            assertTrue("Accessibility state changed after cancelling disclosure",enabled==Settings.Secure.getString(resolver,Settings.Secure.ACCESSIBILITY_ENABLED))
        }
    }
}
