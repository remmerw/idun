package io.github.remmerw.idun.core

import io.github.remmerw.borr.PeerId
import io.github.remmerw.idun.HALO_ROOT
import io.github.remmerw.idun.debug
import kotlinx.io.Buffer
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

internal class Connection(
    private val peerId: PeerId,
    private val connector: Connector,
    private val intern: io.github.remmerw.dagr.Connection
) {
    private val lock = ReentrantLock()

    fun request(cid: Long): Buffer {
        val cidRequest = (cid == HALO_ROOT)

        lock.withLock {
            try {
                intern.writeLong(cid)

                val length = intern.readInt() // read cid
                check(length != EOF) { "EOF" }

                if (cidRequest) {
                    val root = intern.readLong()
                    check(root != EOF.toLong()) { "EOF" }
                    val payload = Buffer()
                    payload.writeLong(root)
                    return payload
                } else {
                    return intern.readBuffer(length)
                }
            } catch (throwable: Throwable) {
                close()
                throw throwable
            }
        }
    }

    fun close() {
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
