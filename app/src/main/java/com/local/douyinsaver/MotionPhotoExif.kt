package com.local.douyinsaver

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Minimal, lossless TIFF update for the Xiaomi MicroVideo byte flag (0x8897).
 * Compatibility readers exist for both IFD0 and Exif IFD, so both carry the byte flag.
 * Existing data/offsets remain in place; only the selected IFD pointer changes when
 * a new directory is appended. No camera model, capture time or opaque vendor tag is invented.
 * Format evidence: Xiaomi SDK guide pId=2003, public MotionPhotoConverter/exif.py,
 * and karin-plugin-kkk/MotionPhoto.ts. Implementation is independent; vendor-gallery
 * playback remains a separate device check.
 */
internal object MotionPhotoExif {
    private val prefix = "Exif\u0000\u0000".toByteArray(Charsets.US_ASCII)
    private const val EXIF_IFD = 0x8769
    private const val MICRO_VIDEO = 0x8897

    fun isExif(bytes: ByteArray): Boolean = bytes.size >= prefix.size &&
        bytes.copyOfRange(0, prefix.size).contentEquals(prefix)

    fun withXiaomiMotionFlag(payload: ByteArray?): ByteArray {
        val original = payload?.also { require(isExif(it)) { "实况封面的 EXIF 标记无效" } }
            ?.copyOfRange(prefix.size, payload.size)
            ?: byteArrayOf(0x49, 0x49, 42, 0, 8, 0, 0, 0, 0, 0, 0, 0, 0, 0)
        val tiff = Tiff(original)
        val primary = tiff.directory(tiff.uint32(4))
        val pointers = primary.entries.filter { tiff.uint16(it) == EXIF_IFD }
        require(pointers.size <= 1) { "实况封面的 EXIF 目录冲突" }
        val pointer = pointers.singleOrNull()
        val exif = pointer?.let {
            require(tiff.uint16(it + 2) == 4 && tiff.uint32(it + 4) == 1L) { "实况封面的 EXIF 指针无效" }
            tiff.directory(tiff.uint32(it + 8))
        }
        val flags = exif?.entries.orEmpty().filter { tiff.uint16(it) == MICRO_VIDEO }
        val primaryFlags = primary.entries.filter { tiff.uint16(it) == MICRO_VIDEO }
        require(flags.size <= 1 && primaryFlags.size <= 1) { "实况封面的动态 EXIF 标记冲突" }
        fun isSet(entry: Int) = tiff.uint16(entry + 2) == 1 && tiff.uint32(entry + 4) == 1L && original[entry + 8].toInt() == 1
        if (flags.singleOrNull()?.let(::isSet) == true && primaryFlags.singleOrNull()?.let(::isSet) == true) return prefix + original
        val exifEntries = exif?.entries.orEmpty().filter { tiff.uint16(it) != MICRO_VIDEO }
            .map { original.copyOfRange(it, it + 12) } +
            tiff.entry(MICRO_VIDEO, 1, 1, byteArrayOf(1, 0, 0, 0))
        val appendedExif = tiff.encodeDirectory(exifEntries, exif?.next ?: 0)
        val alignedOriginal = if (original.size % 2 == 0) original else original + byteArrayOf(0)
        val exifOffset = alignedOriginal.size
        val newPrimaryOffset = exifOffset + appendedExif.size
        val primaryEntries = primary.entries.filter { tiff.uint16(it) !in setOf(EXIF_IFD, MICRO_VIDEO) }
            .map { original.copyOfRange(it, it + 12) } + listOf(
                tiff.entry(EXIF_IFD, 4, 1, ByteBuffer.allocate(4).order(tiff.order).putInt(exifOffset).array()),
                tiff.entry(MICRO_VIDEO, 1, 1, byteArrayOf(1, 0, 0, 0)))
        val appendedPrimary = tiff.encodeDirectory(primaryEntries, primary.next)
        return prefix + (alignedOriginal + appendedExif + appendedPrimary).also {
            ByteBuffer.wrap(it).order(tiff.order).putInt(4, newPrimaryOffset)
        }
    }

    private class Tiff(val bytes: ByteArray) {
        val order: ByteOrder
        private val input: ByteBuffer
        init {
            require(bytes.size >= 8) { "实况封面的 EXIF 数据不完整" }
            order = when (bytes.copyOfRange(0, 2).toString(Charsets.US_ASCII)) {
                "II" -> ByteOrder.LITTLE_ENDIAN
                "MM" -> ByteOrder.BIG_ENDIAN
                else -> error("实况封面的 EXIF 字节序无效")
            }
            input = ByteBuffer.wrap(bytes).order(order)
            require(uint16(2) == 42) { "实况封面的 EXIF 版本无效" }
        }
        fun uint16(offset: Int): Int {
            require(offset >= 0 && offset <= bytes.size - 2) { "实况封面的 EXIF 越界" }
            return input.getShort(offset).toInt() and 0xffff
        }
        fun uint32(offset: Int): Long {
            require(offset >= 0 && offset <= bytes.size - 4) { "实况封面的 EXIF 越界" }
            return input.getInt(offset).toLong() and 0xffff_ffffL
        }
        data class Directory(val entries: List<Int>, val next: Long)
        fun directory(offset: Long): Directory {
            require(offset >= 8 && offset <= bytes.size - 6L) { "实况封面的 EXIF 目录越界" }
            val base = offset.toInt(); val count = uint16(base)
            require(count <= 4_096 && 2L + count * 12L + 4L <= bytes.size - offset) { "实况封面的 EXIF 目录不完整" }
            return Directory((0 until count).map { base + 2 + it * 12 }, uint32(base + 2 + count * 12))
        }
        fun entry(tag: Int, type: Int, count: Int, value: ByteArray): ByteArray =
            ByteBuffer.allocate(12).order(order).putShort(tag.toShort()).putShort(type.toShort())
                .putInt(count).put(value).array()
        fun encodeDirectory(entries: List<ByteArray>, next: Long): ByteArray {
            require(entries.size <= 4_096)
            val sorted = entries.sortedBy { ByteBuffer.wrap(it).order(order).short.toInt() and 0xffff }
            return ByteBuffer.allocate(2 + sorted.size * 12 + 4).order(order).apply {
                putShort(sorted.size.toShort()); sorted.forEach(::put); putInt(next.toInt())
            }.array()
        }
    }
}
