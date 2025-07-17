package io.github.remmerw.idun.core

import io.github.remmerw.asen.Asen
import io.github.remmerw.borr.PeerId
import io.github.remmerw.idun.Fetch
import kotlinx.io.RawSink


internal data class FetchRequest(
    val asen: Asen,
    val connector: Connector,
    val peerId: PeerId
) : Fetch {

    override suspend fun fetchBlock(rawSink: RawSink, cid: Long, offset: Int): Int {
        val connection = connector.connect(asen, peerId)
        connection.request(cid).use { source ->
            source.skip(offset.toLong())
            return source.transferTo(rawSink).toInt()
        }
    }
}

