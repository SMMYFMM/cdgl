package com.k80.chargefloat

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.app.StatusBarManager
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.Icon
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.view.View
import android.widget.LinearLayout
import android.widget.RadioGroup
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.materialswitch.MaterialSwitch
import java.util.Locale
import kotlin.math.abs

class MainActivity : AppCompatActivity() {

    private lateinit var prefs: Prefs
    private lateinit var reader: PowerReader
    private val ui = Handler(Looper.getMainLooper())
    private lateinit var worker: HandlerThread
    private lateinit var bg: Handler

    private lateinit var tvBigPower: TextView
    private lateinit var tvState: TextView
    private lateinit var tvSub: TextView
    private lateinit var tvSession: TextView
    private lateinit var tvSource: TextView
    private lateinit var tvFloatHint: TextView
    private lateinit var tvFontVal: TextView
    private lateinit var tvAlphaVal: TextView
    private lateinit var tvPerm: TextView
    private lateinit var seekFont: SeekBar
    private lateinit var seekAlpha: SeekBar
    private lateinit var rgDecimals: RadioGroup
    private lateinit var rgInterval: RadioGroup
    private lateinit var swLock: MaterialSwitch
    private lateinit var swBoot: MaterialSwitch
    private lateinit var swRoot: MaterialSwitch
    private lateinit var paletteCharge: LinearLayout
    private lateinit var paletteDischarge: LinearLayout
    private lateinit var paletteIdle: LinearLayout
    private lateinit var btnFloat: MaterialButton

    private var ticking = false

    private val tick = object : Runnable {
        override fun run() {
            if (!ticking) return
            val live = FloatService.running
            val snap = if (live) PowerBus.snapshot ?: reader.read() else reader.read()
            val stats = if (live) PowerBus.stats else reader.stats()
            ui.post { if (ticking) bindLive(snap, stats) }
            bg.postDelayed(this, 900)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        prefs = Prefs(this)
        reader = PowerReader(this)
        worker = HandlerThread("ui-sampler").also { it.start() }
        bg = Handler(worker.looper)

        bindViews()
        bindSettings()
        refreshPalettes()
        refreshPermissionState()
        refreshFloatUi()

        btnFloat.setOnClickListener { toggleFloat() }
        findViewById<MaterialButton>(R.id.btnOverlayPerm).setOnClickListener { requestOverlay() }
        findViewById<MaterialButton>(R.id.btnNotifyPerm).setOnClickListener { requestNotification() }
        findViewById<MaterialButton>(R.id.btnBatteryOpt).setOnClickListener { requestBattery() }
        findViewById<MaterialButton>(R.id.btnMiui).setOnClickListener { openMiuiPermissions() }
        findViewById<MaterialButton>(R.id.btnDiag).setOnClickListener { showDiagnose() }
        findViewById<MaterialButton>(R.id.btnLayout).setOnClickListener {
            startActivity(Intent(this, LayoutActivity::class.java))
        }
        findViewById<MaterialButton>(R.id.btnTile).setOnClickListener { addQuickTile() }
    }

    private fun bindViews() {
        tvBigPower = findViewById(R.id.tvBigPower)
        tvState = findViewById(R.id.tvState)
        tvSub = findViewById(R.id.tvSub)
        tvSession = findViewById(R.id.tvSession)
        tvSource = findViewById(R.id.tvSource)
        tvFloatHint = findViewById(R.id.tvFloatHint)
        tvFontVal = findViewById(R.id.tvFontVal)
        tvAlphaVal = findViewById(R.id.tvAlphaVal)
        tvPerm = findViewById(R.id.tvPerm)
        seekFont = findViewById(R.id.seekFont)
        seekAlpha = findViewById(R.id.seekAlpha)
        rgDecimals = findViewById(R.id.rgDecimals)
        rgInterval = findViewById(R.id.rgInterval)
        swLock = findViewById(R.id.swLock)
        swBoot = findViewById(R.id.swBoot)
        swRoot = findViewById(R.id.swRoot)
        paletteCharge = findViewById(R.id.paletteCharge)
        paletteDischarge = findViewById(R.id.paletteDischarge)
        paletteIdle = findViewById(R.id.paletteIdle)
        btnFloat = findViewById(R.id.btnFloat)
    }

    // ---------------------------------------------------------------- 设置

    private fun bindSettings() {
        seekFont.max = 40
        seekFont.progress = (prefs.fontSize - 8).coerceIn(0, seekFont.max)
        tvFontVal.text = getString(R.string.sp_value, prefs.fontSize)
        seekFont.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar, progress: Int, fromUser: Boolean) {
                val v = progress + 8
                prefs.fontSize = v
                tvFontVal.text = getString(R.string.sp_value, v)
            }

            override fun onStartTrackingTouch(bar: SeekBar) = Unit
            override fun onStopTrackingTouch(bar: SeekBar) = Unit
        })

        seekAlpha.max = 100
        seekAlpha.progress = prefs.bgAlpha.coerceIn(0, 100)
        tvAlphaVal.text = getString(R.string.percent_value, prefs.bgAlpha)
        seekAlpha.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar, progress: Int, fromUser: Boolean) {
                prefs.bgAlpha = progress
                tvAlphaVal.text = getString(R.string.percent_value, progress)
            }

            override fun onStartTrackingTouch(bar: SeekBar) = Unit
            override fun onStopTrackingTouch(bar: SeekBar) = Unit
        })

        when (prefs.decimals) {
            0 -> rgDecimals.check(R.id.rbDec0)
            1 -> rgDecimals.check(R.id.rbDec1)
            else -> rgDecimals.check(R.id.rbDec2)
        }
        rgDecimals.setOnCheckedChangeListener { _, id ->
            prefs.decimals = when (id) {
                R.id.rbDec0 -> 0
                R.id.rbDec1 -> 1
                else -> 2
            }
        }

        when (prefs.sampleMs) {
            500 -> rgInterval.check(R.id.rb500)
            2000 -> rgInterval.check(R.id.rb2000)
            else -> rgInterval.check(R.id.rb1000)
        }
        rgInterval.setOnCheckedChangeListener { _, id ->
            prefs.sampleMs = when (id) {
                R.id.rb500 -> 500
                R.id.rb2000 -> 2000
                else -> 1000
            }
        }

        swLock.isChecked = prefs.locked
        swLock.setOnCheckedChangeListener { _, checked -> prefs.locked = checked }
        swBoot.isChecked = prefs.bootStart
        swBoot.setOnCheckedChangeListener { _, checked -> prefs.bootStart = checked }
        swRoot.isChecked = prefs.useRoot
        swRoot.setOnCheckedChangeListener { _, checked -> prefs.useRoot = checked }
    }

    private fun refreshPalettes() {
        fillPalette(paletteCharge, prefs.chargeColor) {
            prefs.chargeColor = it
            refreshPalettes()
        }
        fillPalette(paletteDischarge, prefs.dischargeColor) {
            prefs.dischargeColor = it
            refreshPalettes()
        }
        fillPalette(paletteIdle, prefs.idleColor) {
            prefs.idleColor = it
            refreshPalettes()
        }
    }

    private fun fillPalette(container: LinearLayout, selected: Int, onPick: (Int) -> Unit) {
        container.removeAllViews()
        for (color in PALETTE) {
            val dot = View(this)
            val size = dp(30)
            val params = LinearLayout.LayoutParams(size, size)
            params.marginEnd = dp(9)
            dot.layoutParams = params
            dot.background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(color)
                if (color == selected) {
                    setStroke(dp(3), 0xFFFFFFFF.toInt())
                } else {
                    setStroke(dp(1), 0x33FFFFFF)
                }
            }
            dot.setOnClickListener { onPick(color) }
            container.addView(dot)
        }
    }

    // ---------------------------------------------------------------- 实时显示

    private fun bindLive(s: Snapshot, st: SessionStats) {
        val color = when {
            s.charging -> prefs.chargeColor
            s.plugged -> prefs.idleColor
            else -> prefs.dischargeColor
        }
        val value = if (abs(s.watts) < 0.12f) 0f else abs(s.watts)
        val num = String.format(Locale.US, "%.${prefs.decimals}f", value)
        val prefix = if (s.charging && value >= 0.1f) "+" else ""
        tvBigPower.text = if (s.valid) "$prefix$num W" else "--.- W"
        tvBigPower.setTextColor(if (s.valid) color else 0xFF6A6F7A.toInt())

        tvState.text = s.statusText.ifEmpty { "读取中" }
        tvState.setTextColor(color)

        val parts = ArrayList<String>(4)
        if (s.voltage > 0.1f) parts.add(String.format(Locale.US, "%.2f V", s.voltage))
        if (s.valid) parts.add(String.format(Locale.US, "%.2f A", abs(s.current)))
        if (s.percent in 0..100) parts.add("${s.percent}%")
        if (!s.tempC.isNaN()) parts.add(String.format(Locale.US, "%.1f ℃", s.tempC))
        tvSub.text = if (parts.isEmpty()) "电压 --  ·  电流 --" else parts.joinToString("  ·  ")

        tvSession.text = if (st.millis > 3000) {
            val minutes = (st.millis / 60_000L).coerceAtLeast(1L)
            String.format(
                Locale.US,
                "%s：平均 %.1fW · 峰值 %.1fW · %.2fWh · %d 分钟",
                if (st.charging) "本次充电" else "本次使用",
                st.avgW, st.peakW, st.wh, minutes
            )
        } else {
            "本次统计：暂无记录"
        }

        tvSource.text = if (s.valid) {
            "数据源：${s.source}"
        } else {
            "数据源：读取失败，可点下方「诊断电池节点」"
        }
    }

    // ---------------------------------------------------------------- 悬浮窗

    private fun toggleFloat() {
        if (FloatService.running) {
            stopService(Intent(this, FloatService::class.java))
            ui.postDelayed({ refreshFloatUiSafely() }, 350)
            return
        }
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "请先授予悬浮窗权限", Toast.LENGTH_SHORT).show()
            requestOverlay()
            return
        }
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_NOTIFY
            )
        }
        runCatching {
            ContextCompat.startForegroundService(this, Intent(this, FloatService::class.java))
        }.onFailure {
            Toast.makeText(this, "启动失败：${it.message}", Toast.LENGTH_LONG).show()
        }
        ui.postDelayed({ refreshFloatUiSafely() }, 500)
    }

    private fun refreshFloatUiSafely() {
        if (!isFinishing && !isDestroyed) {
            refreshFloatUi()
            refreshPermissionState()
        }
    }

    private fun refreshFloatUi() {
        val running = FloatService.running
        btnFloat.text = if (running) "关闭悬浮窗" else "开启悬浮窗"
        tvFloatHint.text = when {
            running -> "悬浮窗运行中：可拖到任意位置，单击收起/展开详情"
            !Settings.canDrawOverlays(this) -> "需要先授予「显示在其他应用上层」权限"
            else -> "开启后回到桌面或任意 App 内都能看到实时功率"
        }
    }

    // ---------------------------------------------------------------- 状态栏开关

    private fun addQuickTile() {
        if (Build.VERSION.SDK_INT >= 33) {
            val sbm = getSystemService(StatusBarManager::class.java)
            if (sbm != null) {
                runCatching { sbm.requestAddTileService(
                    ComponentName(this, ChargeTile::class.java),
                    getString(R.string.tile_label),
                    Icon.createWithResource(this, R.drawable.ic_bolt),
                    mainExecutor
                ) { result ->
                    if (result == StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_NOT_ADDED) {
                        Toast.makeText(
                            this,
                            "已取消添加，可稍后在控制中心编辑页手动添加",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                } }.onFailure {
                    Toast.makeText(
                        this,
                        "系统不允许自动添加，请下拉状态栏 → 编辑 → 手动拖入「功率悬浮」",
                        Toast.LENGTH_LONG
                    ).show()
                }
                return
            }
        }
        Toast.makeText(
            this,
            "当前系统需要手动添加：下拉状态栏 → 编辑按钮 → 把「功率悬浮」拖到常用区",
            Toast.LENGTH_LONG
        ).show()
    }

    // ---------------------------------------------------------------- 权限

    private fun refreshPermissionState() {
        val overlay = Settings.canDrawOverlays(this)
        val notify = Build.VERSION.SDK_INT < 33 ||
                ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        val battery = pm.isIgnoringBatteryOptimizations(packageName)
        tvPerm.text = buildString {
            append("悬浮窗权限：").append(if (overlay) "已授予" else "未授予")
            append("\n通知权限：").append(if (notify) "已授予" else "未授予")
            append("\n电池优化：").append(if (battery) "已忽略（推荐）" else "未忽略")
        }
    }

    private fun requestOverlay() {
        val intent = Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:$packageName")
        )
        if (runCatching { startActivity(intent) }.isFailure) {
            runCatching { startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION)) }
        }
    }

    private fun requestNotification() {
        if (Build.VERSION.SDK_INT >= 33) {
            ActivityCompat.requestPermissions(
                this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_NOTIFY
            )
        } else {
            Toast.makeText(this, "当前系统无需额外授权", Toast.LENGTH_SHORT).show()
        }
    }

    private fun requestBattery() {
        val intent = Intent(
            Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
            Uri.parse("package:$packageName")
        )
        if (runCatching { startActivity(intent) }.isFailure) {
            runCatching {
                startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            }
        }
    }

    private fun openMiuiPermissions() {
        val candidates = listOf(
            Intent().setClassName(
                "com.miui.securitycenter",
                "com.miui.permcenter.permissions.PermissionsEditorActivity"
            ).putExtra("extra_pkgname", packageName),
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))
        )
        for (intent in candidates) {
            if (runCatching { startActivity(intent) }.isSuccess) return
        }
        Toast.makeText(this, "没有找到小米安全中心，请手动在系统设置中授权", Toast.LENGTH_LONG).show()
    }

    // ---------------------------------------------------------------- 诊断

    private fun showDiagnose() {
        Toast.makeText(this, "正在读取节点…", Toast.LENGTH_SHORT).show()
        Thread {
            val text = runCatching { reader.diagnose() }.getOrElse { "读取失败：${it.message}" }
            ui.post { showDiagDialog(text) }
        }.start()
    }

    private fun showDiagDialog(text: String) {
        if (isFinishing || isDestroyed) return
        AlertDialog.Builder(this)
            .setTitle("电池节点诊断")
            .setMessage(text)
            .setPositiveButton("复制") { _, _ ->
                val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("battery-diag", text))
                Toast.makeText(this, "已复制到剪贴板", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("关闭", null)
            .show()
    }

    // ---------------------------------------------------------------- 生命周期

    override fun onResume() {
        super.onResume()
        refreshPermissionState()
        refreshFloatUi()
        ticking = true
        bg.post(tick)
    }

    override fun onPause() {
        super.onPause()
        ticking = false
        bg.removeCallbacksAndMessages(null)
    }

    override fun onDestroy() {
        ticking = false
        bg.removeCallbacksAndMessages(null)
        worker.quitSafely()
        super.onDestroy()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        refreshPermissionState()
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density + 0.5f).toInt()

    companion object {
        private const val REQ_NOTIFY = 101

        private val PALETTE = intArrayOf(
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
}
