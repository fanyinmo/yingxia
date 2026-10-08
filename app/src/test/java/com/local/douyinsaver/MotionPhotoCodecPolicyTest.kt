package com.local.douyinsaver

import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class MotionPhotoCodecPolicyTest {
    @Test fun allowsAllThreeOfficialMotionPhotoVideoCodecs() {
        // MediaFormat.MIMETYPE_VIDEO_AVC, MIMETYPE_VIDEO_HEVC and MIMETYPE_VIDEO_AV1.
        listOf("video/avc", "video/hevc", "video/av01").forEach {
            MotionPhotoCodecPolicy.requireCompatibleVideoMime(it)
        }
    }

    @Test fun rejectsOtherDecodableVideoCodecsRatherThanAssumingGalleryCompatibility() {
        listOf("video/mp4v-es", "video/3gpp", "video/mpeg2", "video/x-vnd.on2.vp8",
            "video/x-vnd.on2.vp9", "video/dolby-vision", "video/apv").forEach { mime ->
            assertThrows("Unsupported codec passed the Motion Photo gate: $mime", IllegalArgumentException::class.java) {
                MotionPhotoCodecPolicy.requireCompatibleVideoMime(mime)
            }
        }
    }

    @Test fun rejectsContainerMimeAudioUnknownAndSimilarNames() {
        // MP4/QuickTime identify a container, while avc1/h264/av1 are not official Android codec MIME values.
        listOf(null, "", "video/", "video/mp4", "video/quicktime", "audio/mp4a-latm", "image/jpeg",
            "video/avc1", "video/h264", "video/av1", "video/av01-extra", "video/avc;profile=high").forEach { mime ->
            assertThrows("Non-codec or imprecise MIME passed the gate: $mime", IllegalArgumentException::class.java) {
                MotionPhotoCodecPolicy.requireCompatibleVideoMime(mime)
            }
        }
    }

    @Test fun failureExplainsCompatibilityAndKeepsOriginalResourceAsTheSaveChoice() {
        val failure = assertThrows(IllegalArgumentException::class.java) {
            MotionPhotoCodecPolicy.requireCompatibleVideoMime("video/mp4v-es")
        }
        assertTrue(failure.message.orEmpty().contains("视频编码暂不兼容实况照片"))
        assertTrue(failure.message.orEmpty().contains("AVC、HEVC 或 AV1"))
        assertTrue(failure.message.orEmpty().contains("保存原始动态资源"))
    }
}
