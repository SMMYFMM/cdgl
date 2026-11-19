package com.k80.chargefloat

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.SystemClock
import java.io.File
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max

/** 一次采样结果。watts 有符号：正=充入电池，负=电池放电。 */
data class Snapshot(
    val watts: Float = 0f,
    val voltage: Float = 0f,
    val current: Float = 0f,
    val percent: Int = -1,
    val tempC: Float = Float.NaN,
    val charging: Boolean = false,
    val plugged: Boolean = false,
    val statusText: String = "",
    val source: String = "",
    val valid: Boolean = false
)

/** 一次充电过程（从插上充电器开始）的统计。 */
data class SessionStats(
    val charging: Boolean = false,
    val peakW: Float = 0f,
    val avgW: Float = 0f,
    val wh: Float = 0f,
    val millis: Long = 0L,
    val startPercent: Int = -1,
    val endPercent: Int = -1
)

/**
 * 功率读取器。
 *
 * 优先读 /sys/class/power_supply 下的节点（小米/红米上一般可读，精度最高），
 * 读不到时退回 BatteryManager.BATTERY_PROPERTY_CURRENT_NOW，再退回广播里的电压。
 * 功率 P = U × I，取电池端的数据（和 Scene 的充电统计口径一致）。
 */
class PowerReader(private val context: Context) {

    private val bm = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
    private val prefs = Prefs(context)
    private val stickyFilter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)

    private var currentPath: String? = null
    private var voltagePath: String? = null
    private var inputCurrentPath: String? = null
    private var inputVoltagePath: String? = null
    private var pathsReady = false

    private var ema = Float.NaN
    private var lastSampleTs = 0L
    private var sessionStartTs = 0L
    private var sessionPeak = 0f
    private var sessionSum = 0f
    private var sessionCount = 0
    private var sessionWh = 0f
    private var sessionCharging = false
    private var sessionStartPercent = -1
    private var sessionActive = false
    private var lastSession = SessionStats()
    private var lastCharge = SessionStats()

    /** 缓存：这台机器上 root 是否可用（只检测一次，且只在用户开启开关后才检测） */
    private val rootAvailable: Boolean by lazy { prefs.useRoot && probeRoot() }

    // ---------------------------------------------------------------- 采样

    fun read(): Snapshot {
        val sticky = runCatching { context.registerReceiver(null, stickyFilter) }.getOrNull()

        val rawStatus = sticky?.getIntExtra(
            BatteryManager.EXTRA_STATUS, BatteryManager.BATTERY_STATUS_UNKNOWN
        ) ?: BatteryManager.BATTERY_STATUS_UNKNOWN
        val plugged = sticky?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0
        val level = sticky?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = sticky?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
        val tempTenth = sticky?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0
        val extraVoltMv = sticky?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0) ?: 0

        val percent = when {
            level >= 0 && scale > 0 -> level * 100 / scale
            else -> intProp(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        }
        val tempC = if (tempTenth > 0) tempTenth / 10f else Float.NaN

        val charging = rawStatus == BatteryManager.BATTERY_STATUS_CHARGING ||
                rawStatus == BatteryManager.BATTERY_STATUS_FULL
        val pluggedIn = plugged != 0

        resolvePaths()

        // ---- 电流
        var currentA = Float.NaN
        var source = ""
        currentPath?.let { p ->
            val raw = readNode(p)
            if (raw != null) {
                val a = toAmps(raw)
                if (a.isFinite() && abs(a) < 30f) {
                    currentA = a
                    source = "sysfs/" + shortName(p)
                }
            }
        }
        if (currentA.isNaN() || (abs(currentA) < 0.0005f && pluggedIn)) {
            val ua = intProp(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)
            if (ua != Int.MIN_VALUE && ua != 0 && abs(ua) < 30_000_000) {
                currentA = ua / 1_000_000f
                source = "BatteryManager"
            }
        }

        // ---- 电压
        var volts = Float.NaN
        voltagePath?.let { p ->
            val raw = readNode(p)
            if (raw != null) {
                val v = toVolts(raw)
                if (v.isFinite() && v in 2.0f..6.5f) volts = v
            }
        }
        if (volts.isNaN() && extraVoltMv > 0) {
            val v = extraVoltMv / 1000f
            if (v in 2.0f..6.5f) volts = v
        }

        val valid = currentA.isFinite() && volts.isFinite()

        // ---- 功率：符号按充电状态归一化（不同厂商 current_now 符号相反）
        val magnitude = if (valid) abs(volts * currentA) else 0f
        val signed = if (charging) magnitude else -magnitude

        ema = when {
            ema.isNaN() -> signed
            abs(signed - ema) > 12f -> signed          // 功率突变（插拔/快充档位切换）直接跟上
            else -> ema + (signed - ema) * 0.35f
        }
        val watts = if (valid) ema else 0f

        updateSession(charging, magnitude, percent)

        val statusText = when {
            charging && rawStatus == BatteryManager.BATTERY_STATUS_FULL -> "已充满"
            charging -> "充电中"
            pluggedIn -> "已连接电源"
            else -> "放电中"
        }

        return Snapshot(
            watts = watts,
            voltage = if (volts.isFinite()) volts else 0f,
            current = if (currentA.isFinite()) currentA else 0f,
            percent = percent,
            tempC = tempC,
            charging = charging,
            plugged = pluggedIn,
            statusText = statusText,
            source = source,
            valid = valid
        )
    }

    /** 当前统计窗口 */
    fun stats(): SessionStats = lastSession

    /** 上一次完整的充电统计（拔掉充电器后仍然可查） */
    fun lastChargeStats(): SessionStats = lastCharge

    /** 输入侧功率（充电器到手机），多数机型读不到就返回 NaN */
    fun inputWatts(): Float {
        resolvePaths()
        val c = inputCurrentPath?.let { readNode(it) } ?: return Float.NaN
        val v = inputVoltagePath?.let { readNode(it) } ?: return Float.NaN
        val a = toAmps(c)
        val volt = toVolts(v)
        if (!a.isFinite() || !volt.isFinite() || volt < 3f || volt > 30f) return Float.NaN
        return abs(a * volt)
    }

    // ---------------------------------------------------------------- 统计

    /**
     * 统计窗口：插上/拔掉充电器都会开一个新窗口。
     * 充电时窗口就是"本次充电"，放电时就是"本次使用"，平均功率两种场景都成立。
     */
    private fun updateSession(charging: Boolean, magnitude: Float, percent: Int) {
        val now = SystemClock.elapsedRealtime()

        if (!sessionActive || charging != sessionCharging) {
            // 充电刚结束：把这一轮充电的完整统计留一份
            if (sessionActive && sessionCharging && !charging) {
                lastCharge = lastSession.copy(charging = false, endPercent = percent)
            }
            sessionActive = true
            sessionCharging = charging
            sessionStartTs = now
            sessionStartPercent = percent
            sessionPeak = 0f
            sessionSum = 0f
            sessionCount = 0
            sessionWh = 0f
            lastSampleTs = 0L
        }

        val dt = if (lastSampleTs > 0) now - lastSampleTs else 0L
        // 息屏时采样间隔会拉长到 20 秒，这里留出余量，避免把电量积分丢掉
        if (dt in 1..65_000L) {
            sessionWh += magnitude * dt / 1000f / 3600f
        } else if (dt > 65_000L && sessionCount > 0) {
            // 设备休眠期间回调不会执行，醒来后按窗口均值补上这段时间，避免统计少算一大截。
            // 不为了统计去唤醒设备，精度用均值近似。
            val avg = sessionSum / sessionCount
            val capped = dt.coerceAtMost(6 * 3600_000L)
            sessionWh += avg * capped / 1000f / 3600f
        }
        sessionPeak = max(sessionPeak, magnitude)
        sessionSum += magnitude
        sessionCount++
        lastSession = SessionStats(
            charging = charging,
            peakW = sessionPeak,
            avgW = if (sessionCount > 0) sessionSum / sessionCount else 0f,
            wh = sessionWh,
            millis = now - sessionStartTs,
            startPercent = sessionStartPercent,
            endPercent = percent
        )
        lastSampleTs = now
    }

    // ---------------------------------------------------------------- 节点探测

    private fun resolvePaths() {
        if (pathsReady) return
        pathsReady = true
        currentPath = firstReadable(CURRENT_CANDIDATES)
        voltagePath = firstReadable(VOLTAGE_CANDIDATES)
        inputCurrentPath = firstReadable(INPUT_CURRENT_CANDIDATES)
        inputVoltagePath = firstReadable(INPUT_VOLTAGE_CANDIDATES)
    }

    private fun firstReadable(paths: List<String>): String? {
        var fallback: String? = null
        for (p in paths) {
            val v = readNode(p) ?: continue
            if (v != 0L) return p
            if (fallback == null) fallback = p
        }
        return fallback
    }

    private fun readNode(path: String): Long? {
        // 普通应用能读就直接读，省掉 su 的开销
        val direct = runCatching { File(path).readText().trim().toLongOrNull() }.getOrNull()
        if (direct != null) return direct
        if (!prefs.useRoot || !rootAvailable) return null
        return runCatching {
            val proc = ProcessBuilder("su", "-c", "cat $path")
                .redirectErrorStream(true)
                .start()
            val out = proc.inputStream.bufferedReader().use { it.readText() }.trim()
            proc.waitFor()
            out.toLongOrNull()
        }.getOrNull()
    }

    private fun probeRoot(): Boolean = runCatching {
        val proc = ProcessBuilder("su", "-c", "id").redirectErrorStream(true).start()
        val out = proc.inputStream.bufferedReader().use { it.readText() }
        proc.waitFor()
        out.contains("uid=0")
    }.getOrDefault(false)

    private fun toAmps(raw: Long): Float {
        val a = abs(raw)
        return when {
            a >= 10_000L -> raw / 1_000_000f   // µA
            a >= 100L -> raw / 1_000f          // mA
            else -> raw / 1_000_000f
        }
    }

    private fun toVolts(raw: Long): Float {
        val a = abs(raw)
        return when {
            a >= 100_000L -> raw / 1_000_000f  // µV
            a >= 1_000L -> raw / 1_000f        // mV
            else -> raw.toFloat()
        }
    }

    private fun intProp(prop: Int): Int =
        runCatching { bm.getIntProperty(prop) }.getOrDefault(Int.MIN_VALUE)

    private fun shortName(path: String): String =
        path.substringAfter("/power_supply/", path)

    // ---------------------------------------------------------------- 诊断

    fun diagnose(): String {
        val sb = StringBuilder()
        sb.append("机型：").append(Build.MANUFACTURER).append(' ').append(Build.MODEL).append('\n')
        sb.append("系统：Android ").append(Build.VERSION.RELEASE)
            .append(" / API ").append(Build.VERSION.SDK_INT).append('\n')
        sb.append("Root：").append(
            when {
                !prefs.useRoot -> "未开启"
                rootAvailable -> "可用"
                else -> "不可用"
            }
        ).append("\n\n")

        val dirs = listOf("battery", "bms", "usb", "charger", "main", "maxfg", "dc", "pc_port", "wireless")
        val files = listOf(
            "current_now", "voltage_now", "voltage_avg", "power_now",
            "input_current_now", "input_voltage_now", "online", "status",
            "temp", "charge_counter", "voltage_max", "current_max", "constant_charge_current"
        )
        for (d in dirs) {
            val base = "/sys/class/power_supply/$d"
            if (!File(base).exists()) continue
            sb.append('[').append(d).append("]\n")
            for (f in files) {
                val path = "$base/$f"
                val file = File(path)
                if (!file.exists()) continue
                val text = when {
                    file.canRead() -> runCatching { file.readText().trim() }.getOrDefault("?")
                    rootAvailable -> runCatching {
                        val p = ProcessBuilder("su", "-c", "cat $path").start()
                        val out = p.inputStream.bufferedReader().use { it.readText() }.trim()
                        p.waitFor()
                        out.ifEmpty { "空" }
                    }.getOrDefault("读失败")
                    else -> "无权限"
                }
                sb.append("  ").append(f).append(" = ").append(text).append('\n')
            }
        }

        sb.append("\n[BatteryManager]\n")
        sb.append("  电流 CURRENT_NOW = ").append(intProp(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)).append(" µA\n")
        sb.append("  电量 CAPACITY = ").append(intProp(BatteryManager.BATTERY_PROPERTY_CAPACITY)).append(" %\n")
        sb.append("  电量计 CHARGE_COUNTER = ").append(intProp(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER)).append('\n')
        sb.append("\n当前使用：")
        sb.append(currentPath ?: "BatteryManager")
        sb.append(" / ").append(voltagePath ?: "广播电压").append('\n')
        sb.append(String.format(Locale.US, "输入侧功率：%.1f W\n", inputWatts()))
        return sb.toString()
    }

    companion object {
        private val CURRENT_CANDIDATES = listOf(
            "/sys/class/power_supply/battery/current_now",
            "/sys/class/power_supply/bms/current_now",
            "/sys/class/power_supply/Battery/current_now",
            "/sys/class/power_supply/maxfg/current_now",
            "/sys/class/power_supply/battery/real_time_current"
        )

        private val VOLTAGE_CANDIDATES = listOf(
            "/sys/class/power_supply/battery/voltage_now",
            "/sys/class/power_supply/bms/voltage_now",
            "/sys/class/power_supply/Battery/voltage_now",
            "/sys/class/power_supply/maxfg/voltage_now",
            "/sys/class/power_supply/battery/voltage_avg"
        )

        private val INPUT_CURRENT_CANDIDATES = listOf(
            "/sys/class/power_supply/usb/current_now",
            "/sys/class/power_supply/usb/input_current_now",
            "/sys/class/power_supply/charger/current_now",
            "/sys/class/power_supply/main/current_now",
            "/sys/class/power_supply/dc/current_now",
            "/sys/class/power_supply/battery/input_current_now"
        )

        private val INPUT_VOLTAGE_CANDIDATES = listOf(
            "/sys/class/power_supply/usb/voltage_now",
            "/sys/class/power_supply/usb/voltage_avg",
            "/sys/class/power_supply/charger/voltage_now",
            "/sys/class/power_supply/main/voltage_now",
            "/sys/class/power_supply/battery/input_voltage_now"
        )
    }
}

/** 服务与界面之间的数据总线（同进程，够用）。 */
object PowerBus {
    @Volatile
    var snapshot: Snapshot? = null
        private set

    @Volatile
    var stats: SessionStats = SessionStats()
        private set

    fun publish(s: Snapshot, st: SessionStats) {
        snapshot = s
        stats = st
    }

    fun clear() {
        snapshot = null
        stats = SessionStats()
    }
}
