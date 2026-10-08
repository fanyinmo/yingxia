package com.local.douyinsaver

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class GifConversionPolicyTest {
    @Test fun distributesGifDelaysWithoutAccumulatingFpsRoundingError() {
        for (duration in listOf(100L, 250L, 1_000L, 1_001L, 1_006L, 6_000L, 15_000L)) {
            val plan = GifConversionPolicy.plan(20_000L, 100L, duration)
            assertTrue(plan.frames.size in 2..450)
            assertTrue(plan.frames.all { it.delayCentiseconds > 0 })
            assertEquals(plan.durationMs, plan.frames.sumOf { it.delayCentiseconds }.toLong() * 10L)
            assertTrue(abs(plan.durationMs - duration) <= 5L)
            assertEquals(100L, plan.frames.first().timestampMs)
            assertTrue(plan.frames.zipWithNext().all { (first, next) -> next.timestampMs > first.timestampMs })
            assertTrue(plan.frames.last().timestampMs < 100L + duration)
        }
        assertEquals(listOf(13, 12, 13, 12, 13, 12, 13, 12),
            GifConversionPolicy.plan(1_000L, 0L, 1_000L, 8.0).frames.map { it.delayCentiseconds })
        assertEquals(450, GifConversionPolicy.plan(15_000L, 0L, 15_000L).frames.size)
    }

    @Test fun rejectsOutOfBoundsOrOverflowingRangesWithoutSilentTruncation() {
        listOf(Triple(1_000L, -1L, 250L), Triple(1_000L, 1_000L, 250L), Triple(1_000L, 800L, 250L),
            Triple(1_000L, 0L, 99L), Triple(0L, 0L, 250L),
            Triple(Long.MAX_VALUE, 0L, 250L), Triple(1_000L, Long.MAX_VALUE, 250L),
            Triple(1_000L, 0L, Long.MAX_VALUE)).forEach { (source, start, duration) ->
            assertThrows(IllegalArgumentException::class.java) { GifConversionPolicy.plan(source, start, duration) }
        }
    }

    @Test fun scalesAspectRatioAndDoesNotEnlargeTinyVideo() {
        assertEquals(960 to 540, GifConversionPolicy.canvas(1920, 1080))
        assertEquals(540 to 960, GifConversionPolicy.canvas(1080, 1920))
        assertEquals(100 to 80, GifConversionPolicy.canvas(100, 80))
        assertEquals(960 to 960, GifConversionPolicy.canvas(1024, 1024))
        listOf(0 to 1, 1 to 0, 32_769 to 1, 10_000 to 10_000).forEach { (width, height) ->
            assertThrows(IllegalArgumentException::class.java) { GifConversionPolicy.canvas(width, height) }
        }
    }

    @Test fun supportsFullVideoRemainderAndOneTenthSecondWithoutAllocatingEveryFrame() {
        val minimum = GifConversionPolicy.plan(178_724L, 178_600L, 100L)
        assertEquals(3, minimum.frames.size)
        assertEquals(10, minimum.frames.sumOf { it.delayCentiseconds })
        val full = GifConversionPolicy.plan(178_724L, 0L, 178_724L)
        assertTrue(full.frames.size > 5_000)
        assertEquals(178_720L, full.durationMs)
        assertEquals(full.durationMs, full.frames.sumOf { it.delayCentiseconds.toLong() } * 10L)
        val large = GifConversionPolicy.plan(10_000_000_000L, 0L, 10_000_000_000L)
        assertEquals(300_000_000, large.frames.size)
        assertEquals(0L, large.frames.first().timestampMs)
        assertTrue(large.frames.last().timestampMs < 10_000_000_000L)
        assertThrows(IndexOutOfBoundsException::class.java) { large.frames[-1] }
        assertThrows(IndexOutOfBoundsException::class.java) { large.frames[large.frames.size] }
    }

    @Test fun keepsLowSourceFrameRateAndBoundsGifTemporalPrecision() {
        assertEquals(12, GifConversionPolicy.plan(1_000L, 0L, 1_000L, 12.0).frames.size)
        assertEquals(30, GifConversionPolicy.plan(1_000L, 0L, 1_000L, 60.0).frames.size)
        assertEquals(30, GifConversionPolicy.plan(1_000L, 0L, 1_000L, Double.NaN).frames.size)
    }

    @Test fun shareModeReducesDetailExplicitlyAndKeepsFullDurationWithoutUpscaling() {
        val high = GifConversionPolicy.plan(16_100L, 0L, 16_100L)
        val share = GifConversionPolicy.plan(16_100L, 0L, 16_100L, quality = GifExportQuality.SHARE)
        assertEquals(483, high.frames.size)
        assertEquals(161, share.frames.size)
        assertEquals(16_100L, share.durationMs)
        assertEquals(16_100L, share.frames.sumOf { it.delayCentiseconds.toLong() } * 10L)
        assertEquals(0L, share.frames.first().timestampMs)
        assertEquals(16_000L, share.frames.last().timestampMs)
        assertEquals(480 to 270, GifConversionPolicy.canvas(1280, 720, GifExportQuality.SHARE))
        assertEquals(270 to 480, GifConversionPolicy.canvas(720, 1280, GifExportQuality.SHARE))
        assertEquals(100 to 80, GifConversionPolicy.canvas(100, 80, GifExportQuality.SHARE))
        assertEquals(5, GifConversionPolicy.plan(1_000L, 0L, 1_000L, 5.0, GifExportQuality.SHARE).frames.size)
        val shortest = GifConversionPolicy.plan(100L, 0L, 100L, quality = GifExportQuality.SHARE)
        assertEquals(2, shortest.frames.size)
        assertEquals(10, shortest.frames.sumOf { it.delayCentiseconds })
    }

    @Test fun actualFileSizeAdviceDoesNotPretendToKnowChatPlaybackThresholds() {
        val summary = GifConversionPolicy.sharingSummary(80L * 1024 * 1024)
        assertTrue(summary.contains("80.00 MiB"))
        assertTrue(summary.contains("实际入口"))
        assertTrue(summary.contains("MP4"))
        assertThrows(IllegalArgumentException::class.java) { GifConversionPolicy.sharingSummary(0) }
    }
}
