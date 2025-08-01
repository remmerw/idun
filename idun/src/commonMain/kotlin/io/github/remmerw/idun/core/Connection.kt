package io.github.remmerw.idun.core

import io.github.remmerw.borr.PeerId
import io.github.remmerw.idun.Fetch
import io.github.remmerw.idun.TIMEOUT
import io.github.remmerw.idun.debug
import kotlinx.io.RawSink

internal class Connection(
    private val peerId: PeerId,
    private val connector: Connector,
    private val intern: io.github.remmerw.dagr.Connection
) : Fetch, AutoCloseable {


    override fun fetchBlock(sink: RawSink, cid: Long): Int {
        try {
            return intern.request(cid, sink, TIMEOUT)
        } catch (throwable: Throwable) {
            debug(throwable)
            close()
            throw throwable
        }
    }

    override fun close() {
        try {
            intern.close()
        } catch (throwable: Throwable) {
            debug(throwable)
        } finally {
            connector.remove(this)
        }
    }

    val isConnected: Boolean
        get() = intern.isConnected

    fun remotePeerId(): PeerId {
        return peerId
    }
}
