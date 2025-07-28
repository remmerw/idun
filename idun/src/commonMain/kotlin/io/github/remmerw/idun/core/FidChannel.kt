package io.github.remmerw.idun.core

import io.github.remmerw.idun.Channel
import io.github.remmerw.idun.Fetch
import io.github.remmerw.idun.splitterSize
import kotlinx.io.Buffer
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


    override fun next(sink: Buffer): Int {
        val offset = this.left
        this.left = -1

        if (offset >= 0) {
            // special case when offset is set
            val link = root.links()[index]
            fetch.fetchBlock(sink, link)
            sink.skip(offset.toLong())
            return sink.size.toInt()
        }

        index++

        val links = root.links()
        if (index >= links.size) {
            return -1
        }
        val link = links[index]
        fetch.fetchBlock(sink, link)
        return sink.size.toInt()
    }

    override fun readBytes(): ByteArray {
        val buffer = Buffer()
        do {
            val read = next(buffer)
        } while (read > 0)

        return buffer.readByteArray()
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

            val div = offset.floorDiv(splitterSize())

            require(div < Int.MAX_VALUE) { "Invalid number of links" }

            this.index = div.toInt()

            this.left = offset.mod(splitterSize())

            require(left + (index * splitterSize().toLong()) == offset) {
                "Wrong calculation of offset"
            }

        } else {
            this.left = offset.toInt()
        }
    }
}
