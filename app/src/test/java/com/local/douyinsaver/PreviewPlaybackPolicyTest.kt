package com.local.douyinsaver

import org.junit.Assert.*
import org.junit.Test

class PreviewPlaybackPolicyTest {
    @Test fun customSeekSecondsKeepTheSupportedRange() {
        assertEquals(10, PreviewPlaybackPolicy.DEFAULT_SEEK_SECONDS)
        assertEquals(1, PreviewPlaybackPolicy.seekSeconds(Int.MIN_VALUE))
        assertEquals(1, PreviewPlaybackPolicy.seekSeconds(1))
        assertEquals(47, PreviewPlaybackPolicy.seekSeconds(47))
        assertEquals(120, PreviewPlaybackPolicy.seekSeconds(120))
        assertEquals(120, PreviewPlaybackPolicy.seekSeconds(Int.MAX_VALUE))
    }

    @Test fun independentSeekStepsUseSecondsAndClampAtBothEnds() {
        val backward = PreviewPlaybackPolicy.seekSeconds(7) * -1_000L
        val forward = PreviewPlaybackPolicy.seekSeconds(23) * 1_000L
        assertEquals(41_000L, PreviewPlaybackPolicy.seekTarget(18_000, 60_000, forward))
        assertEquals(11_000L, PreviewPlaybackPolicy.seekTarget(18_000, 60_000, backward))
        assertEquals(60_000L, PreviewPlaybackPolicy.seekTarget(50_000, 60_000, forward))
        assertEquals(0L, PreviewPlaybackPolicy.seekTarget(2_000, 60_000, backward))
    }

    @Test fun timeLabelsKeepDigitsStableAcrossMinuteAndHourBoundaries() {
        assertEquals("00:00", PreviewPlaybackPolicy.formatTime(-1))
        assertEquals("00:59", PreviewPlaybackPolicy.formatTime(59_999))
        assertEquals("01:00", PreviewPlaybackPolicy.formatTime(60_000))
        assertEquals("1:01:01", PreviewPlaybackPolicy.formatTime(3_661_000))
    }

    @Test fun seekButtonsCannotLeaveThePlayableTimeline() {
        assertEquals(0L, PreviewPlaybackPolicy.seekTarget(3_000, 12_000, -10_000))
        assertEquals(12_000L, PreviewPlaybackPolicy.seekTarget(8_000, 12_000, 10_000))
        assertEquals(9_000L, PreviewPlaybackPolicy.seekTarget(4_000, 12_000, 5_000))
        assertEquals(0L, PreviewPlaybackPolicy.seekTarget(4_000, -1, 5_000))
    }

    @Test fun invalidOrOverflowingSeekValuesStayBounded() {
        assertEquals(0L, PreviewPlaybackPolicy.seekTarget(4_000, 12_000, Long.MIN_VALUE))
        assertEquals(12_000L, PreviewPlaybackPolicy.seekTarget(-1, 12_000, Long.MAX_VALUE))
        assertEquals(Long.MAX_VALUE, PreviewPlaybackPolicy.seekTarget(10_000, Long.MAX_VALUE, Long.MAX_VALUE))
    }

    @Test fun sourceAspectRatiosSupportPortraitLandscapeAndUnknownMetadata() {
        assertEquals(9f / 16f, PreviewPlaybackPolicy.aspectRatio(1080, 1920), 0.0001f)
        assertEquals(16f / 9f, PreviewPlaybackPolicy.aspectRatio(1920, 1080), 0.0001f)
        assertEquals(9f / 16f, PreviewPlaybackPolicy.aspectRatio(0, 0), 0.0001f)
        assertTrue(PreviewPlaybackPolicy.aspectRatio(1, 32768).isFinite())
    }
}
