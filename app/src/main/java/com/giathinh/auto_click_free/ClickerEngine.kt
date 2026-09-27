package com.giathinh.auto_click_free

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Trạng thái vòng đời của việc auto-click.
 * IDLE   -> Chưa bắt đầu hoặc đã bấm Stop
 * RUNNING-> Đang thực hiện các lần click
 * PAUSED -> Tạm dừng, giữ nguyên tọa độ và cấu hình, có thể tiếp tục ngay lập tức
 */
enum class ClickerState { IDLE, RUNNING, PAUSED }

/**
 * Cấu hình 1 điểm auto-click.
 * @param x, y            Tọa độ tâm chạm (pixel, screen coordinates)
 * @param intervalMs       Khoảng thời gian trung bình giữa 2 lần click (ms)
 * @param jitterMs         Độ lệch ngẫu nhiên tối đa cho interval (± ms)
 * @param radiusPx         Bán kính phân bố Gaussian quanh tâm (px)
 * @param minHoldMs/maxHoldMs Khoảng ngẫu nhiên cho thời gian giữ ngón tay (down -> up)
 */
data class ClickerConfig(
    val x: Float = 500f,
    val y: Float = 800f,
    val intervalMs: Long = 100L,
    val jitterMs: Long = 15L,
    val radiusPx: Float = 12f,
    val minHoldMs: Long = 40L,
    val maxHoldMs: Long = 120L
)

/**
 * Singleton giữ state + config, là cầu nối giữa FloatingBubbleService (UI)
 * và ClickAccessibilityService (nơi thực sự dispatch gesture).
 * Dùng StateFlow để cả hai phía observe mà không phụ thuộc trực tiếp vào nhau.
 */
object ClickerEngine {

    private val _state = MutableStateFlow(ClickerState.IDLE)
    val state: StateFlow<ClickerState> = _state.asStateFlow()

    private val _config = MutableStateFlow(ClickerConfig())
    val config: StateFlow<ClickerConfig> = _config.asStateFlow()

    // Tổng số lần click đã thực hiện trong phiên hiện tại
    private val _clickCount = MutableStateFlow(0)
    val clickCount: StateFlow<Int> = _clickCount.asStateFlow()

    // Trạng thái hiển thị của tâm ngắm / con trỏ chọn điểm trên màn hình
    private val _isTargetPointerVisible = MutableStateFlow(true)
    val isTargetPointerVisible: StateFlow<Boolean> = _isTargetPointerVisible.asStateFlow()

    fun setTargetPoint(x: Float, y: Float) {
        _config.update { it.copy(x = x, y = y) }
    }

    fun updateInterval(newIntervalMs: Long) {
        _config.update { it.copy(intervalMs = newIntervalMs.coerceIn(30L, 5000L)) }
    }

    fun updateJitter(newJitterMs: Long) {
        _config.update { it.copy(jitterMs = newJitterMs.coerceIn(0L, 200L)) }
    }

    fun updateRadius(newRadiusPx: Float) {
        _config.update { it.copy(radiusPx = newRadiusPx.coerceIn(0f, 100f)) }
    }

    fun toggleTargetPointerVisibility() {
        _isTargetPointerVisible.update { !it }
    }

    fun setTargetPointerVisibility(visible: Boolean) {
        _isTargetPointerVisible.value = visible
    }

    fun start() {
        _clickCount.value = 0
        _state.value = ClickerState.RUNNING
    }

    fun pause() {
        if (_state.value == ClickerState.RUNNING) {
            _state.value = ClickerState.PAUSED
        }
    }

    fun resume() {
        if (_state.value == ClickerState.PAUSED) {
            _state.value = ClickerState.RUNNING
        }
    }

    fun stop() {
        _state.value = ClickerState.IDLE
    }

    fun incrementClickCount() {
        _clickCount.update { it + 1 }
    }
}
