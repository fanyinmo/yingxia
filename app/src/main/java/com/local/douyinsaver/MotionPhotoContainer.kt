package com.local.douyinsaver

import org.w3c.dom.Document
import org.w3c.dom.Element
import org.w3c.dom.Node
import org.xml.sax.InputSource
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.io.RandomAccessFile
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.transform.OutputKeys
import javax.xml.transform.TransformerFactory
import javax.xml.transform.dom.DOMSource
import javax.xml.transform.stream.StreamResult

/** JPEG + original MP4 in one file, following Android Motion Photo 1.0.
 * The legacy MicroVideo fields also match the user's Douyin-saved JPEG.
 * An explicit Xiaomi profile adds its recognition metadata; this does not certify
 * playback in a particular phone's system gallery.
 * No image pixels or video samples are re-encoded by this container writer.
 */
internal object MotionPhotoContainer {
    enum class Format { STANDARD, XIAOMI }
    private const val CAMERA = "http://ns.google.com/photos/1.0/camera/"
    private const val MI_CAMERA = "http://ns.xiaomi.com/photos/1.0/camera/"
    private const val CONTAINER = "http://ns.google.com/photos/1.0/container/"
    private const val ITEM = "http://ns.google.com/photos/1.0/container/item/"
    private const val RDF = "http://www.w3.org/1999/02/22-rdf-syntax-ns#"
    private val xmpPrefix = "http://ns.adobe.com/xap/1.0/\u0000".toByteArray(Charsets.US_ASCII)
    private val extendedXmpPrefix = "http://ns.adobe.com/xmp/extension/\u0000".toByteArray(Charsets.US_ASCII)
    private val cameraProperties = setOf("MotionPhoto", "MotionPhotoVersion", "MotionPhotoPresentationTimestampUs",
        "MicroVideo", "MicroVideoVersion", "MicroVideoOffset", "MicroVideoPresentationTimestampUs")

    data class Info(val videoOffset: Long, val videoLength: Long, val presentationTimestampUs: Long = -1) {
        val totalBytes: Long get() = videoOffset + videoLength
    }

    /** Returns null for a plain JPEG. A declared but malformed motion photo is an error. */
    fun inspect(file: File): Info? = RandomAccessFile(file, "r").use { input ->
        val header = readHeader(input)
        val documents = header.xmp.map(::parseXml)
        val declarations = documents.mapNotNull { metadata(it, file.length()) }
        if (declarations.isEmpty()) return@use null
        require(declarations.distinct().size == 1) { "实况照片包含冲突的动态信息" }
        val info = declarations.first()
        require(info.videoOffset >= header.scanOffset && info.videoLength >= 12L) { "实况照片的动态偏移无效" }
        require(info.videoOffset + info.videoLength == input.length()) { "实况照片的动态文件长度不完整" }
        input.seek(info.videoOffset)
        val mp4Header = ByteArray(12).also(input::readFully)
        require(mp4Header.copyOfRange(4, 8).toString(Charsets.US_ASCII) == "ftyp") { "实况照片没有有效 MP4 内容" }
        info
    }

    /** ContentResolver streams can be read without copying the whole image into memory. */
    fun inspect(input: InputStream, totalBytes: Long): Info? {
        require(totalBytes >= 4) { "实况照片文件为空" }
        val stream = DataInputStream(input)
        require(stream.readUnsignedShort() == 0xffd8) { "实况封面必须是 JPEG 图片" }
        val packets = mutableListOf<ByteArray>()
        var position = 2L
        var scanOffset = -1L
        var segments = 0
        while (position < totalBytes) {
            require(++segments <= 16_384 && position <= 4L * 1024 * 1024) { "JPEG 元数据过大" }
            require(stream.readUnsignedByte() == 255) { "JPEG 头部结构无效" }; position++
            var marker = stream.readUnsignedByte(); position++
            while (marker == 255) { marker = stream.readUnsignedByte(); position++ }
            require(marker !in listOf(0, 0xd8, 0xd9) && marker !in 0xd0..0xd7) { "JPEG 头部结构无效" }
            if (marker == 0x01) continue
            val length = stream.readUnsignedShort(); position += 2
            require(length >= 2 && length - 2 <= totalBytes - position) { "JPEG 元数据不完整" }
            if (marker == 0xe1) {
                val payload = ByteArray(length - 2).also(stream::readFully)
                if (payload.startsWith(xmpPrefix)) packets += payload.copyOfRange(xmpPrefix.size, payload.size)
            } else skipFully(stream, (length - 2).toLong())
            position += length - 2
            if (marker == 0xda) { scanOffset = position; break }
        }
        require(scanOffset >= 0) { "JPEG 图片没有扫描数据" }
        val declarations = packets.map(::parseXml).mapNotNull { metadata(it, totalBytes) }
        if (declarations.isEmpty()) return null
        require(declarations.distinct().size == 1) { "实况照片包含冲突的动态信息" }
        val info = declarations.first()
        require(info.videoOffset >= scanOffset && info.videoLength >= 12L && info.totalBytes == totalBytes) { "实况照片动态偏移无效" }
        skipFully(stream, info.videoOffset - position)
        val mp4Header = ByteArray(12).also(stream::readFully)
        require(mp4Header.copyOfRange(4, 8).toString(Charsets.US_ASCII) == "ftyp") { "实况照片没有有效 MP4 内容" }
        return info
    }

    /** Destination must be a new, private file. Caller validates JPEG pixels and MP4 tracks first.
     * Unknown cover-frame correspondence is -1; a timestamp is never guessed from duration.
     */
    fun write(
        jpeg: File,
        video: File,
        destination: File,
        presentationTimestampUs: Long = -1,
        format: Format = Format.STANDARD,
        checkActive: () -> Unit = {},
        onProgress: (Long, Long) -> Unit = { _, _ -> },
    ): Info {
        require(presentationTimestampUs >= -1) { "实况封面时间戳无效" }
        require(format != Format.XIAOMI || presentationTimestampUs >= 0) {
            "小米兼容实况需要确认封面对应的动态帧，请使用动图转实况功能"
        }
        require(jpeg.canonicalFile != video.canonicalFile && destination.canonicalFile != jpeg.canonicalFile &&
            destination.canonicalFile != video.canonicalFile) { "实况输出不能覆盖原文件" }
        require(!destination.exists()) { "实况输出文件已经存在" }
        require(video.length() >= 12L) { "实况动态文件为空" }
        val header = RandomAccessFile(jpeg, "r").use(::readHeader)
        require(!header.hasExtendedXmp) { "这张图片使用扩展元数据，暂不重新封装实况，原文件已保留" }
        val xml = mergedMetadata(header.xmp, video.length(), presentationTimestampUs, format)
        val packet = xmpPrefix + xml
        require(packet.size <= 65_533) { "图片元数据过大，无法封装实况" }
        val exif = if (format == Format.XIAOMI) {
            require(header.exif.size <= 1) { "实况封面包含冲突的 EXIF 目录" }
            MotionPhotoExif.withXiaomiMotionFlag(header.exif.singleOrNull()).also {
                require(it.size <= 65_533) { "EXIF 元数据过大，无法封装小米兼容实况" }
            }
        } else null
        require(jpegEnd(jpeg, header.scanOffset, checkActive) == jpeg.length()) {
            "封面含有额外媒体内容，不能覆盖原有实况或 HDR 数据"
        }
        video.inputStream().use { input ->
            val bytes = ByteArray(12)
            require(input.read(bytes) == bytes.size && bytes.copyOfRange(4, 8).toString(Charsets.US_ASCII) == "ftyp") {
                "实况动态文件不是 MP4"
            }
        }
        val removedRanges = (header.xmpRanges + if (exif == null) emptyList() else header.exifRanges).sortedBy { it.start }
        val removed = removedRanges.sumOf { it.end - it.start }
        val videoOffset = jpeg.length() - removed + packet.size + 4L + (exif?.let { it.size + 4L } ?: 0L)
        val expected = Info(videoOffset, video.length(), presentationTimestampUs)
        var owned = false
        var finished = false
        try {
            checkActive()
            check(destination.createNewFile()) { "无法创建实况文件" }
            owned = true
            destination.outputStream().buffered().use { output ->
                output.write(byteArrayOf(0xff.toByte(), 0xd8.toByte(), 0xff.toByte(), 0xe1.toByte()))
                val length = packet.size + 2
                output.write(length ushr 8); output.write(length and 255); output.write(packet)
                var copied = packet.size + 6L
                if (exif != null) {
                    val exifLength = exif.size + 2
                    output.write(255); output.write(0xe1); output.write(exifLength ushr 8); output.write(exifLength and 255)
                    output.write(exif); copied += exif.size + 4L
                }
                RandomAccessFile(jpeg, "r").use { input ->
                    var offset = 2L
                    for (range in removedRanges + Range(jpeg.length(), jpeg.length())) {
                        copyRange(input, offset, range.start - offset, output, checkActive) { bytes ->
                            copied += bytes; onProgress(copied, expected.totalBytes)
                        }
                        offset = range.end
                    }
                }
                video.inputStream().buffered().use { input ->
                    copy(input, output, video.length(), checkActive) { bytes ->
                        copied += bytes; onProgress(copied, expected.totalBytes)
                    }
                }
                require(copied == expected.totalBytes) { "实况文件复制不完整" }
            }
            checkActive()
            require(destination.length() == expected.totalBytes && inspect(destination) == expected) { "实况照片结构校验失败" }
            finished = true
            return expected
        } finally { if (owned && !finished) destination.delete() }
    }

    fun extractVideo(file: File, destination: File, checkActive: () -> Unit = {}): Info {
        val info = inspect(file) ?: error("这不是实况照片")
        require(destination.canonicalFile != file.canonicalFile && !destination.exists()) { "动态输出不能覆盖已有文件" }
        var owned = false
        var finished = false
        try {
            checkActive()
            check(destination.createNewFile()) { "无法创建动态预览文件" }; owned = true
            RandomAccessFile(file, "r").use { input ->
                destination.outputStream().buffered().use { output ->
                    copyRange(input, info.videoOffset, info.videoLength, output, checkActive) { }
                }
            }
            require(destination.length() == info.videoLength) { "动态内容提取不完整" }
            checkActive(); finished = true
            return info
        } finally { if (owned && !finished) destination.delete() }
    }

    fun fileName(base: String): String = "${base.removeSuffix(".jpg").removeSuffix(".jpeg")}_MP.jpg"

    private data class Range(val start: Long, val end: Long)
    private data class Header(val xmp: List<ByteArray>, val xmpRanges: List<Range>, val exif: List<ByteArray>,
        val exifRanges: List<Range>, val scanOffset: Long, val hasExtendedXmp: Boolean)
    private fun readHeader(input: RandomAccessFile): Header {
        require(input.length() >= 4 && input.readUnsignedShort() == 0xffd8) { "实况封面必须是 JPEG 图片" }
        val packets = mutableListOf<ByteArray>()
        val ranges = mutableListOf<Range>()
        val exif = mutableListOf<ByteArray>()
        val exifRanges = mutableListOf<Range>()
        var extended = false
        var segments = 0
        while (input.filePointer < input.length()) {
            require(++segments <= 16_384) { "JPEG 元数据结构异常" }
            val start = input.filePointer
            require(input.readUnsignedByte() == 255) { "JPEG 头部结构无效" }
            var marker = input.readUnsignedByte()
            while (marker == 255) marker = input.readUnsignedByte()
            require(marker !in listOf(0, 0xd8, 0xd9) && marker !in 0xd0..0xd7) { "JPEG 头部结构无效" }
            if (marker == 0x01) continue
            val length = input.readUnsignedShort()
            require(length >= 2 && length - 2 <= input.length() - input.filePointer) { "JPEG 元数据不完整" }
            if (marker == 0xda) return Header(packets, ranges, exif, exifRanges, input.filePointer + length - 2, extended)
            if (marker == 0xe1) {
                val payload = ByteArray(length - 2).also(input::readFully)
                if (payload.startsWith(xmpPrefix)) {
                    packets += payload.copyOfRange(xmpPrefix.size, payload.size)
                    ranges += Range(start, input.filePointer)
                }
                if (payload.startsWith(extendedXmpPrefix)) extended = true
                if (MotionPhotoExif.isExif(payload)) { exif += payload; exifRanges += Range(start, input.filePointer) }
            } else input.seek(input.filePointer + length - 2)
        }
        error("JPEG 图片没有扫描数据")
    }

    /** Understands JPEG byte stuffing, restart markers and progressive multiple scans. */
    private fun jpegEnd(file: File, scanOffset: Long, checkActive: () -> Unit): Long {
        file.inputStream().buffered().use { input ->
            skipFully(input, scanOffset)
            var position = scanOffset
            var entropy = true
            while (true) {
                if (position and 0xffffL == 0L) checkActive()
                val prefix = input.read(); require(prefix >= 0) { "JPEG 图片缺少结束标记" }; position++
                if (entropy && prefix != 255) continue
                require(prefix == 255) { "JPEG 扫描结构无效" }
                var marker = input.read(); require(marker >= 0) { "JPEG 图片不完整" }; position++
                while (marker == 255) { marker = input.read(); require(marker >= 0); position++ }
                if (entropy && (marker == 0 || marker in 0xd0..0xd7)) continue
                if (marker == 0xd9) return position
                require(marker != 0 && marker != 0xd8) { "JPEG 扫描标记无效" }
                if (marker == 0x01) continue
                val high = input.read(); val low = input.read(); position += 2
                require(high >= 0 && low >= 0) { "JPEG 扫描不完整" }
                val length = (high shl 8) or low
                require(length >= 2) { "JPEG 扫描长度无效" }
                skipFully(input, (length - 2).toLong()); position += length - 2
                entropy = marker == 0xda || marker == 0xdc
            }
        }
    }

    private fun mergedMetadata(packets: List<ByteArray>, videoLength: Long, timestamp: Long, format: Format): ByteArray {
        val documents = packets.map(::parseXml)
        documents.forEach { document ->
            require(document.getElementsByTagNameNS("http://ns.adobe.com/hdr-gain-map/1.0/", "*").length == 0 &&
                !document.documentElement.textContent.contains("GainMap")) { "HDR 封面元数据不能作为普通 JPEG 改写" }
            val all = document.getElementsByTagName("*")
            for (i in 0 until all.length) {
                val attributes = all.item(i).attributes
                for (j in 0 until attributes.length) require(attributes.item(j).namespaceURI != "http://ns.adobe.com/hdr-gain-map/1.0/") {
                    "HDR 封面元数据不能作为普通 JPEG 改写"
                }
            }
        }
        val result = parseXml("<x:xmpmeta xmlns:x=\"adobe:ns:meta/\"><rdf:RDF xmlns:rdf=\"$RDF\"/></x:xmpmeta>".toByteArray())
        val rdf = result.getElementsByTagNameNS(RDF, "RDF").item(0) as Element
        documents.forEach { document ->
            val descriptions = document.getElementsByTagNameNS(RDF, "Description")
            for (i in 0 until descriptions.length) {
                val copied = result.importNode(descriptions.item(i), true) as Element
                removeMotionMetadata(copied)
                rdf.appendChild(copied)
            }
        }
        val motion = result.createElementNS(RDF, "rdf:Description").apply {
            setAttributeNS(RDF, "rdf:about", "")
            setAttribute("xmlns:GCamera", CAMERA); setAttribute("xmlns:Container", CONTAINER); setAttribute("xmlns:Item", ITEM)
            mapOf("MotionPhoto" to "1", "MotionPhotoVersion" to "1", "MotionPhotoPresentationTimestampUs" to "$timestamp",
                "MicroVideo" to "1", "MicroVideoVersion" to "1", "MicroVideoOffset" to "$videoLength",
                "MicroVideoPresentationTimestampUs" to "$timestamp").forEach { (name, value) ->
                setAttributeNS(CAMERA, "GCamera:$name", value)
            }
            if (format == Format.XIAOMI) {
                setAttribute("xmlns:MiCamera", MI_CAMERA)
                setAttributeNS(MI_CAMERA, "MiCamera:XMPMeta", "<?xml version='1.0' encoding='UTF-8' standalone='yes' ?>")
            }
        }
        rdf.appendChild(motion)
        val directory = result.createElementNS(CONTAINER, "Container:Directory")
        val sequence = result.createElementNS(RDF, "rdf:Seq")
        directory.appendChild(sequence); motion.appendChild(directory)
        listOf("image/jpeg" to "Primary", "video/mp4" to "MotionPhoto").forEach { (mime, semantic) ->
            val li = result.createElementNS(RDF, "rdf:li").apply { setAttributeNS(RDF, "rdf:parseType", "Resource") }
            val item = result.createElementNS(CONTAINER, "Container:Item").apply {
                setAttributeNS(ITEM, "Item:Mime", mime); setAttributeNS(ITEM, "Item:Semantic", semantic)
                setAttributeNS(ITEM, "Item:Length", if (semantic == "Primary") "0" else "$videoLength")
                if (semantic == "Primary") setAttributeNS(ITEM, "Item:Padding", "0")
            }
            li.appendChild(item); sequence.appendChild(li)
        }
        return ByteArrayOutputStream().use { output ->
            TransformerFactory.newInstance().newTransformer().apply {
                setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "yes"); setOutputProperty(OutputKeys.ENCODING, "UTF-8")
            }.transform(DOMSource(result), StreamResult(output))
            output.toByteArray()
        }
    }

    private fun removeMotionMetadata(element: Element) {
        for (i in element.attributes.length - 1 downTo 0) {
            val attribute = element.attributes.item(i)
            if (attribute.namespaceURI == CAMERA && attribute.localName in cameraProperties) element.removeAttributeNode(attribute as org.w3c.dom.Attr)
            if (attribute.namespaceURI == MI_CAMERA && attribute.localName == "XMPMeta") element.removeAttributeNode(attribute as org.w3c.dom.Attr)
        }
        for (i in element.childNodes.length - 1 downTo 0) {
            val child = element.childNodes.item(i)
            if (child.nodeType != Node.ELEMENT_NODE) continue
            if ((child.namespaceURI == CAMERA && child.localName in cameraProperties) || child.namespaceURI == CONTAINER ||
                (child.namespaceURI == MI_CAMERA && child.localName == "XMPMeta")) {
                element.removeChild(child)
            } else removeMotionMetadata(child as Element)
        }
    }

    private fun metadata(document: Document, total: Long): Info? {
        val motion = property(document, CAMERA, "MotionPhoto")
        val legacy = property(document, CAMERA, "MicroVideo")
        if (motion != "1" && legacy != "1") return null
        // A modern negative/zero flag explicitly disables motion-photo interpretation.
        if (motion != null && motion != "1") return null
        val length: Long
        val timestamp: Long
        if (motion == "1") {
            require(property(document, CAMERA, "MotionPhotoVersion") == "1") { "实况照片版本不支持" }
            val items = document.getElementsByTagNameNS(CONTAINER, "Item")
            require(items.length == 2) { "实况照片媒体目录不完整或包含额外媒体" }
            val primary = items.item(0) as Element; val video = items.item(1) as Element
            require(itemProperty(primary, "Mime") == "image/jpeg" && itemProperty(primary, "Semantic") == "Primary" &&
                itemProperty(video, "Mime") == "video/mp4" && itemProperty(video, "Semantic") == "MotionPhoto") { "实况照片媒体顺序无效" }
            length = itemProperty(video, "Length")?.toLongOrNull() ?: error("实况照片缺少视频长度")
            timestamp = property(document, CAMERA, "MotionPhotoPresentationTimestampUs")?.toLongOrNull() ?: -1
        } else {
            require(property(document, CAMERA, "MicroVideoVersion") == "1") { "实况照片版本不支持" }
            length = property(document, CAMERA, "MicroVideoOffset")?.toLongOrNull() ?: error("实况照片缺少视频偏移")
            timestamp = property(document, CAMERA, "MicroVideoPresentationTimestampUs")?.toLongOrNull() ?: -1
        }
        require(length in 12 until total && timestamp >= -1) { "实况照片视频长度或时间戳无效" }
        return Info(total - length, length, timestamp)
    }

    private fun property(document: Document, namespace: String, name: String): String? {
        val values = mutableListOf<String>()
        val elements = document.getElementsByTagNameNS(namespace, name)
        for (i in 0 until elements.length) values += elements.item(i).textContent.trim()
        val all = document.getElementsByTagName("*")
        for (i in 0 until all.length) (all.item(i) as Element).let { element ->
            if (element.hasAttributeNS(namespace, name)) values += element.getAttributeNS(namespace, name)
        }
        require(values.distinct().size <= 1) { "实况照片包含冲突元数据" }
        return values.firstOrNull()
    }

    private fun itemProperty(item: Element, name: String): String? {
        if (item.hasAttributeNS(ITEM, name)) return item.getAttributeNS(ITEM, name)
        return item.getElementsByTagNameNS(ITEM, name).let { if (it.length == 1) it.item(0).textContent.trim() else null }
    }

    private fun parseXml(bytes: ByteArray): Document {
        val xml = bytes.toString(Charsets.UTF_8).trimEnd('\u0000')
        require(!xml.contains("<!DOCTYPE", true) && !xml.contains("<!ENTITY", true)) { "图片元数据含有不支持的外部实体" }
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true; isExpandEntityReferences = false; isValidating = false
            runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
            runCatching { setFeature("http://xml.org/sax/features/external-general-entities", false) }
            runCatching { setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
        }
        return factory.newDocumentBuilder().apply { setEntityResolver { _, _ -> InputSource(ByteArrayInputStream(ByteArray(0))) } }
            .parse(ByteArrayInputStream(xml.toByteArray(Charsets.UTF_8)))
    }

    private fun copyRange(input: RandomAccessFile, offset: Long, length: Long, output: OutputStream, checkActive: () -> Unit, progress: (Long) -> Unit) {
        require(length >= 0); input.seek(offset)
        val buffer = ByteArray(64 * 1024); var remaining = length
        while (remaining > 0) {
            checkActive(); val read = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
            require(read > 0) { "图片文件在复制时提前结束" }; output.write(buffer, 0, read); remaining -= read; progress(read.toLong())
        }
    }
    private fun copy(input: InputStream, output: OutputStream, length: Long, checkActive: () -> Unit, progress: (Long) -> Unit) {
        val buffer = ByteArray(64 * 1024); var remaining = length
        while (remaining > 0) {
            checkActive(); val read = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
            require(read > 0) { "动态文件在复制时提前结束" }; output.write(buffer, 0, read); remaining -= read; progress(read.toLong())
        }
        require(input.read() < 0) { "动态文件在复制时发生变化" }
    }
    private fun skipFully(input: InputStream, length: Long) {
        var remaining = length
        while (remaining > 0) {
            val skipped = input.skip(remaining)
            if (skipped > 0) remaining -= skipped else { require(input.read() >= 0) { "JPEG 文件提前结束" }; remaining-- }
        }
    }
    private fun ByteArray.startsWith(prefix: ByteArray): Boolean = size >= prefix.size && prefix.indices.all { this[it] == prefix[it] }
}
