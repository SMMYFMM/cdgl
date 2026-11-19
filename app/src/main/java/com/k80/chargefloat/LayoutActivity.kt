package com.k80.chargefloat

import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.materialswitch.MaterialSwitch

/**
 * 排版设置页：预览 + 字段开关 / 换行 / 换序 / 逐项配色。
 *
 * 预览和悬浮窗调用同一个 [OverlayRenderer]，所以这里看到什么，桌面上就是什么。
 */
class LayoutActivity : AppCompatActivity() {

    private lateinit var prefs: Prefs
    private lateinit var spec: LayoutSpec
    private lateinit var renderer: OverlayRenderer

    private lateinit var previewBox: LinearLayout
    private lateinit var listBox: LinearLayout
    private lateinit var swUniform: MaterialSwitch
    private lateinit var seekScale: SeekBar
    private lateinit var tvScaleVal: TextView

    private var colorDialog: AlertDialog? = null

    private val sample = Snapshot(
        watts = 63.4f,
        voltage = 8.42f,
        current = 1.47f,
        percent = 76,
        tempC = 33.2f,
        charging = true,
        plugged = true,
        statusText = "充电中",
        valid = true
    )

    private val sampleStats = SessionStats(
        charging = true,
        peakW = 78.2f,
        avgW = 54.6f,
        wh = 12.4f,
        millis = 18 * 60_000L,
        startPercent = 42,
        endPercent = 76
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_layout)

        prefs = Prefs(this)
        spec = LayoutSpec.load(prefs)
        renderer = OverlayRenderer(this)

        previewBox = findViewById(R.id.previewBox)
        listBox = findViewById(R.id.listBox)
        swUniform = findViewById(R.id.swUniform)
        seekScale = findViewById(R.id.seekScale)
        tvScaleVal = findViewById(R.id.tvScaleVal)

        previewBox.addView(
            renderer.view,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )
        previewBox.background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(12).toFloat()
            setColor(android.graphics.Color.argb(
                (prefs.bgAlpha * 255 / 100).coerceIn(0, 255), 8, 8, 12
            ))
            setStroke(dp(1), 0x22FFFFFF)
        }

        findViewById<TextView>(R.id.btnBack).setOnClickListener { finish() }
        findViewById<MaterialButton>(R.id.btnReset).setOnClickListener {
            spec = LayoutSpec.default()
            commit()
            bindFontControls()
            rebuildList()
        }

        bindFontControls()
        rebuildList()
        refreshPreview()
    }

    // ---------------------------------------------------------------- 字号

    private fun bindFontControls() {
        swUniform.isChecked = prefs.uniformSize
        swUniform.setOnCheckedChangeListener { _, checked ->
            prefs.uniformSize = checked
            refreshPreview()
        }

        seekScale.max = 80
        seekScale.progress = (prefs.rowScale - 20).coerceIn(0, 80)
        tvScaleVal.text = getString(R.string.percent_value, prefs.rowScale)
        seekScale.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar, progress: Int, fromUser: Boolean) {
                val v = progress + 20
                prefs.rowScale = v
                tvScaleVal.text = getString(R.string.percent_value, v)
                refreshPreview()
            }

            override fun onStartTrackingTouch(bar: SeekBar) = Unit
            override fun onStopTrackingTouch(bar: SeekBar) = Unit
        })
    }

    // ---------------------------------------------------------------- 字段列表

    private fun rebuildList() {
        listBox.removeAllViews()
        for (field in Field.entries) {
            val cfg = spec.cfgOf(field) ?: continue
            listBox.addView(buildFieldRow(cfg))
        }
    }

    private fun buildFieldRow(cfg: FieldCfg): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(5), 0, dp(5))
        }

        val sw = MaterialSwitch(this).apply {
            isChecked = cfg.enabled
            setOnCheckedChangeListener { _, checked ->
                cfg.enabled = checked
                commit()
            }
        }
        row.addView(sw, LinearLayout.LayoutParams(dp(52), dp(36)))

        val label = TextView(this).apply {
            text = cfg.field.label
            setTextColor(getColor(R.color.textPrimary))
            textSize = 14f
            setPadding(dp(4), 0, 0, 0)
        }
        row.addView(label, LinearLayout.LayoutParams(0, WRAP, 1f))

        val rowBtn = TextView(this).apply {
            text = "第${cfg.row + 1}行"
            setTextColor(if (cfg.enabled) getColor(R.color.textPrimary) else getColor(R.color.textSecondary))
            textSize = 12f
            gravity = Gravity.CENTER
            background = chipBackground()
            setPadding(dp(8), dp(5), dp(8), dp(5))
            setOnClickListener {
                val target = (cfg.row + 1) % 3
                cfg.row = target
                cfg.order = spec.items.count { it.row == target && it.field != cfg.field }
                spec.normalize()
                commit()
                rebuildList()
            }
        }
        row.addView(rowBtn)

        val dot = View(this).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                if (cfg.color == LayoutSpec.AUTO) {
                    setColor(getColor(R.color.textSecondary))
                    setStroke(dp(2), getColor(R.color.surface))
                } else {
                    setColor(cfg.color)
                    setStroke(dp(1), 0x33FFFFFF)
                }
            }
            setOnClickListener { pickColor(cfg) }
        }
        val dotLp = LinearLayout.LayoutParams(dp(24), dp(24))
        dotLp.marginStart = dp(10)
        dotLp.marginEnd = dp(8)
        row.addView(dot, dotLp)

        val up = arrow("↑") {
            if (spec.move(cfg, -1)) {
                commit()
                rebuildList()
            }
        }
        val down = arrow("↓") {
            if (spec.move(cfg, 1)) {
                commit()
                rebuildList()
            }
        }
        row.addView(up, LinearLayout.LayoutParams(dp(26), dp(30)))
        row.addView(down, LinearLayout.LayoutParams(dp(26), dp(30)))

        return row
    }

    private fun arrow(text: String, onClick: () -> Unit): TextView =
        TextView(this).apply {
            this.text = text
            setTextColor(getColor(R.color.textSecondary))
            textSize = 15f
            gravity = Gravity.CENTER
            setOnClickListener { onClick() }
        }

    private fun chipBackground(): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = dp(8).toFloat()
        setColor(getColor(R.color.surfaceHigh))
        setStroke(dp(1), getColor(R.color.divider))
    }

    // ---------------------------------------------------------------- 配色

    private fun pickColor(cfg: FieldCfg) {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(6), dp(20), dp(4))
        }

        val auto = TextView(this).apply {
            text = "跟随状态色（充电 / 放电 / 待机自动切换）"
            setTextColor(getColor(R.color.textPrimary))
            textSize = 14f
            setPadding(0, dp(10), 0, dp(14))
            setOnClickListener {
                cfg.color = LayoutSpec.AUTO
                commit()
                rebuildList()
                colorDialog?.dismiss()
            }
        }
        box.addView(auto)

        var line: LinearLayout? = null
        Palette.COLORS.forEachIndexed { index, color ->
            if (index % 5 == 0) {
                val l = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    setPadding(0, 0, 0, dp(8))
                }
                box.addView(l)
                line = l
            }
            val chosen = cfg.color == color
            val dot = View(this).apply {
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(color)
                    setStroke(dp(if (chosen) 3 else 1), if (chosen) 0xFFFFFFFF.toInt() else 0x33FFFFFF)
                }
                setOnClickListener {
                    cfg.color = color
                    commit()
                    rebuildList()
                    colorDialog?.dismiss()
                }
            }
            val lp = LinearLayout.LayoutParams(dp(36), dp(36))
            lp.marginEnd = dp(10)
            line?.addView(dot, lp)
        }

        colorDialog = AlertDialog.Builder(this)
            .setTitle(cfg.field.label)
            .setView(box)
            .setNegativeButton("取消", null)
            .create()
        colorDialog?.show()
    }

    // ---------------------------------------------------------------- 预览

    private fun commit() {
        spec.save(prefs)
        refreshPreview()
    }

    private fun refreshPreview() {
        renderer.invalidate()
        renderer.render(sample, sampleStats, prefs, spec, false)
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density + 0.5f).toInt()

    companion object {
        private const val WRAP = LinearLayout.LayoutParams.WRAP_CONTENT
    }
}
