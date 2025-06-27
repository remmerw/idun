package io.github.remmerw.idun.core


import io.github.remmerw.idun.Fetch
import io.github.remmerw.idun.MAX_CHARS_SIZE
import io.github.remmerw.idun.Node
import io.github.remmerw.idun.Storage
import io.github.remmerw.idun.splitterSize
import kotlinx.io.Buffer
import kotlinx.io.RawSource
import kotlinx.io.Source
import kotlinx.io.buffered
import kotlinx.io.readByteArray


const val OCTET_MIME_TYPE = "application/octet-stream"
const val UNDEFINED_NAME: String = ""
internal const val EOF = -1

internal fun encodeType(type: Type): Byte {
    return when (type) {
        Type.RAW -> 0x00.toByte()
        Type.FID -> 0x01.toByte()
    }
}


internal fun decodeType(value: Byte): Type {
    if (value.toInt() == 0x00) {
        return Type.RAW
    }
    if (value.toInt() == 0x01) {
        return Type.FID
    }
    throw IllegalStateException()
}

internal fun readUnsignedVariant(buffer: Source): Int {
    var result = 0
    var cur: Int
    var count = 0
    do {
        cur = buffer.readByte().toInt() and 0xff
        result = result or ((cur and 0x7f) shl (count * 7))
        count++
    } while (((cur and 0x80) == 0x80) && count < 5)
    check((cur and 0x80) != 0x80) { "invalid unsigned variant sequence" }
    return result
}


internal fun readLongUnsignedVariant(buffer: Source): Long {
    var result: Long = 0
    var cur: Long
    var count = 0
    do {
        cur = (buffer.readByte().toInt() and 0xff).toLong()
        result = result or ((cur and 0x7fL) shl (count * 7))
        count++
    } while (((cur and 0x80L) == 0x80L) && count < 5)
    check((cur and 0x80L) != 0x80L) { "invalid unsigned variant sequence" }
    return result
}


internal fun unsignedVariantSize(value: Long): Int {
    var remaining = value shr 7
    var count = 0
    while (remaining != 0L) {
        remaining = remaining shr 7
        count++
    }
    return count + 1
}


internal fun writeUnsignedVariant(buffer: Buffer, value: Long) {
    var x = value
    var remaining = x ushr 7
    while (remaining != 0L) {
        buffer.writeByte(((x and 0x7fL) or 0x80L).toByte())
        x = remaining
        remaining = remaining ushr 7
    }
    buffer.writeByte((x and 0x7fL).toByte())
}


internal fun storeSource(
    source: Source, storage: Storage,
    name: String, mimeType: String,
    nextCid: (Any) -> Long
): Fid {
    return splitter(source, storage, name, mimeType, nextCid)
}


private fun splitter(
    source: Source, storage: Storage,
    name: String, mimeType: String,
    nextCid: (Any) -> Long
): Fid {
    val links: MutableList<Long> = mutableListOf()

    val split = splitterSize()
    val chunk = Buffer()

    var size = 0L
    var done = false

    while (!done) {
        var read = 0L
        while (read < split) {
            val iterRead = source.readAtMostTo(chunk, split.toLong())
            if (iterRead <= 0) {
                done = true
                break
            }
            read += iterRead
        }

        if (read > 0) {
            val cid = nextCid.invoke(Any())
            storage.storeBlock(cid, chunk)
            links.add(cid)
            size += read
        }
        chunk.clear()
    }

    val buffer = encodeFid(links, size, name, mimeType)
    val cid = nextCid.invoke(Any())
    storage.storeBlock(cid, buffer)

    return Fid(cid, size, name, mimeType, links)
}

internal fun removeNode(storage: Storage, node: Node) {
    when (node) {
        is Fid -> {
            removeBlocks(storage, node)
        }

        is Raw -> {
            removeBlocks(storage, node)
        }

        else -> {
            throw IllegalStateException("not supported node")
        }
    }
}

private fun removeBlocks(storage: Storage, raw: Raw) {
    storage.deleteBlock(raw.cid())
}

private fun removeBlocks(storage: Storage, fid: Fid) {
    for (link in fid.links()) {
        storage.deleteBlock(link)
    }
    storage.deleteBlock(fid.cid())
}


internal fun createRaw(
    storage: Storage, data: ByteArray,
    nextCid: (Any) -> Long
): Raw {
    val buffer = encodeRaw(data)
    val cid = nextCid.invoke(Any())
    storage.storeBlock(cid, buffer)
    return Raw(cid, data)
}


internal fun encodeRaw(data: ByteArray): Buffer {
    val buffer = Buffer()
    buffer.writeByte(encodeType(Type.RAW))
    writeUnsignedVariant(buffer, data.size.toLong())
    buffer.write(data)
    return buffer
}


internal fun encodeFid(
    links: List<Long>, size: Long, name: String, mimeType: String
): Buffer {
    require(size >= 0) { "Invalid size" }
    require(
        name.length <= MAX_CHARS_SIZE
    ) { "Max name length is $MAX_CHARS_SIZE" }
    require(
        mimeType.length <= MAX_CHARS_SIZE
    ) { "Max mime type length is $MAX_CHARS_SIZE" }

    val rawName = name.encodeToByteArray()
    val rawMimeType = mimeType.encodeToByteArray()

    val length = lengthFid(links, size, rawName, rawMimeType)

    val buffer = Buffer()
    buffer.writeByte(encodeType(Type.FID))
    writeUnsignedVariant(buffer, links.size.toLong())

    for (link in links) {
        buffer.writeLong(link)
    }

    writeUnsignedVariant(buffer, size)

    writeUnsignedVariant(buffer, rawName.size.toLong())
    buffer.write(rawName)

    writeUnsignedVariant(buffer, rawMimeType.size.toLong())
    buffer.write(rawMimeType)

    require(buffer.size.toInt() == length)
    return buffer
}

private fun lengthFid(
    links: List<Long>, size: Long,
    name: ByteArray, mimeType: ByteArray
): Int {
    var length = 1 +
            unsignedVariantSize(links.size.toLong()) +  // links size
            unsignedVariantSize(size) // size


    length = (length +
            unsignedVariantSize(name.size.toLong()) // name size
            + name.size)

    length = (length +
            unsignedVariantSize(mimeType.size.toLong()) // name size
            + mimeType.size)

    length += links.size * Long.SIZE_BYTES
    return length
}


internal suspend fun fetchData(node: Node, fetch: Fetch): ByteArray {
    val size = node.size()
    if (node is Fid) {
        return FidChannel(node, size, fetch).readAllBytes()
    }
    val raw = node as Raw
    return raw.data()
}

internal fun decodeNode(cid: Long, source: RawSource): Node {
    source.buffered().use { buffer ->
        val type: Type = decodeType(buffer.readByte())

        if (type == Type.RAW) {
            val dataLength = readUnsignedVariant(buffer)
            val content = buffer.readByteArray(dataLength)
            require(buffer.exhausted()) { "still data available" }
            return Raw(cid, content)
        } else {

            val linksSize = readUnsignedVariant(buffer)
            val links = mutableListOf<Long>()

            repeat(linksSize) {
                links.add(buffer.readLong())
            }


            val size = readLongUnsignedVariant(buffer)

            var name = UNDEFINED_NAME
            var mimeType = UNDEFINED_NAME

            if (type == Type.FID) {
                val nameLength = readUnsignedVariant(buffer)
                name = buffer.readByteArray(nameLength).decodeToString()
            }

            if (type == Type.FID) {
                val mimeTypeLength = readUnsignedVariant(buffer)
                mimeType = buffer.readByteArray(mimeTypeLength).decodeToString()
            }

            return Fid(cid, size, name, mimeType, links)
        }
    }
}

