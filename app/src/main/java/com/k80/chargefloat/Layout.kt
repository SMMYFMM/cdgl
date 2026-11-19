package com.k80.chargefloat

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import kotlin.math.abs

/**
 * 悬浮窗可显示的字段。顺序即设置页里的展示顺序。
 */
enum class Field(val key: String, val label: String) {
    POWER("power", "功率"),
    VOLTAGE("voltage", "电压"),
    CURRENT("current", "电流"),
    AVG("avg", "平均功率"),
    PEAK("peak", "峰值功率"),
    WH("wh", "电量 Wh"),
    DURATION("duration", "本次时长"),
    PERCENT("percent", "电量"),
    TEMP("temp", "温度"),
    STATUS("status", "状态文字");

    companion object {
        fun of(key: String?): Field? = entries.firstOrNull { it.key == key }
    }
}

/** 逐项取色用的预设色板 */
object Palette {
    val COLORS = intArrayOf(
        0xFFFF4D4F.toInt(),
        0xFFFF9F1C.toInt(),
        0xFFFFD60A.toInt(),
        0xFF32D74B.toInt(),
        0xFF4DD0FF.toInt(),
        0xFF0A84FF.toInt(),
        0xFFBF5AF2.toInt(),
        0xFFFFFFFF.toInt(),
        0xFFB9BEC9.toInt()
    )
}

/**
 * 单个字段的排版配置。
 * [color] 为 [LayoutSpec.AUTO] 时跟随充电/放电/待机状态色。
 */
class FieldCfg(
    var field: Field,
    var row: Int,
    var order: Int,
    var color: Int,
    var enabled: Boolean
)

/**
 * 悬浮窗排版：3 行，每行按 order 排列，字段可自由换行、换序、换色。
 */
class LayoutSpec(val items: MutableList<FieldCfg>) {

    fun enabledIn(row: Int): List<FieldCfg> =
        items.filter { it.enabled && it.row == row }.sortedBy { it.order }

    /** 每个字段都启用时，这一行是否还有内容（用于预览提示） */
    fun hasAny(row: Int): Boolean = items.any { it.row == row }

    fun cfgOf(f: Field): FieldCfg? = items.firstOrNull { it.field == f }

    fun save(prefs: Prefs) {
        prefs.layoutSpec = toJson()
    }

    /** 把每行 order 重新压成 0..n-1，避免换行后出现空洞 */
    fun normalize() {
        for (r in 0..2) {
            items.filter { it.row == r }.sortedBy { it.order }
                .forEachIndexed { i, c -> c.order = i }
        }
    }

    /** 在同一行内上移 / 下移；dir = -1 上移，1 下移 */
    fun move(cfg: FieldCfg, dir: Int): Boolean {
        val same = items.filter { it.row == cfg.row }.sortedBy { it.order }.toMutableList()
        val idx = same.indexOfFirst { it.field == cfg.field }
        val target = idx + dir
        if (idx < 0 || target !in same.indices) return false
        val o = same[idx].order
        same[idx].order = same[target].order
        same[target].order = o
        normalize()
        return true
    }

    fun toJson(): String {
        val arr = JSONArray()
        for (c in items) {
            arr.put(
                JSONObject()
                    .put("k", c.field.key)
                    .put("r", c.row)
                    .put("o", c.order)
                    .put("c", c.color)
                    .put("e", c.enabled)
            )
        }
        return JSONObject().put("v", 1).put("items", arr).toString()
    }

    companion object {
        /** 跟随状态色 */
        const val AUTO = -1

        private const val SECONDARY = 0xCCFFFFFF.toInt()

        fun default(): LayoutSpec = LayoutSpec(
            mutableListOf(
                FieldCfg(Field.POWER, 0, 0, AUTO, true),
                FieldCfg(Field.VOLTAGE, 1, 0, SECONDARY, true),
                FieldCfg(Field.CURRENT, 1, 1, SECONDARY, true),
                FieldCfg(Field.PERCENT, 1, 2, SECONDARY, true),
                FieldCfg(Field.TEMP, 1, 3, SECONDARY, true),
                FieldCfg(Field.AVG, 2, 0, SECONDARY, false),
                FieldCfg(Field.PEAK, 2, 1, SECONDARY, false),
                FieldCfg(Field.WH, 2, 2, SECONDARY, false),
                FieldCfg(Field.DURATION, 2, 3, SECONDARY, false),
                FieldCfg(Field.STATUS, 2, 4, SECONDARY, false)
            )
        )

        fun parse(json: String?): LayoutSpec? {
            if (json.isNullOrBlank()) return null
            return runCatching {
                val arr = JSONObject(json).getJSONArray("items")
                val list = ArrayList<FieldCfg>(arr.length())
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    val f = Field.of(o.optString("k")) ?: continue
                    list.add(
                        FieldCfg(
                            f,
                            o.optInt("r", 0).coerceIn(0, 2),
                            o.optInt("o", i),
                            o.optInt("c", AUTO),
                            o.optBoolean("e", true)
                        )
                    )
                }
                if (list.isEmpty()) null else LayoutSpec(list).also { it.normalize() }
            }.getOrNull()
        }

        /** 读配置；第一次升级上来时按旧设置迁移一次，不丢用户已有的习惯 */
        fun load(prefs: Prefs): LayoutSpec {
            parse(prefs.layoutSpec)?.let { return it }

            val spec = default()
            if (!prefs.showDetail) {
                listOf(Field.VOLTAGE, Field.CURRENT).forEach { spec.cfgOf(it)?.enabled = false }
            }
            if (!prefs.showBattery) {
                listOf(Field.PERCENT, Field.TEMP).forEach { spec.cfgOf(it)?.enabled = false }
            }
            when (prefs.layoutStyle) {
                0 -> listOf(Field.VOLTAGE, Field.CURRENT, Field.PERCENT, Field.TEMP).forEach {
                    spec.cfgOf(it)?.row = 0
                }
                2 -> listOf(Field.AVG, Field.PEAK, Field.WH, Field.DURATION, Field.STATUS).forEach {
                    spec.cfgOf(it)?.enabled = true
                }
            }
            spec.normalize()
            return spec
        }
    }
}

/**
 * 悬浮窗渲染器。悬浮窗和设置页预览共用同一份逻辑，保证看到什么就是什么。
 *
 * 性能上只做两件事：文本没变就不 setText，尺寸没变就不 requestLayout，
 * 由调用方根据返回的 changed 决定要不要通知 WindowManager。
 */
class OverlayRenderer(private val context: Context) {

    private val rows = arrayOfNulls<TextView>(3)
    private val lastKeys = arrayOfNulls<String>(3)
    private val lastSizes = IntArray(3) { -1 }
    private val lastBold = BooleanArray(3)

    private val medium = Typeface.create("sans-serif-medium", Typeface.NORMAL)

    val view = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.START
    }

    init {
        for (i in 0..2) {
            val tv = TextView(context).apply {
                gravity = Gravity.START
                includeFontPadding = false
                setTextColor(Color.WHITE)
                visibility = View.GONE
            }
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            if (i > 0) lp.topMargin = dp(1)
            view.addView(tv, lp)
            rows[i] = tv
        }
    }

    /** 清空缓存，强制下次渲染重画 */
    fun invalidate() {
        for (i in 0..2) {
            lastKeys[i] = null
            lastSizes[i] = -1
        }
    }

    /**
     * @param collapsed 单击折叠时只显示第一行有内容的
     * @return 内容是否发生变化（变了才需要 updateViewLayout）
     */
    fun render(
        s: Snapshot,
        st: SessionStats,
        p: Prefs,
        spec: LayoutSpec,
        collapsed: Boolean = false
    ): Boolean {
        var changed = false
        val stateColor = when {
            s.charging -> p.chargeColor
            s.plugged -> p.idleColor
            else -> p.dischargeColor
        }
        val small = (p.fontSize * p.rowScale / 100f).coerceAtLeast(7f)
        var shown = false

        for (r in 0..2) {
            val tv = rows[r] ?: continue
            val items = spec.enabledIn(r)
            val allow = items.isNotEmpty() && (!collapsed || !shown)

            if (!allow) {
                if (tv.visibility != View.GONE) {
                    tv.visibility = View.GONE
                    changed = true
                }
                lastKeys[r] = null
                continue
            }

            val sb = SpannableStringBuilder()
            val key = StringBuilder()
            var first = true
            for (cfg in items) {
                val text = valueOf(cfg.field, s, st, p) ?: continue
                val color = if (cfg.color == LayoutSpec.AUTO) stateColor else cfg.color
                if (!first) {
                    // 只用空格隔开，去掉原来的「·」，让各段靠得更紧
                    sb.append(" ")
                    key.append('|')
                }
                val start = sb.length
                sb.append(text)
                sb.setSpan(
                    ForegroundColorSpan(color),
                    start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                )
                key.append(text).append('#').append(Integer.toHexString(color))
                first = false
            }

            if (key.isEmpty()) {
                if (tv.visibility != View.GONE) {
                    tv.visibility = View.GONE
                    changed = true
                }
                lastKeys[r] = null
                continue
            }
            shown = true

            val big = p.uniformSize || items.any { it.field == Field.POWER }
            val size = if (big) p.fontSize.toFloat() else small
            val sizeKey = (size * 10).toInt()
            if (lastSizes[r] != sizeKey) {
                tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, size)
                lastSizes[r] = sizeKey
                changed = true
            }
            if (lastBold[r] != big) {
                tv.typeface = if (big) medium else Typeface.DEFAULT
                if (big) {
                    tv.setShadowLayer(dp(4).toFloat(), 0f, dp(1).toFloat(), 0x66000000)
                } else {
                    tv.setShadowLayer(0f, 0f, 0f, 0)
                }
                lastBold[r] = big
                changed = true
            }

            val k = key.toString()
            if (lastKeys[r] != k) {
                tv.text = sb
                lastKeys[r] = k
                changed = true
            }
            if (tv.visibility != View.VISIBLE) {
                tv.visibility = View.VISIBLE
                changed = true
            }
        }
        return changed
    }

    private fun valueOf(f: Field, s: Snapshot, st: SessionStats, p: Prefs): String? = when (f) {
        Field.POWER -> {
            if (s.valid) {
                val v = if (abs(s.watts) < 0.12f) 0f else abs(s.watts)
                val num = String.format(Locale.US, "%.${p.decimals}f", v)
                val prefix = if (s.charging && v >= 0.1f) "+" else ""
                "$prefix$num W"
            } else {
                "--.- W"
            }
        }

        Field.VOLTAGE -> if (s.voltage > 0.1f) String.format(Locale.US, "%.2fV", s.voltage) else null
        Field.CURRENT -> if (s.valid) String.format(Locale.US, "%.2fA", abs(s.current)) else null
        Field.AVG -> if (st.millis > 3000 && st.avgW > 0.05f)
            String.format(Locale.US, "平均%.${p.decimals}fW", st.avgW) else null

        Field.PEAK -> if (st.millis > 0 && st.peakW > 0.05f)
            String.format(Locale.US, "峰值%.0fW", st.peakW) else null

        Field.WH -> if (st.millis > 0 && st.wh > 0.005f) String.format(Locale.US, "%.2fWh", st.wh)
        else null

        Field.DURATION -> when {
            st.millis >= 3_600_000L -> String.format(Locale.US, "%.1f时", st.millis / 3_600_000f)
            st.millis >= 60_000L -> "${st.millis / 60_000L}分"
            st.millis > 5_000L -> "<1分"
            else -> null
        }

        Field.PERCENT -> if (s.percent in 0..100) "${s.percent}%" else null
        Field.TEMP -> if (!s.tempC.isNaN()) String.format(Locale.US, "%.1f℃", s.tempC) else null
        Field.STATUS -> s.statusText.ifEmpty { null }
    }

    private fun dp(v: Int): Int = (v * context.resources.displayMetrics.density + 0.5f).toInt()
}
