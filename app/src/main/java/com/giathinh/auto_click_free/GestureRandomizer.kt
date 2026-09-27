package com.giathinh.auto_click_free

import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Logic mô phỏng thao tác bấm tay người thật (Human-like touch simulation):
 * - Vị trí bấm theo phân bố chuẩn Gaussian 2D (Box-Muller)
 * - Khoảng nghỉ theo phân bố tam giác (Triangular jitter)
 * - Thời gian nhấn giữ tự nhiên (Touch hold duration)
 * - Chuyển động vi mô trong lúc nhấn (Micro-drift giữa down và up)
 */
object GestureRandomizer {

    private val random = Random.Default

    /**
     * Sinh 1 điểm ngẫu nhiên quanh tâm (cx, cy) theo phân bố Gaussian 2D
     * (Box-Muller transform). Điểm tập trung dày gần tâm và thưa dần ra rìa.
     *
     * @param radiusPx tương đương ~2 độ lệch chuẩn (95% điểm rơi trong bán kính này).
     */
    fun randomPointGaussian(cx: Float, cy: Float, radiusPx: Float): Pair<Float, Float> {
        if (radiusPx <= 0f) return Pair(cx, cy)
        val sigma = radiusPx / 2f

        // Box-Muller: sinh 2 số ngẫu nhiên chuẩn độc lập
        val u1 = random.nextDouble(0.0001, 1.0) // Tránh ln(0)
        val u2 = random.nextDouble(0.0, 1.0)
        val mag = sqrt(-2.0 * ln(u1))
        val z0 = mag * cos(2 * Math.PI * u2)
        val z1 = mag * sin(2 * Math.PI * u2)

        val dx = (z0 * sigma).toFloat()
        val dy = (z1 * sigma).toFloat()

        return Pair(cx + dx, cy + dy)
    }

    /**
     * Khoảng nghỉ tiếp theo trước lần click kế tiếp.
     * Dùng phân bố tam giác (triangular) quanh baseMs thay vì uniform đơn thuần.
     */
    fun nextIntervalMs(baseMs: Long, jitterMs: Long): Long {
        if (jitterMs <= 0) return baseMs
        val r1 = random.nextDouble()
        val r2 = random.nextDouble()
        // Trung bình 2 biến ngẫu nhiên uniform tạo ra phân bố tam giác quanh 0.5
        val triangular = (r1 + r2) / 2.0
        val offset = ((triangular - 0.5) * 2 * jitterMs).toLong()
        return (baseMs + offset).coerceAtLeast(20L)
    }

    /**
     * Thời gian giữ ngón tay (down -> up), ngẫu nhiên trong khoảng an toàn.
     */
    fun nextHoldDurationMs(minMs: Long, maxMs: Long): Long {
        if (maxMs <= minMs) return minMs
        return random.nextLong(minMs, maxMs)
    }

    /**
     * Sinh điểm trung gian rất nhỏ giữa lúc down và up để mô phỏng ngón tay
     * không đứng yên tuyệt đối khi chạm vào màn hình (độ lệch 0.5 - 2.5px).
     */
    fun microDrift(x: Float, y: Float): Pair<Float, Float> {
        val driftX = random.nextDouble(-2.0, 2.0).toFloat()
        val driftY = random.nextDouble(-2.0, 2.0).toFloat()
        return Pair(x + driftX, y + driftY)
    }
}
