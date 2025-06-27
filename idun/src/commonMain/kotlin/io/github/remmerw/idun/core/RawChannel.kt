package io.github.remmerw.idun.core

import io.github.remmerw.idun.Channel
import kotlinx.io.Buffer
import kotlinx.io.Sink


internal class RawChannel(private val data: ByteArray) : Channel {

    override fun size(): Long {
        return data.size.toLong()
    }

    override fun seek(offset: Long) {
        throw Exception("Seek is not supported")
    }

    override suspend fun transferTo(sink: Sink, read: (Int) -> Unit) {
        val size = data.size
        sink.write(data)
        read.invoke(size)
    }

    override suspend fun readAllBytes(): ByteArray {
        return data
    }

    override suspend fun next(): Buffer? {
        return null
    }
}