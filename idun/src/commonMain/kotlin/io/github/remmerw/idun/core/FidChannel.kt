package io.github.remmerw.idun.core

import io.github.remmerw.idun.Channel
import io.github.remmerw.idun.Fetch
import io.github.remmerw.idun.splitterSize
import kotlinx.io.Buffer
import kotlinx.io.Sink
import kotlinx.io.buffered
import kotlinx.io.readByteArray


internal class FidChannel(
    private val root: Fid,
    private val size: Long,
    private val fetch: Fetch,
) : Channel {
    private var index = -1
    private var left = -1

    override fun size(): Long {
        return size
    }

    // todo
    override suspend fun next(): Buffer? {
        val offset = this.left
        this.left = -1

        if (offset >= 0) {
            val link = root.links()[index]
            fetch.fetchBlock(link).buffered().use { source ->
                source.skip(offset.toLong())
                val data = Buffer()
                data.write(source.readByteArray())
                return data
            }
        }

        index++

        val links = root.links()
        if (index >= links.size) {
            return null
        }
        val link = links[index]
        // todo
        fetch.fetchBlock(link).buffered().use { source ->
            val data = Buffer()
            data.write(source.readByteArray())
            return data
        }
    }


    override fun seek(offset: Long) {
        require(offset >= 0) { "invalid offset" }

        if (offset == 0L) {
            this.left = 0
            this.index = 0
            return
        }

        if (root.links().isNotEmpty()) {
            // Internal nodes have no data, so just iterate through the
            // sizes of its children (advancing the child index of the
            // `dagWalker`) to find where we need to go down to next in
            // the search

            val div = offset.toInt().floorDiv(splitterSize())

            require(div < Int.MAX_VALUE) { "Invalid number of links" }

            this.index = div

            this.left = offset.toInt().mod(splitterSize())

        } else {
            this.left = offset.toInt()
        }
    }


    override suspend fun readAllBytes(): ByteArray {

        val all = Buffer()

        transferTo(all) { }

        return all.readByteArray()
    }


    override suspend fun transferTo(sink: Sink, read: (Int) -> Unit) {
        var write: Int
        var data: Buffer?
        do {
            data = next()
            if (data != null) {
                write = data.size.toInt()
                sink.write(data, data.size)
                if (write > 0) {
                    read.invoke(write)
                }
                require(data.exhausted()) { "Still data available" }
            }
        } while (data != null)
    }
}
