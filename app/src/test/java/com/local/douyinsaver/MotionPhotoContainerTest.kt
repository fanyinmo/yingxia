package com.local.douyinsaver

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

class MotionPhotoContainerTest {
    @get:Rule val temporary = TemporaryFolder()
    private val prefix = "http://ns.adobe.com/xap/1.0/\u0000".toByteArray()
    private val camera = "http://ns.google.com/photos/1.0/camera/"
    private fun jpeg(xmp: String? = null, tail: ByteArray = byteArrayOf()): ByteArray =
        byteArrayOf(-1, -40) + segment(0xe1, "Exif\u0000\u0000unchanged".toByteArray()) +
            (xmp?.let { segment(0xe1, prefix + it.toByteArray()) } ?: byteArrayOf()) +
            segment(0xda, byteArrayOf(1, 1, 0, 0, 63, 0)) +
            byteArrayOf(1, 2, -1, 0, 3, -1, -48, 4, 5, -1, -39) + tail
    private fun segment(marker: Int, payload: ByteArray): ByteArray {
        val length = payload.size + 2
        return byteArrayOf(-1, marker.toByte(), (length ushr 8).toByte(), length.toByte()) + payload
    }
    private fun video(): ByteArray = byteArrayOf(0, 0, 0, 20) + "ftypisom".toByteArray() + ByteArray(100_000) { (it * 37).toByte() }
    private fun input(name: String, bytes: ByteArray): File = temporary.newFile(name).apply { writeBytes(bytes) }
    private fun xmp(body: String): String = "<x:xmpmeta xmlns:x='adobe:ns:meta/'><rdf:RDF xmlns:rdf='http://www.w3.org/1999/02/22-rdf-syntax-ns#'>$body</rdf:RDF></x:xmpmeta>"
    private fun legacy(length: Long, flag: Int = 1, timestamp: Long = 0): String = xmp("<rdf:Description xmlns:c='$camera' c:MicroVideo='$flag' c:MicroVideoVersion='1' c:MicroVideoOffset='$length' c:MicroVideoPresentationTimestampUs='$timestamp'/>")

    @Test fun singleFileContainsUntouchedExifJpegScanAndOriginalMp4Bytes() {
        val coverBytes = jpeg(); val mp4Bytes = video()
        val cover = input("cover.jpg", coverBytes); val mp4 = input("motion.mp4", mp4Bytes)
        val output = File(temporary.root, "output_MP.jpg")
        val progress = mutableListOf<Long>()
        val info = MotionPhotoContainer.write(cover, mp4, output, 900_000) { done, total ->
            assertTrue(done <= total); progress += done
        }
        assertEquals(info, MotionPhotoContainer.inspect(output))
        assertEquals(info, output.inputStream().use { MotionPhotoContainer.inspect(it, output.length()) })
        assertArrayEquals(coverBytes, cover.readBytes()); assertArrayEquals(mp4Bytes, mp4.readBytes())
        val data = output.readBytes()
        assertArrayEquals(mp4Bytes, data.copyOfRange(info.videoOffset.toInt(), data.size))
        val xmlEnd = 6 + ((data[4].toInt() and 255) shl 8 or (data[5].toInt() and 255)) - 2
        assertArrayEquals(coverBytes, byteArrayOf(-1, -40) + data.copyOfRange(xmlEnd, info.videoOffset.toInt()))
        assertEquals(900_000, info.presentationTimestampUs)
        assertEquals(output.length(), progress.last())
        assertTrue(progress.zipWithNext().all { (a, b) -> b >= a })
    }

    @Test fun xmpIsNamespaceAwareValidXmlWithBothModernAndObservedLegacyOffsets() {
        val cover = input("cover.jpg", jpeg()); val mp4 = input("motion.mp4", video())
        val output = File(temporary.root, "output_MP.jpg")
        val info = MotionPhotoContainer.write(cover, mp4, output)
        val data = output.readBytes()
        val length = ((data[4].toInt() and 255) shl 8 or (data[5].toInt() and 255))
        val xml = data.copyOfRange(6 + prefix.size, 4 + length)
        val document = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
            .newDocumentBuilder().parse(ByteArrayInputStream(xml))
        val description = document.getElementsByTagNameNS("http://www.w3.org/1999/02/22-rdf-syntax-ns#", "Description").item(0) as org.w3c.dom.Element
        assertEquals("1", description.getAttributeNS(camera, "MotionPhoto"))
        assertEquals("1", description.getAttributeNS(camera, "MicroVideo"))
        assertEquals(mp4.length().toString(), description.getAttributeNS(camera, "MicroVideoOffset"))
        assertEquals("-1", description.getAttributeNS(camera, "MotionPhotoPresentationTimestampUs"))
        assertEquals(-1, info.presentationTimestampUs)
    }

    @Test fun keepsUnrelatedMetadataAndReplacesConflictingOldMotionFlagsOnce() {
        val previous = xmp("<rdf:Description xmlns:c='$camera' xmlns:dc='http://purl.org/dc/elements/1.1/' c:MicroVideo='0' c:MicroVideoOffset='123'><dc:title>原始标题</dc:title><c:MotionPhoto>0</c:MotionPhoto></rdf:Description>")
        val cover = input("cover.jpg", jpeg(previous)); val mp4 = input("motion.mp4", video())
        val output = File(temporary.root, "output_MP.jpg")
        MotionPhotoContainer.write(cover, mp4, output)
        assertNotNull(MotionPhotoContainer.inspect(output))
        val text = output.readBytes().toString(Charsets.UTF_8)
        assertTrue(text.contains("原始标题")); assertFalse(text.contains("MicroVideoOffset=\"123\""))
        assertEquals(1, Regex("GCamera:MicroVideoOffset=").findAll(text).count())
    }

    @Test fun readsTheObservedDouyinLegacyJpegAndExtractsAllOriginalVideoBytes() {
        val mp4 = video(); val cover = jpeg(legacy(mp4.size.toLong()))
        val saved = input("douyin.jpg", cover + mp4)
        val info = MotionPhotoContainer.inspect(saved)!!
        assertEquals(cover.size.toLong(), info.videoOffset); assertEquals(mp4.size.toLong(), info.videoLength)
        val extracted = File(temporary.root, "preview.mp4")
        assertEquals(info, MotionPhotoContainer.extractVideo(saved, extracted))
        assertArrayEquals(mp4, extracted.readBytes())
        assertArrayEquals(cover + mp4, saved.readBytes())
    }

    @Test fun plainJpegHasNoMotionAndAnExplicitZeroFlagSuppressesLegacy() {
        assertNull(MotionPhotoContainer.inspect(input("plain.jpg", jpeg())))
        val xml = xmp("<rdf:Description xmlns:c='$camera' c:MotionPhoto='0' c:MicroVideo='1' c:MicroVideoVersion='1' c:MicroVideoOffset='120'/>")
        assertNull(MotionPhotoContainer.inspect(input("disabled.jpg", jpeg(xml))))
    }

    @Test fun rejectsTruncatedOffsetAudioOrHtmlMasqueradingAsEmbeddedVideo() {
        val badLength = input("wrong.jpg", jpeg(legacy(999_999)))
        assertTrue(runCatching { MotionPhotoContainer.inspect(badLength) }.isFailure)
        val html = "not a video".repeat(20).toByteArray()
        val badVideo = input("html.jpg", jpeg(legacy(html.size.toLong()), tail = html))
        assertTrue(runCatching { MotionPhotoContainer.inspect(badVideo) }.isFailure)
    }

    @Test fun rejectsDuplicateConflictingOffsetsInsteadOfChoosingOne() {
        val xml = xmp("<rdf:Description xmlns:c='$camera' c:MicroVideo='1' c:MicroVideoVersion='1' c:MicroVideoOffset='120'><c:MicroVideoOffset>121</c:MicroVideoOffset></rdf:Description>")
        assertTrue(runCatching { MotionPhotoContainer.inspect(input("conflict.jpg", jpeg(xml, video()))) }.isFailure)
    }

    @Test fun neverOverwritesInputsOrExistingOutputs() {
        val cover = input("cover.jpg", jpeg()); val mp4 = input("motion.mp4", video())
        val existing = input("existing.jpg", "keep existing bytes".toByteArray())
        assertTrue(runCatching { MotionPhotoContainer.write(cover, mp4, cover) }.isFailure)
        assertTrue(runCatching { MotionPhotoContainer.write(cover, mp4, mp4) }.isFailure)
        assertTrue(runCatching { MotionPhotoContainer.write(cover, mp4, existing) }.isFailure)
        assertEquals("keep existing bytes", existing.readText())
        assertArrayEquals(jpeg(), cover.readBytes()); assertArrayEquals(video(), mp4.readBytes())
    }

    @Test fun rejectsAlreadyAppendedMediaHdrAndExternalEntityWithoutCreatingDestination() {
        val mp4 = input("motion.mp4", video())
        val samples = listOf(jpeg(tail = video()), jpeg(xmp("<rdf:Description xmlns:h='http://ns.adobe.com/hdr-gain-map/1.0/' h:Version='1.0'/>") ),
            jpeg("<!DOCTYPE x [<!ENTITY leak SYSTEM 'file:///never-read'>]>" + xmp("<rdf:Description>&leak;</rdf:Description>")))
        samples.forEachIndexed { index, bytes ->
            val cover = input("cover$index.jpg", bytes); val output = File(temporary.root, "failed$index.jpg")
            assertTrue(runCatching { MotionPhotoContainer.write(cover, mp4, output) }.isFailure)
            assertFalse(output.exists()); assertArrayEquals(bytes, cover.readBytes())
        }
    }

    @Test fun cancellationDuringStreamingRemovesPartialNewFileAndKeepsSources() {
        val cover = input("cover.jpg", jpeg()); val mp4 = input("motion.mp4", video())
        val output = File(temporary.root, "cancelled.jpg")
        var checks = 0
        assertTrue(runCatching { MotionPhotoContainer.write(cover, mp4, output, checkActive = {
            if (++checks >= 3) throw IllegalStateException("controlled cancellation")
        }) }.isFailure)
        assertFalse(output.exists()); assertArrayEquals(jpeg(), cover.readBytes()); assertArrayEquals(video(), mp4.readBytes())
    }

    @Test fun supportsProgressiveScansWithoutConfusingStuffedOrRestartMarkers() {
        val original = jpeg().dropLast(2).toByteArray() + segment(0xc4, byteArrayOf(0, 1)) +
            segment(0xda, byteArrayOf(1, 1, 0, 1, 63, 0)) + byteArrayOf(7, -1, 0, 8, -1, -41, 9, -1, -39)
        val cover = input("progressive.jpg", original); val mp4 = input("motion.mp4", video())
        val output = File(temporary.root, "progressive_MP.jpg")
        assertNotNull(MotionPhotoContainer.write(cover, mp4, output)); assertNotNull(MotionPhotoContainer.inspect(output))
    }

    @Test fun namesSatisfyAndroidMotionPhotoSuffixPattern() {
        assertEquals("标题_001_MP.jpg", MotionPhotoContainer.fileName("标题_001"))
        assertEquals("标题_MP.jpg", MotionPhotoContainer.fileName("标题.jpg"))
    }

    @Test fun xiaomiProfileRequiresActualCoverTimeAndPreservesCompressedPixelsAndMotionBytes() {
        // A JPEG without EXIF makes this test independent of the writer's TIFF updater.
        val coverBytes = byteArrayOf(-1, -40) + segment(0xda, byteArrayOf(1, 1, 0, 0, 63, 0)) +
            byteArrayOf(1, 2, -1, 0, 3, -1, -39)
        val cover = input("cover-without-exif.jpg", coverBytes); val mp4 = input("motion.mp4", video())
        val unknown = File(temporary.root, "unknown_MP.jpg")
        assertTrue(runCatching { MotionPhotoContainer.write(cover, mp4, unknown, format = MotionPhotoContainer.Format.XIAOMI) }.isFailure)
        assertFalse(unknown.exists())
        val output = File(temporary.root, "xiaomi_MP.jpg")
        val info = MotionPhotoContainer.write(cover, mp4, output, 0, MotionPhotoContainer.Format.XIAOMI)
        assertEquals(info, MotionPhotoContainer.inspect(output))
        val data = output.readBytes()
        assertArrayEquals(mp4.readBytes(), data.copyOfRange(info.videoOffset.toInt(), data.size))
        assertArrayEquals(coverBytes.copyOfRange(2, coverBytes.size),
            data.copyOfRange(info.videoOffset.toInt() - coverBytes.size + 2, info.videoOffset.toInt()))
        val length = ((data[4].toInt() and 255) shl 8 or (data[5].toInt() and 255))
        val document = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }.newDocumentBuilder()
            .parse(ByteArrayInputStream(data.copyOfRange(6 + prefix.size, 4 + length)))
        val description = document.getElementsByTagNameNS("http://www.w3.org/1999/02/22-rdf-syntax-ns#", "Description").item(0) as org.w3c.dom.Element
        assertEquals("0", description.getAttributeNS(camera, "MicroVideoPresentationTimestampUs"))
        assertEquals("<?xml version='1.0' encoding='UTF-8' standalone='yes' ?>",
            description.getAttributeNS("http://ns.xiaomi.com/photos/1.0/camera/", "XMPMeta"))
        assertArrayEquals(coverBytes, cover.readBytes())
    }
}
