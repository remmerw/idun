package io.github.remmerw.idun.core

import io.github.remmerw.asen.Asen
import io.github.remmerw.idun.Fetch
import io.github.remmerw.idun.Storage
import kotlinx.io.Buffer


internal data class FetchRequest(
    val asen: Asen,
    val connector: Connector,
    val request: String,
    val storage: Storage?
) : Fetch {

    override suspend fun fetchBlock(cid: Long): Buffer {
        if (storage != null) {
            if (storage.hasBlock(cid)) {
                return storage.getBlock(cid)
            }
        }
        val pnsChannel = connector.connect(asen, request)
        return request(pnsChannel, cid)
    }


    private suspend fun request(channel: Channel, cid: Long): Buffer {
        val payload = channel.request(cid)
        if (storage != null) {
            if (!storage.hasBlock(cid)) {
                storage.storeBlock(cid, payload.copy())
            }
        }
        return payload
    }
}

