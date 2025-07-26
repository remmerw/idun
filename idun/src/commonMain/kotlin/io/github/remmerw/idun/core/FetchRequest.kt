package io.github.remmerw.idun.core

import io.github.remmerw.asen.Asen
import io.github.remmerw.borr.PeerId
import io.github.remmerw.idun.Fetch
import kotlinx.io.RawSink


internal data class FetchRequest(
    val asen: Asen,
    val connection: Connection,
    val peerId: PeerId
) : Fetch {

    override fun fetchBlock(rawSink: RawSink, cid: Long, offset: Int): Int {

        connection.request(cid).use { source ->
            source.skip(offset.toLong())
            return source.transferTo(rawSink).toInt()
        }
    }
}

