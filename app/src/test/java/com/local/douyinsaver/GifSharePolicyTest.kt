package com.local.douyinsaver

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class GifSharePolicyTest {
    @Test fun smallExportsKeepExistingShareParametersWithoutRetry() = runBlocking {
        val visited = mutableListOf<GifSharePolicy.Profile>()
        val result = GifSharePolicy.encode(GifExportQuality.SHARE) { profile, budget ->
            visited += profile
            assertEquals(GifConversionPolicy.MAX_GIF_BYTES, budget)
            File("private-output.gif")
        }
        assertEquals("private-output.gif", result.name)
        assertEquals(listOf(GifSharePolicy.Profile(480, 10, 128)), visited)
    }

    @Test fun sizeOverflowErrorsAndCancellationNeverRetryAtLowerQuality() = runBlocking {
        var attempts = 0
        for (failure in listOf(GifSizeLimitException(), IllegalArgumentException("corrupt source"), CancellationException("cancel"))) {
            attempts = 0
            try {
                GifSharePolicy.encode(GifExportQuality.SHARE) { _, _ -> attempts++; throw failure }
                fail("Failure was swallowed")
            } catch (error: Exception) { assertSame(failure, error) }
            assertEquals(1, attempts)
        }
    }

    @Test fun highQualityIsUnchangedAndEveryRetryKeepsFullDurationAndAspectRatio() {
        assertEquals(listOf(GifSharePolicy.Profile(960, 30, 256)), GifSharePolicy.profiles(GifExportQuality.HIGH_QUALITY))
        for (profile in GifSharePolicy.profiles(GifExportQuality.SHARE)) {
            for (unknown in listOf(Double.NaN, Double.POSITIVE_INFINITY, 0.0, -1.0))
                assertEquals(profile.fps.toDouble(), GifSharePolicy.frameRate(unknown, profile), 0.0)
            assertEquals(3.0, GifSharePolicy.frameRate(3.0, profile), 0.0)
            assertEquals(profile.edge to profile.edge / 2, GifSharePolicy.canvas(960, 480, profile))
            assertEquals(8 to 4, GifSharePolicy.canvas(8, 4, profile))
            val plan = GifConversionPolicy.plan(178_724, 0, 178_724, profile.fps.toDouble(), GifExportQuality.SHARE)
            assertEquals(178_720L, plan.durationMs)
            assertEquals(plan.durationMs, plan.frames.sumOf { it.delayCentiseconds.toLong() } * 10L)
        }
    }
}
