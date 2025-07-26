package io.github.remmerw.idun.core

import io.github.remmerw.idun.Channel
import kotlinx.io.Buffer

internal class RawChannel(private val data: ByteArray) : Channel {

    private var hasRead = false
    override fun size(): Long {
        return data.size.toLong()
    }

    override fun seek(offset: Long) {
        throw Exception("Seek is not supported")
    }

    override fun next(buffer: Buffer): Int {
        if (!hasRead) {
            buffer.write(data)
            hasRead = true
            return data.size
        } else {
            return -1
        }
    }

    override fun readBytes(): ByteArray {
        return data
    }
}