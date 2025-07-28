package io.github.remmerw.idun.core

import io.github.remmerw.idun.Channel
import kotlinx.io.Buffer
import kotlinx.io.readByteArray

internal class RawChannel(data: ByteArray) : Channel {
    private val buffer = Buffer()

    init {
        buffer.write(data)
    }

    override fun size(): Long {
        return buffer.size
    }

    override fun seek(offset: Long) {
        buffer.skip(offset)
    }

    override fun next(sink: Buffer): Int {
        return if (!buffer.exhausted()) {
            buffer.transferTo(sink).toInt()
        } else {
            -1
        }
    }

    override fun readBytes(): ByteArray {
        return buffer.readByteArray()
    }
}