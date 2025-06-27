package io.github.remmerw.idun.core

import io.github.remmerw.asen.Asen
import io.github.remmerw.asen.PeerId
import io.github.remmerw.idun.Fetch
import kotlinx.io.RawSource


internal data class FetchRequest(
    val asen: Asen,
    val connector: Connector,
    val peerId: PeerId
) : Fetch {

    override suspend fun fetchBlock(cid: Long): RawSource {
        val connection = connector.connect(asen, peerId)
        return request(connection, cid)
    }


    private suspend fun request(connection: Connection, cid: Long): RawSource {
        return connection.request(cid)
    }
}

