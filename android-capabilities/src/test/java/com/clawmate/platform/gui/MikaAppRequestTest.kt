package com.clawmate.platform.gui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MikaAppRequestTest {
    private val apps = listOf(
        "com.sina.weibo" to "微博",
        "com.xingin.xhs" to "小红书",
        "fixture.notes" to "小红",
        "com.tencent.mm" to "微信",
        "com.tencent.mobileqq" to "QQ",
    )

    @Test fun recognizesGoToAndSearchPhrasings() {
        assertEquals("com.sina.weibo", MikaAppRequest.target("去微博帮我找一下有关英雄联盟s16的热点内容", apps))
        assertEquals("com.sina.weibo", MikaAppRequest.target("在微博里搜一下 S16", apps))
        assertEquals("com.sina.weibo", MikaAppRequest.target("用微博看看热搜", apps))
        assertEquals("com.tencent.mm", MikaAppRequest.target("帮我打开微信", apps))
    }

    @Test fun keepsLongerAppNamesFromBeingTruncated() {
        assertNull(MikaAppRequest.target("打开小红书助手", apps))
        assertNull(MikaAppRequest.target("怎么打开小红书？", apps))
    }

    @Test fun ambiguousOrAbsentMentionsStayUnbound() {
        assertNull(MikaAppRequest.target("帮我总结一下今天的热点", apps))
        assertNull(MikaAppRequest.target("去小红书看看", listOf("a.one" to "小红书", "b.two" to "小红书")))
    }

    @Test fun englishLaunchStillWorks() {
        assertEquals("com.sina.weibo", MikaAppRequest.target("please open 微博", apps))
    }

    @Test fun englishAliasesFindChineseLabels() {
        val installed = apps + listOf("com.android.settings" to "设置", "com.tencent.mm" to "微信")
        assertEquals("com.android.settings", MikaAppRequest.target("open settings and read the battery level", installed))
        assertEquals("com.tencent.mm", MikaAppRequest.target("please open wechat", installed))
        assertEquals("tv.danmaku.bili", MikaAppRequest.target("open bilibili", installed + listOf("tv.danmaku.bili" to "哔哩哔哩")))
    }
}
