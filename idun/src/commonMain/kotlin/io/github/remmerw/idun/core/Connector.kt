package io.github.remmerw.idun.core

import io.github.remmerw.asen.Asen
import io.github.remmerw.asen.Peeraddr
import io.github.remmerw.asen.SocketAddress
import io.github.remmerw.asen.createInetSocketAddress
import io.github.remmerw.borr.PeerId
import io.github.remmerw.idun.RESOLVE_TIMEOUT
import io.github.remmerw.idun.debug
import io.ktor.network.selector.SelectorManager
import io.ktor.network.sockets.aSocket
import io.ktor.util.collections.ConcurrentMap

internal class Connector(private val selectorManager: SelectorManager) {
    private val connections: ConcurrentMap<PeerId, Connection> = ConcurrentMap()
    private val reachable: ConcurrentMap<PeerId, Peeraddr> = ConcurrentMap()

    fun reachable(peeraddr: Peeraddr) {
        reachable.put(peeraddr.peerId, peeraddr)
    }

    private fun resolve(target: PeerId): Connection? {
        return connection(target)
    }

    private suspend fun resolveConnection(asen: Asen, target: PeerId): Connection {
        val connection = resolve(target)
        if (connection != null) {
            return connection
        }

        val addresses = asen.resolveAddresses(target, RESOLVE_TIMEOUT.toLong())
        addresses.forEach { address ->
            try {
                return openConnection(selectorManager, this, target, address)
            } catch (throwable: Throwable) {
                debug(throwable)
            }
        }

        throw Exception("No hop connection established")
    }

    private suspend fun connect(peeraddr: Peeraddr): Connection {
        val connection = connection(peeraddr.peerId)
        if (connection != null) {
            return connection
        }
        return openConnection(
            selectorManager, this,
            peeraddr.peerId, peeraddr.toSocketAddress()
        )
    }

    suspend fun connect(asen: Asen, peerId: PeerId): Connection {
        val connection = connection(peerId)
        if (connection != null) {
            return connection
        }
        val peeraddr = reachable[peerId]
        return if (peeraddr != null) {
            connect(peeraddr)
        } else {
            resolveConnection(asen, peerId)
        }
    }

    fun numConnections(): Int {
        return connections.size
    }

    fun connection(peerId: PeerId): Connection? {
        val connection = connections[peerId]
        if (connection != null) {
            if (!connection.isConnected) {
                connections.remove(peerId)
                return null
            }
        }

        return connection
    }


    fun removeChannel(connection: Connection) {
        connections.remove(connection.remotePeerId())
    }

    fun shutdown() {
        connections.values.forEach { connection: Connection -> connection.close() }
        connections.clear()
    }


    internal suspend fun openConnection(
        selectorManager: SelectorManager,
        connector: Connector,
        peerId: PeerId,
        address: SocketAddress
    ): Connection {

        val socketAddress = createInetSocketAddress(
            address.address,
            address.port.toInt()
        )
        val socket = aSocket(selectorManager).tcp().connect(socketAddress)
        checkNotNull(socket)

        val connection = Connection(peerId, connector, socket)
        connections.put(peerId, connection)
        return connection
    }

}
