package com.k80.chargefloat

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import java.util.Locale
import kotlin.math.abs

/**
 * 悬浮窗前台服务：每秒采样一次，把功率画在屏幕上。
 */
class FloatService : Service() {

    companion object {
        const val CHANNEL_ID = "float_window"
        const val NOTIFICATION_ID = 4101

        @Volatile
        var running = false
            private set
    }

    private lateinit var wm: WindowManager
    private lateinit var prefs: Prefs
    private lateinit var reader: PowerReader

    private var root: LinearLayout? = null
    private var rowLayout: LinearLayout? = null
    private var tvPower: TextView? = null
    private var tvDetail: TextView? = null
    private var tvSession: TextView? = null
    private var lp: WindowManager.LayoutParams? = null

    private lateinit var worker: HandlerThread
    private lateinit var bg: Handler
    private val ui = Handler(Looper.getMainLooper())

    private var active = false
    private var lastSnapshot: Snapshot? = null

    private val prefsWatcher = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
        ui.post { lastSnapshot?.let { render(it) } }
    }

    private val sampler = object : Runnable {
        override fun run() {
            if (!active) return
            val snap = runCatching { reader.read() }.getOrNull()
            if (snap != null) {
                PowerBus.publish(snap, reader.stats())
                ui.post { render(snap) }
            }
            bg.postDelayed(this, prefs.sampleMs.toLong().coerceIn(300L, 5000L))
        }
    }

    override fun onCreate() {
        super.onCreate()
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        prefs = Prefs(this)
        reader = PowerReader(this)
        worker = HandlerThread("power-sampler").also { it.start() }
        bg = Handler(worker.looper)
        createChannel()
        prefs.raw().registerOnSharedPreferenceChangeListener(prefsWatcher)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            buildNotification(),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
        )

        if (!Settings.canDrawOverlays(this)) {
            stopSelf()
            return START_NOT_STICKY
        }

        if (!active) {
            active = true
            running = true
            showOverlay()
            bg.post(sampler)
        }
        return START_STICKY
    }

    override fun onDestroy() {
        active = false
        running = false
        bg.removeCallbacksAndMessages(null)
        prefs.raw().unregisterOnSharedPreferenceChangeListener(prefsWatcher)
        root?.let { v ->
            runCatching { wm.removeView(v) }
        }
        root = null
        PowerBus.clear()
        worker.quitSafely()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ---------------------------------------------------------------- 悬浮窗

    private fun showOverlay() {
        if (root != null) return

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.START
            setPadding(dp(10), dp(5), dp(10), dp(6))
        }

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.START
            isBaselineAligned = true
        }
        rowLayout = row

        tvPower = TextView(this).apply {
            gravity = Gravity.START
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            includeFontPadding = false
            setShadowLayer(dp(4).toFloat(), 0f, dp(1).toFloat(), 0x66000000)
            setTextColor(Color.WHITE)
            text = "--.- W"
        }
        tvDetail = TextView(this).apply {
            gravity = Gravity.START
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            setTextColor(0xCCFFFFFF.toInt())
            includeFontPadding = false
        }
        tvSession = TextView(this).apply {
            gravity = Gravity.START
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
            setTextColor(0x99FFFFFF.toInt())
            includeFontPadding = false
        }

        row.addView(tvPower, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ))
        row.addView(tvDetail, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(1) })

        container.addView(row, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ))
        container.addView(tvSession, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(1) })

        val type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            val dm = resources.displayMetrics
            x = if (prefs.posX >= 0) prefs.posX.coerceIn(0, (dm.widthPixels - dp(60)).coerceAtLeast(0))
            else (dm.widthPixels - dp(150)).coerceAtLeast(0)
            y = if (prefs.posY >= 0) prefs.posY.coerceIn(0, (dm.heightPixels - dp(60)).coerceAtLeast(0))
            else dp(120)
        }

        container.background = panelBackground(prefs.bgAlpha)

        attachDrag(container, params)

        root = container
        lp = params
        runCatching { wm.addView(container, params) }

        lastSnapshot?.let { render(it) }
    }

    private fun attachDrag(view: View, params: WindowManager.LayoutParams) {
        view.setOnTouchListener(object : View.OnTouchListener {
            private var startX = 0
            private var startY = 0
            private var touchX = 0f
            private var touchY = 0f
            private var moved = false

            override fun onTouch(v: View, e: MotionEvent): Boolean {
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        startX = params.x
                        startY = params.y
                        touchX = e.rawX
                        touchY = e.rawY
                        moved = false
                        return true
                    }

                    MotionEvent.ACTION_MOVE -> {
                        val dx = (e.rawX - touchX).toInt()
                        val dy = (e.rawY - touchY).toInt()
                        if (!moved && (abs(dx) > dp(6) || abs(dy) > dp(6))) moved = true
                        if (moved && !prefs.locked) {
                            val dm = resources.displayMetrics
                            val minX = -v.width / 3
                            val minY = 0
                            val maxX = (dm.widthPixels - v.width / 3).coerceAtLeast(minX)
                            val maxY = (dm.heightPixels - v.height / 3).coerceAtLeast(minY)
                            params.x = (startX + dx).coerceIn(minX, maxX)
                            params.y = (startY + dy).coerceIn(minY, maxY)
                            runCatching { wm.updateViewLayout(v, params) }
                        }
                        return true
                    }

                    MotionEvent.ACTION_UP -> {
                        if (!moved) {
                            // 单击：临时收起/展开详情
                            prefs.showDetail = !prefs.showDetail
                            lastSnapshot?.let { render(it) }
                        } else if (!prefs.locked) {
                            prefs.posX = params.x
                            prefs.posY = params.y
                        }
                        return true
                    }
                }
                return false
            }
        })
    }

    private fun render(s: Snapshot) {
        lastSnapshot = s
        val p = prefs
        val powerView = tvPower ?: return
        val detailView = tvDetail ?: return
        val sessionView = tvSession ?: return

        val color = when {
            s.charging -> p.chargeColor
            s.plugged -> p.idleColor
            else -> p.dischargeColor
        }

        val value = if (abs(s.watts) < 0.12f) 0f else abs(s.watts)
        val num = String.format(Locale.US, "%.${p.decimals}f", value)
        val prefix = if (s.charging && value >= 0.1f) "+" else ""
        powerView.text = if (s.valid) "$prefix$num W" else "--.- W"
        powerView.setTextColor(if (s.valid) color else 0xFF9AA0AA.toInt())
        powerView.setTextSize(TypedValue.COMPLEX_UNIT_SP, p.fontSize.toFloat())
        powerView.minWidth = 0

        val style = p.layoutStyle
        val compact = style == 0
        rowLayout?.let { row ->
            row.orientation = if (compact) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
            row.gravity = if (compact) Gravity.START or Gravity.CENTER_VERTICAL else Gravity.START
            row.isBaselineAligned = compact
        }
        (detailView.layoutParams as? LinearLayout.LayoutParams)?.let { dlp ->
            if (compact) {
                dlp.leftMargin = dp(7)
                dlp.topMargin = 0
            } else {
                dlp.leftMargin = 0
                dlp.topMargin = dp(1)
            }
            detailView.layoutParams = dlp
        }

        val line1 = ArrayList<String>(4)
        if (p.showDetail) {
            if (s.voltage > 0.1f) line1.add(String.format(Locale.US, "%.2fV", s.voltage))
            if (s.valid) line1.add(String.format(Locale.US, "%.2fA", abs(s.current)))
        }
        if (p.showBattery) {
            if (s.percent in 0..100) line1.add("${s.percent}%")
            if (!s.tempC.isNaN()) line1.add(String.format(Locale.US, "%.1f℃", s.tempC))
        }
        detailView.text = line1.joinToString(" · ")
        val small = (p.fontSize * 0.32f).coerceAtLeast(7f)
        detailView.setTextSize(TypedValue.COMPLEX_UNIT_SP, small)
        detailView.visibility = if (line1.isEmpty()) View.GONE else View.VISIBLE

        if (style == 2) {
            val st = PowerBus.stats
            val line2 = ArrayList<String>(3)
            line2.add(s.statusText.ifEmpty { "—" })
            if (p.showSession && st.millis > 0) {
                line2.add(String.format(Locale.US, "峰值%.0fW", st.peakW))
                line2.add(String.format(Locale.US, "%.1fWh", st.wh))
                val min = st.millis / 60000L
                if (min >= 1) line2.add("${min}分")
            }
            sessionView.text = line2.joinToString(" · ")
            sessionView.setTextSize(TypedValue.COMPLEX_UNIT_SP, (small * 0.88f).coerceAtLeast(6.5f))
            sessionView.visibility = View.VISIBLE
        } else {
            sessionView.visibility = View.GONE
        }

        (root?.background as? GradientDrawable)?.let { g ->
            g.setColor(Color.argb((p.bgAlpha * 255 / 100).coerceIn(0, 255), 8, 8, 12))
            g.cornerRadius = dp(12).toFloat()
        }

        root?.let { v ->
            v.requestLayout()
            lp?.let { params -> runCatching { wm.updateViewLayout(v, params) } }
        }
    }

    private fun panelBackground(alphaPercent: Int): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(12).toFloat()
            setColor(Color.argb((alphaPercent * 255 / 100).coerceIn(0, 255), 8, 8, 12))
            setStroke(dp(1), 0x22FFFFFF)
        }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density + 0.5f).toInt()

    // ---------------------------------------------------------------- 通知

    private fun createChannel() {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "悬浮窗运行状态",
                NotificationManager.IMPORTANCE_MIN
            ).apply {
                description = "保持充电功率悬浮窗运行"
                setShowBadge(false)
                enableLights(false)
                enableVibration(false)
            }
            nm.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val contentIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_bolt)
            .setContentTitle("充电功率悬浮窗")
            .setContentText("正在显示实时功率")
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setOngoing(true)
            .setSilent(true)
            .setContentIntent(contentIntent)
            .build()
    }
}
