package com.freebuff.core.ui

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 语言归一化的边界:只认白名单,其余一律「跟随系统」。
 *
 * 这里守两件事:
 * 1. **系统给的标记是带区域的**(zh-Hans / en-US / zh-CN),不能因为 `!= "zh"` 就被判成「跟随系统」;
 * 2. **未知语言回落成「跟随系统」**而不是原样存进去 —— 存了不认识的标记,设置页的分段控件会
 *    找不到选中项(索引 -1),界面就会显示成「一个都没选」。
 */
class AppLanguageTest {

    @Test
    fun `带区域的标记按语言前缀归一`() {
        assertEquals(AppLanguage.ZH, AppLanguage.normalize("zh"))
        assertEquals(AppLanguage.ZH, AppLanguage.normalize("zh-Hans"))
        assertEquals(AppLanguage.ZH, AppLanguage.normalize("zh-CN"))
        assertEquals(AppLanguage.EN, AppLanguage.normalize("en"))
        assertEquals(AppLanguage.EN, AppLanguage.normalize("en-US"))
    }

    @Test
    fun `空值与未知语言都回落成跟随系统`() {
        assertEquals(AppLanguage.SYSTEM, AppLanguage.normalize(null))
        assertEquals(AppLanguage.SYSTEM, AppLanguage.normalize(""))
        assertEquals(AppLanguage.SYSTEM, AppLanguage.normalize("   "))
        assertEquals(AppLanguage.SYSTEM, AppLanguage.normalize("system"))
        assertEquals(AppLanguage.SYSTEM, AppLanguage.normalize("fr"))
    }

    @Test
    fun `选项顺序与设置页分段控件一一对应`() {
        // 设置页拿 OPTIONS 的下标去选分段项,顺序本身就是契约
        assertEquals(listOf(AppLanguage.SYSTEM, AppLanguage.ZH, AppLanguage.EN), AppLanguage.OPTIONS)
        assertEquals(1, AppLanguage.OPTIONS.indexOf(AppLanguage.ZH))
        assertEquals(2, AppLanguage.OPTIONS.indexOf(AppLanguage.EN))
    }
}
