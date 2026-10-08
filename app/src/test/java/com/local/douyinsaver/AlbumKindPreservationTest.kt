package com.local.douyinsaver

import org.junit.Assert.*
import org.junit.Test

class AlbumKindPreservationTest {
    @Test fun reorderedUniqueIdentitiesRetainKnownKindsWithoutReplacingDesktopMedia() {
        val base = album(listOf(image("live", AlbumAssetKind.LIVE), image("animation", AlbumAssetKind.ANIMATED),
            image("static", AlbumAssetKind.STATIC)))
        val candidate = album(listOf(image("animation", AlbumAssetKind.DYNAMIC, motion = true),
            image("static", AlbumAssetKind.STATIC), image("live", AlbumAssetKind.DYNAMIC, motion = true)))
            .copy(title = "desktop title", width = 1920, height = 1080, bgmUrl = "https://sf11.douyinstatic.com/desktop.mp3")
        val result = AlbumKindPreservation.preserveKnownKinds(base, candidate)
        assertEquals(listOf(AlbumAssetKind.ANIMATED, AlbumAssetKind.STATIC, AlbumAssetKind.LIVE), result.images.map { it.kind })
        assertEquals(candidate.copy(images = candidate.images.mapIndexed { index, item ->
            item.copy(kind = result.images[index].kind)
        }), result)
        assertEquals(listOf(AlbumAssetKind.LIVE, AlbumAssetKind.ANIMATED, AlbumAssetKind.STATIC), base.images.map { it.kind })
        assertEquals(listOf(AlbumAssetKind.DYNAMIC, AlbumAssetKind.STATIC, AlbumAssetKind.DYNAMIC), candidate.images.map { it.kind })
    }

    @Test fun duplicateKeysInEitherArrayRemainUnknownEvenWithMatchingUrls() {
        val live = image("same", AlbumAssetKind.LIVE)
        val dynamic = image("same", AlbumAssetKind.DYNAMIC, motion = true)
        val duplicateBase = album(listOf(live, live.copy(kind = AlbumAssetKind.STATIC)))
        val singleCandidate = album(listOf(dynamic))
        assertSame(singleCandidate, AlbumKindPreservation.preserveKnownKinds(duplicateBase, singleCandidate))
        val uniqueBase = album(listOf(live))
        val duplicateCandidate = album(listOf(dynamic, dynamic.copy(kind = AlbumAssetKind.STATIC)))
        assertSame(duplicateCandidate, AlbumKindPreservation.preserveKnownKinds(uniqueBase, duplicateCandidate))
    }

    @Test fun blankKeysNeverFallBackToSameUrlOrPosition() {
        for (key in listOf("", " ")) {
            val base = album(listOf(image(key, AlbumAssetKind.LIVE)))
            val candidate = album(listOf(image(key, AlbumAssetKind.DYNAMIC, motion = true)))
            assertEquals(base.images.single().url, candidate.images.single().url)
            assertSame(candidate, AlbumKindPreservation.preserveKnownKinds(base, candidate))
        }
    }

    @Test fun aDifferentWorkCannotCarryOverItsClassification() {
        val base = album(listOf(image("same", AlbumAssetKind.LIVE)))
        val candidate = album(listOf(image("same", AlbumAssetKind.DYNAMIC, motion = true))).copy(id = "7397812747032468746")
        assertSame(candidate, AlbumKindPreservation.preserveKnownKinds(base, candidate))
    }

    @Test fun conflictingKeysNeverMatchByTheSameExactUrlOrSequencePosition() {
        val base = album(listOf(image("mobile", AlbumAssetKind.ANIMATED)))
        val candidate = album(listOf(image("desktop", AlbumAssetKind.DYNAMIC, motion = true)
            .copy(url = base.images.single().url, mediaSources = base.images.single().mediaSources)))
        assertSame(candidate, AlbumKindPreservation.preserveKnownKinds(base, candidate))
    }

    @Test fun explicitCandidateKindsRemainIntactIncludingOppositeKnownKindsAndStatic() {
        for (originalKind in listOf(AlbumAssetKind.LIVE, AlbumAssetKind.ANIMATED)) {
            val base = album(listOf(image("same", originalKind)))
            for (candidateKind in listOf(AlbumAssetKind.STATIC, AlbumAssetKind.LIVE, AlbumAssetKind.ANIMATED)) {
                val candidate = album(listOf(image("same", candidateKind, motion = true)))
                assertSame(candidate, AlbumKindPreservation.preserveKnownKinds(base, candidate))
            }
        }
    }

    @Test fun missingMotionAndPreviouslyUnknownTypesCannotBePromoted() {
        val known = album(listOf(image("same", AlbumAssetKind.LIVE)))
        val noMotion = album(listOf(image("same", AlbumAssetKind.DYNAMIC)))
        assertSame(noMotion, AlbumKindPreservation.preserveKnownKinds(known, noMotion))
        for (originalKind in listOf(AlbumAssetKind.STATIC, AlbumAssetKind.DYNAMIC)) {
            val base = album(listOf(image("same", originalKind)))
            val candidate = album(listOf(image("same", AlbumAssetKind.DYNAMIC, motion = true)))
            assertSame(candidate, AlbumKindPreservation.preserveKnownKinds(base, candidate))
        }
    }

    private fun image(key: String, kind: AlbumAssetKind, motion: Boolean = false): ParsedImage {
        val url = "https://p3.douyinpic.com/cover.jpg?signature=unchanged"
        val clip = "https://v3.douyinvod.com/$key.mp4?signature=unchanged"
        return ParsedImage(url, 1280, 720, listOf(MediaSource(url, WatermarkMode.CLEAN)), kind, "image/jpeg",
            if (motion) ParsedMotion(clip, 1920, 1080, 2.7, listOf(MediaSource(clip, WatermarkMode.CLEAN))) else null, key)
    }
    private fun album(images: List<ParsedImage>): ParsedVideo = ParsedVideo("7397812747032468745", "mobile title", "", 0.0,
        1280, 720, images = images, bgmUrl = "https://sf11.douyinstatic.com/mobile.mp3")
}
