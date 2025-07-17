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
import io.ktor.util.collections.ConcurrentSet

internal class Connector(private val selectorManager: SelectorManager) {
    private val connections: MutableSet<Connection> = ConcurrentSet()
    private val reachable: ConcurrentMap<PeerId, Peeraddr> = ConcurrentMap()

    fun reachable(peeraddr: Peeraddr) {
        reachable.put(peeraddr.peerId, peeraddr)
    }

    private fun resolve(target: PeerId): Connection? {
        val pnsChannels = connections(target)
        if (pnsChannels.isNotEmpty()) {
            return pnsChannels.iterator().next()
        }
        return null
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
        val pnsChannels = connections(peeraddr.peerId)
        if (pnsChannels.isNotEmpty()) {
            return pnsChannels.iterator().next()
        }
        return openConnection(
            selectorManager, this,
            peeraddr.peerId, peeraddr.toSocketAddress()
        )
    }

    suspend fun connect(asen: Asen, peerId: PeerId): Connection {
        val pnsChannels = connections(peerId)
        if (pnsChannels.isNotEmpty()) {
            return pnsChannels.iterator().next()
        }
        val peeraddr = reachable[peerId]
        return if (peeraddr != null) {
            connect(peeraddr)
        } else {
            resolveConnection(asen, peerId)
        }
    }


    fun connections(peerId: PeerId): Set<Connection> {
        return connections().filter { connection -> connection.remotePeerId() == peerId }.toSet()
    }

    fun connections(): Set<Connection> {

        val result: MutableSet<Connection> = mutableSetOf()
        val delete: MutableSet<Connection> = mutableSetOf()
        for (connection in connections) {
            if (connection.isConnected) {
                result.add(connection)
            } else {
                delete.add(connection)
            }
        }
        delete.forEach { connection -> removeChannel(connection) }
        return result

    }

    fun registerChannel(connection: Connection) {
        connections.add(connection)
    }

    fun removeChannel(connection: Connection) {
        connections.remove(connection)
    }

    fun shutdown() {
        connections().forEach { connection: Connection -> connection.close() }
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
        connector.registerChannel(connection)
        return connection
    }

}
