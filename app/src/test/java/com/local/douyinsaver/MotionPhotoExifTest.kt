package com.local.douyinsaver

import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class MotionPhotoExifTest {
    private val prefix = "Exif\u0000\u0000".toByteArray()

    @Test fun newExifCreatesOneByteMicroVideoFlagWithoutInventingCameraOrCaptureMetadata() {
        val payload = MotionPhotoExif.withXiaomiMotionFlag(null)
        val tiff = payload.copyOfRange(6, payload.size)
        val input = ByteBuffer.wrap(tiff).order(ByteOrder.LITTLE_ENDIAN)
        val primary = input.getInt(4)
        assertEquals(2, input.getShort(primary).toInt())
        assertEquals(0x8769, input.getShort(primary + 2).toInt() and 0xffff)
        val exif = input.getInt(primary + 10)
        assertEquals(0x8897, input.getShort(primary + 14).toInt() and 0xffff)
        assertEquals(1, tiff[primary + 22].toInt())
        assertEquals(1, input.getShort(exif).toInt())
        assertEquals(0x8897, input.getShort(exif + 2).toInt() and 0xffff)
        assertEquals(1, input.getShort(exif + 4).toInt())
        assertEquals(1, input.getInt(exif + 6))
        assertEquals(1, tiff[exif + 10].toInt())
        assertEquals(0, input.getInt(exif + 14))
        assertFalse(payload.toString(Charsets.ISO_8859_1).contains("Xiaomi"))
    }

    @Test fun appendingAnExifFlagPreservesOpaqueBytesOffsetsThumbnailChainAndBothByteOrders() {
        listOf(ByteOrder.LITTLE_ENDIAN, ByteOrder.BIG_ENDIAN).forEach { order ->
            val input = ByteBuffer.allocate(120).order(order)
            input.put(if (order == ByteOrder.LITTLE_ENDIAN) byteArrayOf(73, 73) else byteArrayOf(77, 77))
            input.putShort(42); input.putInt(8)
            input.putShort(2)
            input.putShort(0x100); input.putShort(4); input.putInt(1); input.putInt(1440)
            input.putShort(0x8769.toShort()); input.putShort(4); input.putInt(1); input.putInt(38)
            input.putInt(80) // Existing thumbnail directory.
            input.putShort(2)
            input.putShort(0x927c.toShort()); input.putShort(7); input.putInt(16); input.putInt(104)
            input.putShort(0x9a01.toShort()); input.putShort(1); input.putInt(1); input.put(byteArrayOf(1, 0, 0, 0))
            input.putInt(0)
            input.position(80); input.putShort(0); input.putInt(0)
            input.position(104); input.put(ByteArray(16) { (it + 17).toByte() })
            val original = input.array()
            val written = MotionPhotoExif.withXiaomiMotionFlag(prefix + original)
            val result = written.copyOfRange(6, written.size)
            val parsed = ByteBuffer.wrap(result).order(order)
            val changed = original.copyOf().also { ByteBuffer.wrap(it).order(order).putInt(4, parsed.getInt(4)) }
            assertArrayEquals("Only the primary IFD pointer can change in existing TIFF bytes", changed, result.copyOfRange(0, original.size))
            val primary = parsed.getInt(4)
            assertEquals(3, parsed.getShort(primary).toInt())
            assertEquals(80, parsed.getInt(primary + 38))
            assertArrayEquals("Opaque MakerNote bytes stay at their original offset", original.copyOfRange(104, 120), result.copyOfRange(104, 120))
            val exif = parsed.getInt(primary + 22)
            assertEquals(3, parsed.getShort(exif).toInt())
            val rows = (0 until 3).associate { i ->
                val entry = exif + 2 + i * 12
                (parsed.getShort(entry).toInt() and 0xffff) to entry
            }
            assertEquals(104, parsed.getInt(rows.getValue(0x927c) + 8))
            assertEquals(1, result[rows.getValue(0x9a01) + 8].toInt())
            assertEquals(1, result[rows.getValue(0x8897) + 8].toInt())
        }
    }

    @Test fun replacingAnExistingFlagDoesNotDuplicateOrRelocateExifDirectories() {
        val first = MotionPhotoExif.withXiaomiMotionFlag(null)
        val second = MotionPhotoExif.withXiaomiMotionFlag(first)
        assertArrayEquals(first, second)
    }

    @Test fun malformedOrDuplicateExifPointersAreRejectedRatherThanDroppingMetadata() {
        assertTrue(runCatching { MotionPhotoExif.withXiaomiMotionFlag(prefix + byteArrayOf(1, 2, 3)) }.isFailure)
        val data = ByteBuffer.allocate(38).order(ByteOrder.LITTLE_ENDIAN).apply {
            put(byteArrayOf(73, 73)); putShort(42); putInt(8); putShort(2)
            repeat(2) { putShort(0x8769.toShort()); putShort(4); putInt(1); putInt(0x7fffffff) }
            putInt(0)
        }.array()
        assertTrue(runCatching { MotionPhotoExif.withXiaomiMotionFlag(prefix + data) }.isFailure)
    }
}
