package com.k80.chargefloat

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kotlin.math.abs

/**
 * 悬浮窗前台服务。
 *
 * 功耗控制：
 *  - 息屏时不再画界面（也看不见），采样间隔放宽到 20 秒，只维持统计积分；
 *  - 内容没变化不调用 updateViewLayout，避免每秒一次的跨进程窗口刷新；
 *  - 采样回调没有 wakelock，设备睡着时自然不执行，不会为了刷新把手机唤醒。
 */
class FloatService : Service() {

    companion object {
        const val CHANNEL_ID = "float_window"
        const val NOTIFICATION_ID = 4101

        /** 息屏后的采样间隔 */
        private const val IDLE_SAMPLE_MS = 20_000L

        @Volatile
        var running = false
            private set
    }

    private lateinit var wm: WindowManager
    private lateinit var prefs: Prefs
    private lateinit var reader: PowerReader
    private lateinit var renderer: OverlayRenderer
    private var spec: LayoutSpec? = null

    private var root: View? = null
    private var lp: WindowManager.LayoutParams? = null

    private lateinit var worker: HandlerThread
    private lateinit var bg: Handler
    private val ui = Handler(Looper.getMainLooper())

    private var active = false

    @Volatile
    private var interactive = true

    private var lastSnapshot: Snapshot? = null

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_ON -> {
                    interactive = true
                    if (active) {
                        bg.removeCallbacks(sampler)
                        bg.post(sampler)
                    }
                }

                Intent.ACTION_SCREEN_OFF -> interactive = false
            }
        }
    }

    private val prefsWatcher = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
        spec = LayoutSpec.load(prefs)
        ui.post {
            if (!active) return@post
            val cfg = spec
            val snap = lastSnapshot
            if (cfg != null && snap != null) {
                renderer.invalidate()
                if (renderer.render(snap, PowerBus.stats, prefs, cfg, !prefs.showDetail)) {
                    relayout()
                }
            }
            applyBackground()
        }
    }

    private val sampler = object : Runnable {
        override fun run() {
            if (!active) return
            val snap = runCatching { reader.read() }.getOrNull()
            if (snap != null) {
                val st = reader.stats()
                PowerBus.publish(snap, st)
                lastSnapshot = snap
                // 息屏时不碰界面，只让统计继续
                if (interactive) ui.post { if (active) draw(snap, st) }
            }
            val delay = if (interactive) prefs.sampleMs.toLong().coerceIn(300L, 10_000L)
            else IDLE_SAMPLE_MS
            bg.postDelayed(this, delay)
        }
    }

    override fun onCreate() {
        super.onCreate()
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        prefs = Prefs(this)
        reader = PowerReader(this)
        spec = LayoutSpec.load(prefs)
        renderer = OverlayRenderer(this)
        worker = HandlerThread("power-sampler").also { it.start() }
        bg = Handler(worker.looper)
        interactive = (getSystemService(POWER_SERVICE) as PowerManager).isInteractive
        createChannel()
        prefs.raw().registerOnSharedPreferenceChangeListener(prefsWatcher)
        ContextCompat.registerReceiver(
            this,
            screenReceiver,
            IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_SCREEN_OFF)
            },
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
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
        runCatching { unregisterReceiver(screenReceiver) }
        root?.let { v -> runCatching { wm.removeView(v) } }
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
        container.addView(
            renderer.view,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )

        val dm = resources.displayMetrics
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = if (prefs.posX >= 0) prefs.posX.coerceIn(0, (dm.widthPixels - dp(60)).coerceAtLeast(0))
            else (dm.widthPixels - dp(150)).coerceAtLeast(0)
            y = if (prefs.posY >= 0) prefs.posY.coerceIn(0, (dm.heightPixels - dp(60)).coerceAtLeast(0))
            else dp(120)
        }

        applyBackground(container)
        attachDrag(container, params)

        root = container
        lp = params
        runCatching { wm.addView(container, params) }

        lastSnapshot?.let { draw(it, PowerBus.stats) }
    }

    private fun applyBackground(target: View? = root) {
        val v = target ?: return
        v.background = panelBackground(prefs.bgAlpha)
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
                            // 单击：折叠成只显示第一行
                            prefs.showDetail = !prefs.showDetail
                            lastSnapshot?.let { draw(it, PowerBus.stats) }
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

    private fun draw(s: Snapshot, st: SessionStats) {
        if (!active) return
        val cfg = spec ?: return
        if (renderer.render(s, st, prefs, cfg, !prefs.showDetail)) relayout()
    }

    private fun relayout() {
        root?.let { v -> lp?.let { p -> runCatching { wm.updateViewLayout(v, p) } } }
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
