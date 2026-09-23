package io.github.tangyuan1129.chataside.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for the "stop the OS killing this app" guidance.
 *
 * Worth pinning because it replaced vendor-specific text that named a phone the
 * user did not own: the failure it prevents (app killed → accessibility marked
 * crashed → service silently disabled) is exactly the one that looked like the
 * app being broken.
 */
class PowerHintsTest {

    @Test
    fun `realme gets the oppo family path, not the xiaomi one`() {
        val h = PowerHints.hint("realme", "realme")
        assertTrue(h.contains("realme"))
        assertTrue("must name the battery screen that exists on ColorOS", h.contains("耗电管理"))
        assertFalse("must not send the user to a Xiaomi screen", h.contains("HyperOS"))
    }

    @Test
    fun `oppo and oneplus are covered too`() {
        assertTrue(PowerHints.hint("OPPO", "OPPO").contains("耗电管理"))
        assertTrue(PowerHints.hint("OnePlus", "OnePlus").contains("耗电管理"))
    }

    @Test
    fun `xiaomi still gets its own path`() {
        val h = PowerHints.hint("Xiaomi", "Redmi")
        assertTrue(h.contains("自启动"))
        assertTrue("should mention the unlimited battery policy", h.contains("无限制"))
    }

    @Test
    fun `huawei and vivo and samsung each get their own`() {
        assertTrue(PowerHints.hint("HUAWEI", "HONOR").contains("应用启动管理"))
        assertTrue(PowerHints.hint("vivo", "iQOO").contains("后台耗电管理"))
        assertTrue(PowerHints.hint("samsung", "samsung").contains("休眠"))
    }

    @Test
    fun `an unknown vendor still gets usable generic steps`() {
        val h = PowerHints.hint("SomeBrand", "SomeBrand")
        assertTrue(h.contains("自启动"))
        assertTrue(h.contains("后台"))
    }

    @Test
    fun `an empty manufacturer does not crash or return nothing`() {
        assertTrue(PowerHints.hint("", "").isNotBlank())
    }

    @Test
    fun `matching is case insensitive`() {
        assertTrue(PowerHints.hint("REALME", "Realme").contains("耗电管理"))
        assertTrue(PowerHints.hint("xiaomi", "").contains("无限制"))
    }

    @Test
    fun `the consequence explains the crash so the user knows why it matters`() {
        val c = PowerHints.consequence()
        assertTrue("must say the service gets turned off", c.contains("自动关闭"))
        assertTrue("must say it needs re-enabling", c.contains("重新开启"))
    }

    // ------------------------------------------- coverage across vendors

    @Test
    fun `the remaining major vendors each get their own path`() {
        assertTrue(PowerHints.hint("Meizu", "meizu").contains("后台管理"))
        assertTrue(PowerHints.hint("nubia", "REDMAGIC").contains("允许后台活动"))
        assertTrue(PowerHints.hint("ASUS", "ROG").contains("电源管理"))
        assertTrue(PowerHints.hint("Lenovo", "ZUI").contains("安全中心"))
        assertTrue(PowerHints.hint("TECNO", "Infinix").contains("手机管家"))
    }

    @Test
    fun `black shark follows the xiaomi path since it is a xiaomi sub-brand`() {
        assertTrue(PowerHints.hint("Blackshark", "blackshark").contains("省电策略"))
    }

    @Test
    fun `near-stock android is told there is no autostart screen to look for`() {
        // Sending a Pixel owner hunting for a screen that does not exist is the
        // same mistake the Xiaomi-only text made.
        val pixel = PowerHints.hint("Google", "google")
        assertTrue("must point at the battery setting", pixel.contains("电池"))
        assertTrue("and name the standard allowlist", pixel.contains("省电优化"))
        assertTrue(
            "must say outright that there is no autostart screen",
            pixel.contains("没有额外的自启动开关")
        )
    }

    @Test
    fun `every hint names the phone family it is written for`() {
        // A path with no label reads as generic advice and gets skipped. Checked
        // by looking for the family name rather than by measuring the string —
        // an earlier version asserted on the prefix length, which said nothing
        // about whether the right brand was named.
        val expected = mapOf(
            "xiaomi" to "小米",
            "oppo" to "realme",
            "huawei" to "华为",
            "vivo" to "iQOO",
            "samsung" to "三星",
            "meizu" to "魅族",
            "nubia" to "红魔",
            "asus" to "ROG",
            "lenovo" to "联想",
            "tecno" to "Infinix",
            "google" to "Pixel"
        )
        for ((manufacturer, token) in expected) {
            val h = PowerHints.hint(manufacturer, manufacturer)
            assertTrue("$manufacturer hint should name '$token': $h", h.contains(token))
            assertTrue("$manufacturer hint should use the '名字：步骤' shape", h.contains("："))
        }
    }

    @Test
    fun `hasVendorHint distinguishes covered from uncovered devices`() {
        assertTrue(PowerHints.hasVendorHint("realme", "realme"))
        assertTrue(PowerHints.hasVendorHint("Xiaomi", "Redmi"))
        assertFalse("an unlisted ROM falls back, so callers can tell", PowerHints.hasVendorHint("Acme", "Acme"))
    }

    @Test
    fun `the fallback names both things to look for and where they hide`() {
        val f = PowerHints.FALLBACK
        assertTrue("autostart", f.contains("自启动"))
        assertTrue("battery", f.contains("电池"))
        assertTrue("unrestricted", f.contains("不限制"))
        assertTrue("should say it may be hidden deeper", f.contains("安全中心"))
    }

    @Test
    fun `every vendor hint is actionable rather than a placeholder`() {
        // A hint that says nothing is worse than the generic fallback, so none of
        // them may be a stub.
        for ((m, b) in listOf(
            "xiaomi" to "redmi", "oppo" to "oppo", "huawei" to "honor",
            "vivo" to "iqoo", "samsung" to "samsung", "meizu" to "meizu",
            "nubia" to "redmagic", "asus" to "rog", "lenovo" to "zui",
            "tecno" to "infinix", "google" to "google"
        )) {
            val h = PowerHints.hint(m, b)
            assertTrue("$m hint is too short to be usable: $h", h.length > 20)
            assertTrue("$m hint should mention a setting to open", h.contains("设置") || h.contains("管家"))
        }
    }
}
