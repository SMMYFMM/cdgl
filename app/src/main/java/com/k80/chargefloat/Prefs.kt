package com.k80.chargefloat

import android.content.Context

/**
 * 所有设置项都存在 SharedPreferences，悬浮窗每秒读取，改完立即生效。
 */
class Prefs(context: Context) {

    private val sp = context.applicationContext.getSharedPreferences("cfg", Context.MODE_PRIVATE)

    /** 给需要注册监听的地方用 */
    fun raw(): android.content.SharedPreferences = sp

    /** 悬浮窗功率字号，单位 sp */
    var fontSize: Int
        get() = sp.getInt("font_size", 34)
        set(value) = sp.edit().putInt("font_size", value).apply()

    /** 背景不透明度 0..100 */
    var bgAlpha: Int
        get() = sp.getInt("bg_alpha", 70)
        set(value) = sp.edit().putInt("bg_alpha", value).apply()

    /** 充电颜色 */
    var chargeColor: Int
        get() = sp.getInt("color_charge", 0xFFFF4D4F.toInt())
        set(value) = sp.edit().putInt("color_charge", value).apply()

    /** 放电（消耗）颜色 */
    var dischargeColor: Int
        get() = sp.getInt("color_discharge", 0xFF4DD0FF.toInt())
        set(value) = sp.edit().putInt("color_discharge", value).apply()

    /** 插电但未充电 / 待机颜色 */
    var idleColor: Int
        get() = sp.getInt("color_idle", 0xFFB9BEC9.toInt())
        set(value) = sp.edit().putInt("color_idle", value).apply()

    /** 小数位 0..2 */
    var decimals: Int
        get() = sp.getInt("decimals", 1).coerceIn(0, 2)
        set(value) = sp.edit().putInt("decimals", value.coerceIn(0, 2)).apply()

    /** 悬浮窗排版（JSON）。空表示还没设置过，首次会按旧设置迁移 */
    var layoutSpec: String
        get() = sp.getString("layout_spec", "") ?: ""
        set(value) = sp.edit().putString("layout_spec", value).apply()

    /** 副行字号相对主字号的百分比 20..100 */
    var rowScale: Int
        get() = sp.getInt("row_scale", 32).coerceIn(20, 100)
        set(value) = sp.edit().putInt("row_scale", value.coerceIn(20, 100)).apply()

    /** 所有行统一用主字号 */
    var uniformSize: Boolean
        get() = sp.getBoolean("uniform_size", false)
        set(value) = sp.edit().putBoolean("uniform_size", value).apply()

    /** 旧版布局：0=单行 1=两行 2=详细（仅用于迁移） */
    var layoutStyle: Int
        get() = sp.getInt("layout_style", 1).coerceIn(0, 2)
        set(value) = sp.edit().putInt("layout_style", value.coerceIn(0, 2)).apply()

    var showDetail: Boolean
        get() = sp.getBoolean("show_detail", true)
        set(value) = sp.edit().putBoolean("show_detail", value).apply()

    var showBattery: Boolean
        get() = sp.getBoolean("show_battery", true)
        set(value) = sp.edit().putBoolean("show_battery", value).apply()

    var showSession: Boolean
        get() = sp.getBoolean("show_session", true)
        set(value) = sp.edit().putBoolean("show_session", value).apply()

    /** 锁定位置，禁止拖动 */
    var locked: Boolean
        get() = sp.getBoolean("locked", false)
        set(value) = sp.edit().putBoolean("locked", value).apply()

    /** 开机自启 */
    var bootStart: Boolean
        get() = sp.getBoolean("boot_start", false)
        set(value) = sp.edit().putBoolean("boot_start", value).apply()

    /** 采样间隔 ms */
    var sampleMs: Int
        get() = sp.getInt("sample_ms", 1000)
        set(value) = sp.edit().putInt("sample_ms", value).apply()

    /** 悬浮窗位置，-1 表示还没设置过 */
    var posX: Int
        get() = sp.getInt("pos_x", -1)
        set(value) = sp.edit().putInt("pos_x", value).apply()

    var posY: Int
        get() = sp.getInt("pos_y", -1)
        set(value) = sp.edit().putInt("pos_y", value).apply()

    /** 优先用 root 读取 sysfs（无 root 自动忽略） */
    var useRoot: Boolean
        get() = sp.getBoolean("use_root", false)
        set(value) = sp.edit().putBoolean("use_root", value).apply()
}
