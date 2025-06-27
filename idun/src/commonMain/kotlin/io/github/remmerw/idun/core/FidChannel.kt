package io.github.remmerw.idun.core

import io.github.remmerw.idun.Channel
import io.github.remmerw.idun.Fetch
import io.github.remmerw.idun.splitterSize
import kotlinx.io.Buffer
import kotlinx.io.RawSource
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


    override suspend fun next(): RawSource? {
        val offset = this.left
        this.left = -1

        if (offset >= 0) {
            // special case when offset is set
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
        return fetch.fetchBlock(link)
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
        do {
            val data = next()
            data?.buffered()?.use { source ->
                source.transferTo(sink)
            }
        } while (data != null)
    }
}
