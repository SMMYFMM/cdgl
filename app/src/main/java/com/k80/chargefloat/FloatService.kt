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
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Point
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowInsets
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

    /** 拖动中：暂停重绘，避免和窗口移动抢主线程 */
    private var dragging = false

    /** 缓存的状态栏高度，配置变化时清空 */
    private var cachedStatusBar = 0

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
        root?.let { v ->
            // 停止服务前再存一次，防止最后一次拖动没收到 ACTION_UP
            lp?.let { prefs.savePosition(it.x, it.y) }
            runCatching { wm.removeView(v) }
        }
        root = null
        PowerBus.clear()
        worker.quitSafely()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // 横竖屏的状态栏高度不一样，重新取
        cachedStatusBar = 0
    }

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

        val screen = screenSize()
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START

            // 不设这两个，系统会把窗口限制在状态栏和导航栏之间
            if (Build.VERSION.SDK_INT >= 28) {
                layoutInDisplayCutoutMode = if (Build.VERSION.SDK_INT >= 30) {
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                } else {
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                }
            }
            if (Build.VERSION.SDK_INT >= 30) {
                setFitInsetsTypes(0)
                setFitInsetsSides(0)
                setFitInsetsIgnoringVisibility(true)
            }

            // 顶部最低停在状态栏下方：进入状态栏区域后触摸会被状态栏截走，窗口就再也拖不下来了
            val top = statusBarHeight()
            if (prefs.hasPosition) {
                // 不能用 x >= 0 判断：贴左边缘时 x 是负的
                x = prefs.posX.coerceIn(-screen.x / 2, (screen.x - dp(24)).coerceAtLeast(0))
                y = prefs.posY.coerceIn(top, (screen.y - dp(24)).coerceAtLeast(top))
            } else {
                x = (screen.x - dp(150)).coerceAtLeast(0)
                y = maxOf(dp(120), top)
            }
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
            private var minX = 0
            private var maxX = 0
            private var minY = 0
            private var maxY = 0
            private var lastApply = 0L

            override fun onTouch(v: View, e: MotionEvent): Boolean {
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        startX = params.x
                        startY = params.y
                        touchX = e.rawX
                        touchY = e.rawY
                        moved = false
                        lastApply = 0L

                        // 屏幕尺寸和边界只在按下时算一次。
                        // screenSize() 内部是 maximumWindowMetrics，走 Binder，
                        // 每帧调一次会让拖动明显发涩。
                        val screen = screenSize()
                        val top = statusBarHeight()
                        minX = -v.width / 3
                        maxX = (screen.x - v.width / 3).coerceAtLeast(minX)
                        minY = top
                        maxY = (screen.y - v.height / 3).coerceAtLeast(minY)
                        return true
                    }

                    MotionEvent.ACTION_MOVE -> {
                        val dx = (e.rawX - touchX).toInt()
                        val dy = (e.rawY - touchY).toInt()
                        if (!moved && (abs(dx) > dp(5) || abs(dy) > dp(5))) {
                            moved = true
                            dragging = true
                        }
                        if (moved && !prefs.locked) {
                            // 高刷屏的触摸报点比屏幕刷新快，合并到约 120Hz 再提交
                            val now = SystemClock.uptimeMillis()
                            if (lastApply != 0L && now - lastApply < 8L) return true
                            lastApply = now

                            val targetX = startX + dx
                            val targetY = startY + dy
                            val cx = targetX.coerceIn(minX, maxX)
                            val cy = targetY.coerceIn(minY, maxY)
                            // 顶到边界后把基准一起拉回，否则手指离开边界时窗口要先"追"一段才动
                            if (cx != targetX) startX += targetX - cx
                            if (cy != targetY) startY += targetY - cy

                            params.x = cx
                            params.y = cy
                            try {
                                wm.updateViewLayout(v, params)
                            } catch (_: Throwable) {
                            }
                        }
                        return true
                    }

                    MotionEvent.ACTION_UP -> {
                        dragging = false
                        if (!moved) {
                            // 单击：折叠成只显示第一行
                            prefs.showDetail = !prefs.showDetail
                        } else if (!prefs.locked) {
                            prefs.savePosition(params.x, params.y)
                        }
                        // 拖动期间跳过了重绘，这里补一次
                        lastSnapshot?.let { draw(it, PowerBus.stats) }
                        return true
                    }

                    MotionEvent.ACTION_CANCEL -> {
                        // 拖到边缘时可能被系统手势打断，收不到 ACTION_UP，这里也要存
                        dragging = false
                        if (moved && !prefs.locked) {
                            prefs.savePosition(params.x, params.y)
                        }
                        lastSnapshot?.let { draw(it, PowerBus.stats) }
                        return true
                    }
                }
                return false
            }
        })
    }

    private fun draw(s: Snapshot, st: SessionStats) {
        if (!active) return
        // 拖动中不重排窗口：此时每帧都在移动，重排会抢主线程造成掉帧
        if (dragging) return
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
        }
    }

    /** 状态栏高度。窗口顶部不能高于这里，否则触摸会被状态栏截走，窗口就拖不下来了 */
    private fun statusBarHeight(): Int {
        if (cachedStatusBar > 0) return cachedStatusBar
        val h = runCatching {
            if (Build.VERSION.SDK_INT >= 30) {
                wm.currentWindowMetrics.windowInsets
                    .getInsetsIgnoringVisibility(WindowInsets.Type.statusBars()).top
            } else {
                val id = resources.getIdentifier("status_bar_height", "dimen", "android")
                if (id > 0) resources.getDimensionPixelSize(id) else 0
            }
        }.getOrDefault(0)
        cachedStatusBar = if (h > 0) h else dp(28)
        return cachedStatusBar
    }

    /** 包含状态栏、导航栏和挖孔区域的真实屏幕尺寸 */
    private fun screenSize(): Point {
        val p = Point()
        if (Build.VERSION.SDK_INT >= 30) {
            val b = wm.maximumWindowMetrics.bounds
            p.set(b.width(), b.height())
        } else {
            @Suppress("DEPRECATION")
            wm.defaultDisplay.getRealSize(p)
        }
        return p
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
