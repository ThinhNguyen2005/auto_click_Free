package com.giathinh.auto_click_free

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Path
import android.os.PowerManager
import android.view.accessibility.AccessibilityEvent
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Service duy nhất chịu trách nhiệm dispatch gesture thực tế.
 * Tự động tạm dừng ngay lập tức khi tắt màn hình hoặc khi màn hình khóa (Lockscreen)
 * để bảo vệ thiết bị, tránh chạm nhầm mật khẩu màn hình khóa hoặc gây hao pin.
 */
class ClickAccessibilityService : AccessibilityService() {

    private val serviceScope = CoroutineScope(Dispatchers.Default + Job())
    private var loopJob: Job? = null
    private var isReceiverRegistered = false

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_OFF -> {
                    // Tự động tạm dừng ngay khi người dùng bấm tắt màn hình
                    ClickerEngine.pause()
                }
            }
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        registerScreenStateReceiver()

        serviceScope.launch {
            ClickerEngine.state.collectLatest { state ->
                when (state) {
                    ClickerState.RUNNING -> startLoopIfNeeded()
                    ClickerState.PAUSED -> {
                        // Vòng lặp đang chạy sẽ kiểm tra state mỗi vòng lặp
                    }
                    ClickerState.IDLE -> stopLoop()
                }
            }
        }
    }

    private fun registerScreenStateReceiver() {
        if (!isReceiverRegistered) {
            val filter = IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_OFF)
            }
            ContextCompat.registerReceiver(
                this,
                screenReceiver,
                filter,
                ContextCompat.RECEIVER_NOT_EXPORTED
            )
            isReceiverRegistered = true
        }
    }

    private fun startLoopIfNeeded() {
        if (loopJob?.isActive == true) return
        loopJob = serviceScope.launch {
            try {
                while (ClickerEngine.state.value != ClickerState.IDLE) {
                    if (ClickerEngine.state.value == ClickerState.RUNNING) {
                        // Kiểm tra an toàn: nếu màn hình đang tắt hoặc đang khóa, lập tức dừng click
                        if (isDeviceScreenOffOrLocked()) {
                            ClickerEngine.pause()
                            delay(200L)
                            continue
                        }

                        val config = ClickerEngine.config.value
                        performRandomizedClick(config)
                        ClickerEngine.incrementClickCount()
                        val interval = GestureRandomizer.nextIntervalMs(config.intervalMs, config.jitterMs)
                        delay(interval)
                    } else {
                        // Trạng thái PAUSED: kiểm tra định kỳ với khoảng thời gian ngắn
                        delay(120L)
                    }
                }
            } catch (e: CancellationException) {
                throw e
            }
        }
    }

    /**
     * Kiểm tra trạng thái màn hình và màn hình khóa:
     * - Screen off: powerManager.isInteractive == false
     * - Lock screen: keyguardManager.isKeyguardLocked == true
     */
    private fun isDeviceScreenOffOrLocked(): Boolean {
        val powerManager = getSystemService(Context.POWER_SERVICE) as? PowerManager
        val keyguardManager = getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager

        val isScreenInteractive = powerManager?.isInteractive ?: true
        val isLocked = keyguardManager?.isKeyguardLocked ?: false

        return !isScreenInteractive || isLocked
    }

    private fun stopLoop() {
        loopJob?.cancel()
        loopJob = null
    }

    /**
     * Dispatch cử chỉ chạm mô phỏng người thật:
     * - Tâm Gaussian Box-Muller
     * - Micro-drift nhẹ từ điểm down đến điểm up
     * - Thời gian giữ holdMs ngẫu nhiên
     */
    private fun performRandomizedClick(config: ClickerConfig) {
        val (px, py) = GestureRandomizer.randomPointGaussian(config.x, config.y, config.radiusPx)
        val (driftX, driftY) = GestureRandomizer.microDrift(px, py)
        val holdMs = GestureRandomizer.nextHoldDurationMs(config.minHoldMs, config.maxHoldMs)

        val path = Path().apply {
            moveTo(px, py)
            lineTo(driftX, driftY)
        }

        val stroke = GestureDescription.StrokeDescription(path, 0L, holdMs.coerceAtLeast(10L))
        val gesture = GestureDescription.Builder().addStroke(stroke).build()

        dispatchGesture(gesture, null, null)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Không cần xử lý sự kiện UI bên thứ ba cho chức năng auto-click
    }

    override fun onInterrupt() {
        stopLoop()
        ClickerEngine.stop()
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isReceiverRegistered) {
            runCatching { unregisterReceiver(screenReceiver) }
            isReceiverRegistered = false
        }
        stopLoop()
        serviceScope.cancel()
        instance = null
        ClickerEngine.stop()
    }

    companion object {
        var instance: ClickAccessibilityService? = null
            private set

        fun isServiceRunning(): Boolean = instance != null
    }
}
