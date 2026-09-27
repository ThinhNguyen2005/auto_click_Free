package com.giathinh.auto_click_free

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.view.accessibility.AccessibilityEvent
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
 * Không chứa logic UI, chỉ lắng nghe ClickerEngine.state và điều khiển vòng lặp click.
 * Mọi thao tác chờ (delay) đều diễn ra trên Coroutine (Dispatchers.Default),
 * tuyệt đối không bao giờ dùng Thread.sleep() làm block Main Thread.
 */
class ClickAccessibilityService : AccessibilityService() {

    private val serviceScope = CoroutineScope(Dispatchers.Default + Job())
    private var loopJob: Job? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this

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

    private fun startLoopIfNeeded() {
        if (loopJob?.isActive == true) return
        loopJob = serviceScope.launch {
            try {
                while (ClickerEngine.state.value != ClickerState.IDLE) {
                    if (ClickerEngine.state.value == ClickerState.RUNNING) {
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
                // Tôn trọng Coroutine Cancellation theo chuẩn @android-pro
                throw e
            }
        }
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
