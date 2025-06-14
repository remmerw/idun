package io.github.remmerw.idun.core

import io.github.remmerw.asen.PeerId
import io.github.remmerw.asen.Peeraddr
import io.github.remmerw.idun.HALO_ROOT
import io.github.remmerw.idun.debug
import io.ktor.network.sockets.Socket
import io.ktor.network.sockets.isClosed
import io.ktor.network.sockets.openReadChannel
import io.ktor.network.sockets.openWriteChannel
import io.ktor.utils.io.InternalAPI
import io.ktor.utils.io.readBuffer
import io.ktor.utils.io.readInt
import io.ktor.utils.io.readLong
import io.ktor.utils.io.writeLong
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.io.Buffer

internal class Channel(
    internal val remotePeeraddr: Peeraddr,
    private val connector: Connector,
    private val socket: Socket
) {
    private val mutex = Mutex()
    private val receiveChannel = socket.openReadChannel()
    private val sendChannel = socket.openWriteChannel(autoFlush = true)

    @OptIn(InternalAPI::class)
    suspend fun request(cid: Long): Buffer {
        val cidRequest = (cid == HALO_ROOT)

        mutex.withLock {
            try {
                sendChannel.writeLong(cid)

                val length = receiveChannel.readInt() // read cid
                check(length != EOF) { "EOF" }

                if (cidRequest) {
                    val root = receiveChannel.readLong()
                    check(root != EOF.toLong()) { "EOF" }
                    val payload = Buffer()
                    payload.writeLong(root)
                    return payload
                } else {
                    return receiveChannel.readBuffer(length)
                }
            } catch (throwable: Throwable) {
                close()
                throw throwable
            }
        }
    }

    fun close() {
        try {
            socket.close()
        } catch (throwable: Throwable) {
            debug(throwable)
        } finally {
            connector.removeChannel(this)
        }
    }


    val isConnected: Boolean
        get() = !socket.isClosed

    fun remotePeerId(): PeerId {
        return remotePeeraddr.peerId
    }
}
