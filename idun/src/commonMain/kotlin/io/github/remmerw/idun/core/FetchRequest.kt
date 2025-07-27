package io.github.remmerw.idun.core

import io.github.remmerw.asen.Asen
import io.github.remmerw.borr.PeerId
import io.github.remmerw.idun.Fetch
import kotlinx.io.Buffer


internal data class FetchRequest(
    val asen: Asen,
    val connection: Connection,
    val peerId: PeerId
) : Fetch {

    override fun fetchBlock(sink: Buffer, cid: Long): Int {
        connection.request(cid, sink)
        return sink.size.toInt()
    }
}

