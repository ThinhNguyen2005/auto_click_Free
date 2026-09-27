package com.giathinh.auto_click_free

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.IBinder
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * Foreground Service quản lý 2 overlay chính:
 * 1. Bubble nút nổi (thu gọn / mở rộng bảng điều khiển) snap cạnh màn hình.
 * 2. TargetPointer (tâm ngắm điểm chạm tròn có crosshair) kéo thả tự do để chọn tọa độ click chính xác.
 */
class FloatingBubbleService : Service() {

    private lateinit var windowManager: WindowManager
    private var bubbleView: View? = null
    private var panelView: View? = null
    private var targetPointerView: TargetPointerView? = null
    private var targetPointerParams: WindowManager.LayoutParams? = null

    private val serviceScope = CoroutineScope(Dispatchers.Main + Job())

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        createNotificationChannel()
        startForeground(NOTIF_ID, buildForegroundNotification())

        showBubble()
        showTargetPointer()
        observeEngine()
    }

    // =========================================================================
    // 1. TÂM NGẮM ĐIỂM CHẠM (TARGET POINTER) - Kéo thả để chọn điểm chính xác
    // =========================================================================

    private fun showTargetPointer() {
        if (targetPointerView != null) return

        val sizePx = dpToPx(56f).toInt()
        val params = WindowManager.LayoutParams(
            sizePx,
            sizePx,
            overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            val currentConf = ClickerEngine.config.value
            x = (currentConf.x - sizePx / 2).toInt().coerceAtLeast(0)
            y = (currentConf.y - sizePx / 2).toInt().coerceAtLeast(0)
        }

        val pointer = TargetPointerView(this).apply {
            var initialX = 0
            var initialY = 0
            var touchX = 0f
            var touchY = 0f

            setOnTouchListener { _, event ->
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        initialX = params.x
                        initialY = params.y
                        touchX = event.rawX
                        touchY = event.rawY
                        true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = (event.rawX - touchX).toInt()
                        val dy = (event.rawY - touchY).toInt()
                        params.x = initialX + dx
                        params.y = initialY + dy
                        windowManager.updateViewLayout(this, params)

                        // Cập nhật tọa độ tâm ngắm vào ClickerEngine
                        val centerX = params.x + sizePx / 2f
                        val centerY = params.y + sizePx / 2f
                        ClickerEngine.setTargetPoint(centerX, centerY)
                        true
                    }
                    MotionEvent.ACTION_UP -> {
                        val centerX = params.x + sizePx / 2f
                        val centerY = params.y + sizePx / 2f
                        ClickerEngine.setTargetPoint(centerX, centerY)
                        true
                    }
                    else -> false
                }
            }
        }

        windowManager.addView(pointer, params)
        targetPointerView = pointer
        targetPointerParams = params
    }

    private fun updateTargetPointerVisibility(visible: Boolean) {
        targetPointerView?.visibility = if (visible) View.VISIBLE else View.GONE
    }

    // =========================================================================
    // 2. BUBBLE NÚT NỔI (Kéo thả + Snap cạnh)
    // =========================================================================

    private fun showBubble() {
        val bubbleSize = dpToPx(48f).toInt()
        val params = WindowManager.LayoutParams(
            bubbleSize,
            bubbleSize,
            overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 16
            y = 400
        }

        val bgDrawable = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(0xFF1E293B.toInt()) // Slate-800
            setStroke(dpToPx(2f).toInt(), 0xFF38BDF8.toInt()) // Sky-400 border
        }

        val bubble = TextView(this).apply {
            text = "▶"
            textSize = 18f
            gravity = Gravity.CENTER
            background = bgDrawable
            setTextColor(0xFF38BDF8.toInt())
            elevation = dpToPx(6f)
        }

        var initialX = 0
        var initialY = 0
        var touchX = 0f
        var touchY = 0f
        var moved = false

        bubble.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x
                    initialY = params.y
                    touchX = event.rawX
                    touchY = event.rawY
                    moved = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - touchX).toInt()
                    val dy = (event.rawY - touchY).toInt()
                    if (abs(dx) > 10 || abs(dy) > 10) moved = true
                    params.x = initialX + dx
                    params.y = initialY + dy
                    windowManager.updateViewLayout(bubble, params)
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!moved) {
                        togglePanel()
                    } else {
                        snapBubbleToEdge(params, bubble)
                    }
                    true
                }
                else -> false
            }
        }

        windowManager.addView(bubble, params)
        bubbleView = bubble
    }

    private fun snapBubbleToEdge(params: WindowManager.LayoutParams, view: View) {
        val screenWidth = resources.displayMetrics.widthPixels
        val targetX = if (params.x < screenWidth / 2) 16 else screenWidth - view.width - 16
        params.x = targetX
        windowManager.updateViewLayout(view, params)
    }

    // =========================================================================
    // 3. PANEL ĐIỀU KHIỂN CHI TIẾT (EXPANDED PANEL)
    // =========================================================================

    private fun togglePanel() {
        if (panelView != null) {
            runCatching { windowManager.removeView(panelView) }
            panelView = null
            return
        }
        showPanel()
    }

    private fun showPanel() {
        val params = WindowManager.LayoutParams(
            dpToPx(280f).toInt(),
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = dpToPx(64f).toInt()
            y = dpToPx(160f).toInt()
        }

        val panelBg = GradientDrawable().apply {
            cornerRadius = dpToPx(16f)
            setColor(0xF00F172A.toInt()) // Slate-900 94% opacity
            setStroke(dpToPx(1.5f).toInt(), 0xFF334155.toInt())
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val pad = dpToPx(16f).toInt()
            setPadding(pad, pad, pad, pad)
            background = panelBg
            elevation = dpToPx(12f)
        }

        // Header: Trạng thái & Nút đóng
        val headerRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val statusText = TextView(this).apply {
            text = "Auto Click"
            textSize = 15f
            setTextColor(0xFFF8FAFC.toInt())
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val closeBtn = TextView(this).apply {
            text = "✕"
            textSize = 16f
            setTextColor(0xFF94A3B8.toInt())
            setPadding(dpToPx(8f).toInt(), 0, dpToPx(4f).toInt(), 0)
            setOnClickListener { togglePanel() }
        }
        headerRow.addView(statusText)
        headerRow.addView(closeBtn)
        root.addView(headerRow)

        // Bộ đếm click & Tọa độ mục tiêu
        val infoText = TextView(this).apply {
            textSize = 12f
            setTextColor(0xFF38BDF8.toInt())
            setPadding(0, dpToPx(6f).toInt(), 0, dpToPx(8f).toInt())
        }
        root.addView(infoText)

        // Slider điều chỉnh Interval
        val intervalLabel = TextView(this).apply {
            textSize = 13f
            setTextColor(0xFFCBD5E1.toInt())
        }
        root.addView(intervalLabel)

        val seek = SeekBar(this).apply {
            max = 970 // Từ 30ms đến 1000ms
            progress = (ClickerEngine.config.value.intervalMs - 30).toInt().coerceIn(0, 970)
            setPadding(0, dpToPx(8f).toInt(), 0, dpToPx(8f).toInt())
        }
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                val ms = (30 + progress).toLong()
                intervalLabel.text = "Tốc độ click: ${ms} ms"
                if (fromUser) ClickerEngine.updateInterval(ms)
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })
        intervalLabel.text = "Tốc độ click: ${ClickerEngine.config.value.intervalMs} ms"
        root.addView(seek)

        // Hàng nút điều khiển Start / Pause / Stop
        val buttonRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dpToPx(10f).toInt(), 0, dpToPx(6f).toInt())
        }

        val startPauseBtn = Button(this).apply {
            text = if (ClickerEngine.state.value == ClickerState.RUNNING) "Tạm dừng" else "Bắt đầu"
            textSize = 13f
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = dpToPx(6f).toInt()
            }
            setOnClickListener {
                when (ClickerEngine.state.value) {
                    ClickerState.IDLE -> ClickerEngine.start()
                    ClickerState.RUNNING -> ClickerEngine.pause()
                    ClickerState.PAUSED -> ClickerEngine.resume()
                }
            }
        }

        val stopBtn = Button(this).apply {
            text = "Dừng"
            textSize = 13f
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener {
                ClickerEngine.stop()
            }
        }

        buttonRow.addView(startPauseBtn)
        buttonRow.addView(stopBtn)
        root.addView(buttonRow)

        // Nút bật/tắt tâm ngắm
        val toggleTargetBtn = Button(this).apply {
            text = "🎯 Ẩn / Hiện tâm ngắm"
            textSize = 12f
            setOnClickListener {
                ClickerEngine.toggleTargetPointerVisibility()
            }
        }
        root.addView(toggleTargetBtn)

        // Lắng nghe cập nhật UI thời gian thực
        serviceScope.launch {
            ClickerEngine.state.collectLatest { state ->
                statusText.text = when (state) {
                    ClickerState.RUNNING -> "● Đang chạy"
                    ClickerState.PAUSED -> "❙❙ Tạm dừng"
                    ClickerState.IDLE -> "○ Sẵn sàng"
                }
                startPauseBtn.text = when (state) {
                    ClickerState.RUNNING -> "Tạm dừng"
                    ClickerState.PAUSED -> "Tiếp tục"
                    ClickerState.IDLE -> "Bắt đầu"
                }
            }
        }

        serviceScope.launch {
            ClickerEngine.clickCount.collectLatest { count ->
                val conf = ClickerEngine.config.value
                infoText.text = "Số click: $count | Tâm: (${conf.x.toInt()}, ${conf.y.toInt()})"
            }
        }

        windowManager.addView(root, params)
        panelView = root
    }

    // =========================================================================
    // 4. LẮNG NGHE ENGINE VÀ ĐỒNG BỘ
    // =========================================================================

    private fun observeEngine() {
        serviceScope.launch {
            ClickerEngine.state.collectLatest { state ->
                (bubbleView as? TextView)?.apply {
                    when (state) {
                        ClickerState.RUNNING -> {
                            text = "❙❙"
                            setTextColor(0xFFF59E0B.toInt()) // Amber
                        }
                        ClickerState.PAUSED -> {
                            text = "▶"
                            setTextColor(0xFF10B981.toInt()) // Emerald
                        }
                        ClickerState.IDLE -> {
                            text = "▶"
                            setTextColor(0xFF38BDF8.toInt()) // Sky
                        }
                    }
                }
            }
        }

        serviceScope.launch {
            ClickerEngine.isTargetPointerVisible.collectLatest { visible ->
                updateTargetPointerVisibility(visible)
            }
        }
    }

    private fun overlayType(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE
        }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Auto Click Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Kênh thông báo duy trì bảng điều khiển nổi Auto Click"
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }

    private fun buildForegroundNotification() =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.fgs_notification_title))
            .setContentText(getString(R.string.fgs_notification_content))
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setOngoing(true)
            .setContentIntent(
                PendingIntent.getActivity(
                    this,
                    0,
                    Intent(this, MainActivity::class.java),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                )
            )
            .build()

    private fun dpToPx(dp: Float): Float {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            dp,
            resources.displayMetrics
        )
    }

    override fun onDestroy() {
        super.onDestroy()
        bubbleView?.let { runCatching { windowManager.removeView(it) } }
        panelView?.let { runCatching { windowManager.removeView(it) } }
        targetPointerView?.let { runCatching { windowManager.removeView(it) } }
        serviceScope.cancel()
        ClickerEngine.stop()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val CHANNEL_ID = "autoclicker_channel"
        const val NOTIF_ID = 1001
        var isServiceRunning = false
            private set
    }
}

/**
 * View con trỏ tâm ngắm (Target Pointer) vẽ hình tròn crosshair với số index "1" ở giữa.
 * Người dùng có thể nhìn thấy trực tiếp điểm chạm sẽ rơi vào đâu trên màn hình.
 */
class TargetPointerView(context: Context) : View(context) {

    private val circlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xAA0284C7.toInt() // Sky-600 semi-transparent
        style = Paint.Style.FILL
    }

    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 3f
    }

    private val crosshairPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        strokeWidth = 2f
    }

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 28f
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cx = width / 2f
        val cy = height / 2f
        val radius = (width / 2f) - 4f

        // Vòng tròn nền
        canvas.drawCircle(cx, cy, radius, circlePaint)
        // Viền trắng
        canvas.drawCircle(cx, cy, radius, borderPaint)

        // Crosshair vi mô ở 4 hướng
        canvas.drawLine(cx - radius, cy, cx - radius + 10f, cy, crosshairPaint)
        canvas.drawLine(cx + radius - 10f, cy, cx + radius, cy, crosshairPaint)
        canvas.drawLine(cx, cy - radius, cx, cy - radius + 10f, crosshairPaint)
        canvas.drawLine(cx, cy + radius - 10f, cx, cy + radius, crosshairPaint)

        // Nhãn số 1 ở tâm
        val textY = cy - ((textPaint.descent() + textPaint.ascent()) / 2)
        canvas.drawText("1", cx, textY, textPaint)
    }
}
