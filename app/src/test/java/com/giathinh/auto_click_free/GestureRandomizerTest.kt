package com.giathinh.auto_click_free

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.hypot

class GestureRandomizerTest {

    @Test
    fun testRandomPointGaussian_generatesWithinReasonableRange() {
        val cx = 500f
        val cy = 500f
        val radius = 20f

        var maxDistance = 0f
        for (i in 0 until 1000) {
            val (px, py) = GestureRandomizer.randomPointGaussian(cx, cy, radius)
            val dist = hypot(px - cx, py - cy)
            if (dist > maxDistance) {
                maxDistance = dist
            }
        }

        // Gaussian với sigma = radius/2: 99.7% rơi vào 3*sigma = 1.5*radius = 30px
        assertTrue("Max distance should not wildly deviate from target radius", maxDistance < radius * 3f)
    }

    @Test
    fun testNextIntervalMs_withinJitterBounds() {
        val baseMs = 100L
        val jitterMs = 20L

        for (i in 0 until 500) {
            val interval = GestureRandomizer.nextIntervalMs(baseMs, jitterMs)
            assertTrue("Interval must be >= baseMs - jitterMs", interval >= baseMs - jitterMs)
            assertTrue("Interval must be <= baseMs + jitterMs", interval <= baseMs + jitterMs)
        }
    }

    @Test
    fun testNextHoldDurationMs_withinBounds() {
        val minMs = 40L
        val maxMs = 120L

        for (i in 0 until 500) {
            val hold = GestureRandomizer.nextHoldDurationMs(minMs, maxMs)
            assertTrue("Hold must be >= minMs", hold >= minMs)
            assertTrue("Hold must be < maxMs", hold < maxMs)
        }
    }

    @Test
    fun testMicroDrift_smallDeviation() {
        val x = 300f
        val y = 400f

        for (i in 0 until 500) {
            val (driftX, driftY) = GestureRandomizer.microDrift(x, y)
            val dx = kotlin.math.abs(driftX - x)
            val dy = kotlin.math.abs(driftY - y)
            assertTrue("Drift X must be subtle (<= 2.5px)", dx <= 2.5f)
            assertTrue("Drift Y must be subtle (<= 2.5px)", dy <= 2.5f)
        }
    }

    @Test
    fun testClickerEngine_stateTransitions() {
        ClickerEngine.stop()
        assertEquals(ClickerState.IDLE, ClickerEngine.state.value)

        ClickerEngine.start()
        assertEquals(ClickerState.RUNNING, ClickerEngine.state.value)
        assertEquals(0, ClickerEngine.clickCount.value)

        ClickerEngine.incrementClickCount()
        assertEquals(1, ClickerEngine.clickCount.value)

        ClickerEngine.pause()
        assertEquals(ClickerState.PAUSED, ClickerEngine.state.value)

        ClickerEngine.resume()
        assertEquals(ClickerState.RUNNING, ClickerEngine.state.value)

        ClickerEngine.stop()
        assertEquals(ClickerState.IDLE, ClickerEngine.state.value)
    }
}
