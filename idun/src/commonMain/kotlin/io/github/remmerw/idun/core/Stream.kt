package io.github.remmerw.idun.core

import io.github.remmerw.idun.Channel
import kotlinx.coroutines.runBlocking
import kotlinx.io.Buffer
import java.io.IOException
import java.io.InputStream
import kotlin.math.min

class Stream(private val channel: Channel) : InputStream() {
    private var buffer: Buffer = Buffer()

    override fun available(): Int {
        return channel.size().toInt()
    }

    private fun loadNextData(): Unit = runBlocking {
        try {
            channel.next(buffer)
        } catch (throwable: Throwable) {
            throw IOException(throwable)
        }
    }

    private fun hasData(): Boolean {
        if (buffer.exhausted()) {
            loadNextData()
        }
        return !buffer.exhausted()
    }

    override fun read(bytes: ByteArray, off: Int, len: Int): Int {
        if (!hasData()) return -1
        val endIndex = min(off + len, buffer.size.toInt())
        return buffer.readAtMostTo(bytes, off, endIndex)
    }

    override fun read(bytes: ByteArray): Int {
        if (!hasData()) return -1
        return read(bytes, 0, bytes.size)
    }


    override fun read(): Int {
        if (!hasData()) return -1
        return buffer.readByte().toInt() and 0xFF
    }


    override fun skip(n: Long): Long {
        channel.seek(n)
        loadNextData()
        return n
    }


    override fun close() {
        buffer.close()
    }
}