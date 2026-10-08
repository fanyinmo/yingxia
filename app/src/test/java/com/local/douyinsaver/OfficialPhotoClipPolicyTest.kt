package com.local.douyinsaver

import org.junit.Assert.*
import org.junit.Test

class OfficialPhotoClipPolicyTest {
    @Test fun anOlderUntaggedPhotoCanStillProvideItsOwnVideo() {
        assertTrue(OfficialPhotoClipPolicy.allowsVideo(false, null))
        assertFalse(OfficialPhotoClipPolicy.declaresLive(null))
    }

    @Test fun onlyOfficialVideoLiveAndDefaultClipsCanUseTaggedVideoMetadata() {
        assertTrue(OfficialPhotoClipPolicy.allowsVideo(true, 1))
        assertTrue(OfficialPhotoClipPolicy.allowsVideo(true, 3))
        assertTrue(OfficialPhotoClipPolicy.allowsVideo(true, 4))
        assertFalse(OfficialPhotoClipPolicy.declaresLive(1))
        assertFalse(OfficialPhotoClipPolicy.declaresLive(4))
    }

    @Test fun anOfficialImageClipCannotTurnItsVideoPlaceholderIntoMotion() {
        assertFalse(OfficialPhotoClipPolicy.allowsVideo(true, 2))
        assertFalse(OfficialPhotoClipPolicy.declaresLive(2))
    }

    @Test fun aDeclaredLivePhotoRemainsLiveEvenBeforeItsVideoIsAvailable() {
        assertTrue(OfficialPhotoClipPolicy.declaresLive(3))
        assertTrue(OfficialPhotoClipPolicy.allowsVideo(true, 3))
    }

    @Test fun guessedUnknownAndCoercedClipTagsCannotProvideVideoOrAFalseLiveDeclaration() {
        for (tag in listOf(0, 5, -1, "3", true, Double.NaN, Double.POSITIVE_INFINITY, null)) {
            assertFalse("unexpected playable tag $tag", OfficialPhotoClipPolicy.allowsVideo(true, tag))
            assertFalse("unexpected live tag $tag", OfficialPhotoClipPolicy.declaresLive(tag))
        }
    }

    @Test fun jsonNumericRepresentationsHaveTheSameMeaningAsJavascriptNumbers() {
        for (tag in listOf(3, 3L, 3.0, 3.0f)) {
            assertTrue(OfficialPhotoClipPolicy.allowsVideo(true, tag))
            assertTrue(OfficialPhotoClipPolicy.declaresLive(tag))
        }
        assertTrue(OfficialPhotoClipPolicy.allowsVideo(true, 1L))
        assertTrue(OfficialPhotoClipPolicy.allowsVideo(true, 4.0))
        assertFalse(OfficialPhotoClipPolicy.allowsVideo(true, 2.0))
    }
}
