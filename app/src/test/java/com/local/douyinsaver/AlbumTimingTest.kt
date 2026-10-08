package com.local.douyinsaver

import org.junit.Assert.*
import org.junit.Test

class AlbumTimingTest {
    @Test fun persistedDecimalStaticDefaultFeedsGifAndMixedCompositionWithoutChangingMotion() {
        val options = DownloadOptions(imageSeconds = 7, staticImageSeconds = 0.1)
        assertEquals(100L, AlbumTiming.staticMilliseconds(options))
        assertEquals(listOf(100L, 2_966L, 1_944L, 100L), AlbumTiming.durations(listOf(null, 2_966L, 1_944L, null), options))
        val longer = options.copy(staticImageSeconds = 1.2)
        assertEquals(1_200L, AlbumTiming.staticMilliseconds(longer))
        assertEquals(listOf(1_200L, 2_966L, 1_944L, 1_200L), AlbumTiming.durations(listOf(null, 2_966L, 1_944L, null), longer))
        val override = longer.copy(itemDurationSeconds = 0.7)
        assertEquals(700L, AlbumTiming.staticMilliseconds(override))
        assertEquals(listOf(700L, 700L, 700L), AlbumTiming.durations(listOf(null, 2_966L, 1_944L), override))
    }

    @Test fun decimalDefaultsPreserveLegacyRecordSignaturesAndSeparateChangedDefaults() {
        assertEquals(AlbumTiming.signature(DownloadOptions(imageSeconds = 7)),
            AlbumTiming.signature(DownloadOptions(staticImageSeconds = 7.0)))
        assertEquals("auto;static=0.1", AlbumTiming.signature(DownloadOptions(staticImageSeconds = 0.1)))
        assertEquals("auto;static=1.2", AlbumTiming.signature(DownloadOptions(staticImageSeconds = 1.2)))
        assertEquals(AlbumTiming.signature(DownloadOptions(staticImageSeconds = 0.1, itemDurationSeconds = 1.7)),
            AlbumTiming.signature(DownloadOptions(staticImageSeconds = 120.0, itemDurationSeconds = 1.7)))
    }

    @Test fun decimalDefaultSummaryKeepsEachMotionAndAddsOnlyStaticDefaults() {
        val content = ParsedVideo("9999999999999999910", "受控混合图集", "", 0.0, 1, 1, images = listOf(
            ParsedImage("https://p3.douyinpic.com/static"),
            ParsedImage("https://p3.douyinpic.com/motion", kind = AlbumAssetKind.ANIMATED, motion = ParsedMotion("", durationSeconds = 2.966)),
            ParsedImage("https://p3.douyinpic.com/static2")))
        assertEquals(3.166, AlbumDurationUiPolicy.totalSeconds(content, null, 0.1)!!, 0.00001)
        assertEquals(5.366, AlbumDurationUiPolicy.totalSeconds(content, null, 1.2)!!, 0.00001)
        assertEquals(2.1, AlbumDurationUiPolicy.totalSeconds(content, 0.7, 1.2)!!, 0.00001)
    }

    @Test fun singleItemConfirmationKeepsItsOriginalGalleryPosition() {
        val adjustments = AlbumTiming.adjustments(listOf(2_700L), listOf(100L), selectedIndices = listOf(2))
        assertEquals(2, adjustments.single().index)
        assertEquals(2.7, adjustments.single().originalSeconds, 0.001)
        assertEquals(0.1, adjustments.single().requestedSeconds, 0.001)
        assertTrue(AlbumDurationUiPolicy.adjustmentDescription(adjustments.single()).startsWith("第 3 项"))
    }
    @Test fun decimalInputUsesTenthsOfASecondIncludingPointOne() {
        assertEquals(100L, AlbumTiming.milliseconds(0.1))
        assertEquals(1_700L, AlbumTiming.milliseconds(1.7))
        assertEquals(2_400L, AlbumTiming.milliseconds(2.3999999999))
        assertEquals(120_000L, AlbumTiming.milliseconds(120.0))
    }
    @Test fun rejectsEmptyNonfiniteAndOutsideSupportedInputRange() {
        listOf(0.0, -0.1, 0.09, 120.1, Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY).forEach {
            assertTrue("Invalid custom seconds accepted: $it", runCatching { AlbumTiming.milliseconds(it) }.isFailure)
        }
    }
    @Test fun automaticMixedGalleryKeepsEachExactMotionDurationAndStaticDefault() {
        val values = AlbumTiming.durations(listOf(null, 2_966L, 1_944L, null), DownloadOptions(imageSeconds = 3))
        assertEquals(listOf(3_000L, 2_966L, 1_944L, 3_000L), values)
        assertEquals(10_910L, values.sum())
    }
    @Test fun customTenthsOverrideAutomaticSuggestionForAllSelectedItems() {
        assertEquals(listOf(1_700L, 1_700L, 1_700L), AlbumTiming.durations(listOf(null, 2_966L, 1_944L),
            DownloadOptions(itemDurationSeconds = 1.7)))
    }
    @Test fun missingMotionDurationUsesStaticDefaultWithoutInventingDynamicLength() {
        assertEquals(listOf(4_000L, 4_000L, 4_000L), AlbumTiming.durations(listOf(null, 0L, -1L), DownloadOptions(imageSeconds = 4)))
    }
    @Test fun mismatchReportIncludesOnlyActualMotionAdjustmentsAndKeepsIndices() {
        val changes = AlbumTiming.adjustments(listOf(null, 2_000L, 3_000L, 1_000L), listOf(1_000L, 1_000L, 3_000L, 4_000L))
        assertEquals(listOf(DurationAdjustment(1, 2.0, 1.0), DurationAdjustment(3, 1.0, 4.0)), changes)
    }
    @Test fun trimUsesOnlyRequestedLeadingSegmentAndLongerDurationRepeatsWholeSourceThenTail() {
        assertEquals(listOf(700L), AlbumTiming.segments(2_000L, 700L))
        assertEquals(listOf(2_000L), AlbumTiming.segments(2_000L, 2_000L))
        assertEquals(listOf(2_000L, 2_000L, 700L), AlbumTiming.segments(2_000L, 4_700L))
        assertEquals(4_700L, AlbumTiming.segments(2_000L, 4_700L).sum())
    }
    @Test fun invalidOrExcessiveLoopRequestsFailBeforeExport() {
        listOf(0L to 100L, 100L to 0L, -1L to 100L, 100L to 900_001L, 1L to 900_000L).forEach { (source, wanted) ->
            assertTrue(runCatching { AlbumTiming.segments(source, wanted) }.isFailure)
        }
    }
    @Test fun wholeCompositionHasAnExplicitTotalLimit() {
        assertEquals(900_000L, AlbumTiming.durations(List(100) { null }, DownloadOptions(itemDurationSeconds = 9.0)).sum())
        assertTrue(runCatching { AlbumTiming.durations(List(100) { null }, DownloadOptions(itemDurationSeconds = 9.1)) }.isFailure)
        assertTrue(runCatching { AlbumTiming.durations(emptyList(), DownloadOptions()) }.isFailure)
    }
    @Test fun selectionRetainsOriginalOrderAndOneItemDoesNotIncludeNeighbors() {
        val content = ParsedVideo("9999999999999999910", "受控图集", "", 0.0, 1, 1,
            images = (0..3).map { ParsedImage("https://p3.douyinpic.com/image_$it") })
        assertEquals(listOf(content.images[2]), AlbumSelection.select(content, listOf(2)).images)
        assertEquals(listOf(content.images[0], content.images[3]), AlbumSelection.select(content, listOf(3, 0)).images)
        assertEquals(content, AlbumSelection.select(content, emptyList()))
    }
    @Test fun staleDuplicateOrOutOfBoundsSelectionsFailWithoutSilentlySavingWholeAlbum() {
        val content = ParsedVideo("9999999999999999910", "受控图集", "", 0.0, 1, 1, images = listOf(ParsedImage("https://p3.douyinpic.com/only")))
        listOf(listOf(-1), listOf(1), listOf(0, 0)).forEach { indices ->
            assertTrue(runCatching { AlbumSelection.select(content, indices) }.isFailure)
        }
    }

    @Test fun enrichedSelectionFollowsExactImageIdentityAcrossReorderedSignedSources() {
        val first = ParsedImage("https://p3.douyinpic.com/first?sign=old", imageKey = "photo-first")
        val second = ParsedImage("https://p3.douyinpic.com/second?sign=old", imageKey = "photo-second")
        val base = ParsedVideo("9999999999999999910", "受控图集", "", 0.0, 1, 1, images = listOf(first, second))
        val enriched = base.copy(images = listOf(second.copy(url = "https://p3.douyinpic.com/second?sign=new"),
            first.copy(url = "https://p3.douyinpic.com/first?sign=new")))
        assertEquals(listOf(1), AlbumSelection.correspondingIndices(base, enriched, listOf(0)))
        assertEquals(listOf(0, 1), AlbumSelection.correspondingIndices(base, enriched, listOf(1, 0)))
        assertThrows(IllegalArgumentException::class.java) {
            AlbumSelection.correspondingIndices(base, enriched.copy(id = "9999999999999999911"), listOf(0))
        }
    }

    @Test fun twoSelectedOriginalsCannotSilentlyCollapseIntoOneCandidateSlot() {
        val repeated = ParsedImage("https://p3.douyinpic.com/repeated", imageKey = "same-photo-key")
        val base = ParsedVideo("9999999999999999910", "受控图集", "", 0.0, 1, 1, images = listOf(repeated, repeated))
        val candidate = base.copy(images = listOf(repeated,
            ParsedImage("https://p3.douyinpic.com/neighbor", imageKey = "neighbor-photo-key")))
        assertThrows(IllegalArgumentException::class.java) {
            AlbumSelection.correspondingIndices(base, candidate, listOf(0, 1))
        }
        assertEquals(2, base.images.size)
        assertEquals(2, candidate.images.size)
    }

    @Test fun multipleCandidateMatchesFailWithoutGuessingByArrayPosition() {
        val selected = ParsedImage("https://p3.douyinpic.com/same", imageKey = "same-photo-key")
        val base = ParsedVideo("9999999999999999910", "受控图集", "", 0.0, 1, 1, images = listOf(selected))
        assertThrows(IllegalArgumentException::class.java) {
            AlbumSelection.correspondingIndices(base, base.copy(images = listOf(selected, selected)), listOf(0))
        }
    }
}
