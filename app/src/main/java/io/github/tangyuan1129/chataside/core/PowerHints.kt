package io.github.tangyuan1129.chataside.core

/**
 * What to tell the user to stop their phone from killing this app, per vendor.
 *
 * This is not cosmetic. When the OS force-stops the app, the accessibility
 * service disconnects, Android records it as a crash, and the service is then
 * removed from `enabled_accessibility_services` automatically — so the app goes
 * silent and looks broken. Observed on a realme/ColorOS device:
 *
 *     ActivityManager: Force stopping … : o-stop(40)
 *     AccessibilityUserState: [serviceDisconnectedLocked] add mCrashedServices …
 *     AccessibilityManagerService: mEnabledServices = [无障碍菜单, …]   ← ours gone
 *
 * The upstream text named Xiaomi/HyperOS only, because that was the author's
 * phone. On any other device that reads as instructions for a screen the user
 * does not have, which is worse than saying nothing.
 *
 * The vendor screens cannot be opened by an intent — every ROM invents its own,
 * and none are public API. Naming the path in words is the only thing we can do,
 * so the wording has to be right. The generic battery-optimisation allowlist
 * *is* standard API and is offered separately in the permission list.
 */
object PowerHints {

    private data class Vendor(val keywords: List<String>, val steps: String)

    /**
     * Evaluated in order; first keyword hit wins.
     *
     * Each one opens with the family name so the user can tell at a glance that
     * the steps are for the phone in their hand — a path with no label reads as
     * generic advice and gets skipped.
     */
    private val VENDORS = listOf(
        Vendor(
            listOf("xiaomi", "redmi", "poco", "blackshark"),
            "小米 / Redmi / POCO：设置 → 应用设置 → 授权管理 → 自启动，允许旁白；" +
                "再到 应用管理 → 旁白 → 省电策略，选「无限制」。"
        ),
        Vendor(
            listOf("oppo", "oneplus", "realme", "oplus"),
            "OPPO / 一加 / realme：设置 → 应用 → 应用管理 → 旁白 → 耗电管理，" +
                "打开「允许后台运行」和「允许自启动」。"
        ),
        Vendor(
            listOf("huawei", "honor"),
            "华为 / 荣耀：设置 → 应用 → 应用启动管理 → 旁白，关掉「自动管理」，" +
                "再手动打开「允许自启动」「允许关联启动」「允许后台活动」。"
        ),
        Vendor(
            listOf("vivo", "iqoo"),
            "vivo / iQOO：设置 → 电池 → 后台耗电管理 → 旁白，允许后台高耗电；" +
                "再到 i 管家 → 自启动里允许旁白。"
        ),
        Vendor(
            listOf("samsung"),
            "三星：设置 → 电池 → 后台使用限制，把旁白加入「不休眠的应用」。"
        ),
        Vendor(
            listOf("meizu"),
            "魅族：手机管家 → 权限管理 → 后台管理 → 旁白，允许后台运行。"
        ),
        Vendor(
            listOf("nubia", "redmagic", "zte"),
            "努比亚 / 红魔 / 中兴：设置 → 应用 → 旁白 → 电池，选「允许后台活动」；" +
                "再到手机管家 → 自启动管理里允许旁白。"
        ),
        Vendor(
            listOf("asus", "rog"),
            "华硕 / ROG：设置 → 电池 → 电源管理 → 旁白，选「允许后台运行」；" +
                "再到移动管家 → 自启动里允许旁白。"
        ),
        Vendor(
            listOf("lenovo", "zui", "motorola", "moto"),
            "联想 / 摩托罗拉：设置 → 应用 → 旁白 → 电池，允许后台运行；" +
                "再到安全中心 → 自启动管理里允许旁白。"
        ),
        Vendor(
            listOf("tecno", "infinix", "itel"),
            "Tecno / Infinix / itel：设置 → 应用 → 旁白 → 电池，允许后台运行；" +
                "再到手机管家 → 自启动里允许旁白。"
        ),
        // Near-stock Android (Pixel, Nokia, Sony, Nothing, and any AOSP build):
        // there is no auto-start screen to visit, and the standard battery
        // allowlist offered in the permission list is the whole answer. Saying so
        // beats sending the user hunting for a screen that does not exist.
        Vendor(
            listOf("google", "nokia", "sony", "nothing", "fairphone"),
            "Pixel / Nokia / Sony 等接近原生安卓的机型：设置 → 电池，" +
                "把旁白排除在省电优化之外（也就是打开权限列表里的「省电优化白名单」）。" +
                "这类系统没有额外的自启动开关，做到这一步就够了。"
        )
    )

    /**
     * Vendor-specific steps, keyed on the manufacturer/brand properties.
     *
     * @param manufacturer `Build.MANUFACTURER`
     * @param brand `Build.BRAND` — some ROMs put the real vendor here instead
     */
    fun hint(manufacturer: String, brand: String = ""): String {
        val id = "$manufacturer $brand".lowercase()
        VENDORS.firstOrNull { v -> v.keywords.any { id.contains(it) } }?.let { return it.steps }
        return FALLBACK
    }

    /**
     * The generic instructions. Written as two concrete things to look for plus
     * where they hide, because a user on an unlisted ROM still has to find them.
     */
    const val FALLBACK: String =
        "在系统设置里找两处，都允许旁白：\n" +
            "①「自启动 / 自动启动 / 开机启动」——有的机型藏在手机管家或安全中心里；\n" +
            "②「电池 / 耗电」里把旁白设为「不限制 / 允许后台运行」。"

    /**
     * Why this matters, in the user's terms.
     */
    fun consequence(): String =
        "系统一旦在后台杀掉旁白，安卓就会把无障碍服务当成崩溃并自动关闭，" +
            "界面上再也没有任何反应，必须重新开启无障碍。所以这两项务必放开。"

    /** Whether we actually have vendor-specific wording for this device. */
    fun hasVendorHint(manufacturer: String, brand: String = ""): Boolean {
        val id = "$manufacturer $brand".lowercase()
        return VENDORS.any { v -> v.keywords.any { id.contains(it) } }
    }
}
