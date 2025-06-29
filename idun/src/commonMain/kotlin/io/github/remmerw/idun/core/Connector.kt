package io.github.remmerw.idun.core

import io.github.remmerw.asen.Asen
import io.github.remmerw.asen.PeerId
import io.github.remmerw.asen.Peeraddr
import io.github.remmerw.idun.RESOLVE_TIMEOUT
import io.github.remmerw.idun.debug
import io.ktor.network.selector.SelectorManager
import io.ktor.network.sockets.InetSocketAddress
import io.ktor.network.sockets.aSocket
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal class Connector(private val selectorManager: SelectorManager) {
    private val connections: MutableSet<Connection> = mutableSetOf()
    private val mutex = Mutex()

    private suspend fun resolve(target: PeerId): Connection? {
        val pnsChannels = connections(target)
        if (pnsChannels.isNotEmpty()) {
            return pnsChannels.iterator().next()
        }
        return null
    }

    private suspend fun resolveAddress(asen: Asen, target: PeerId): Connection {
        val connection = resolve(target)
        if (connection != null) {
            return connection
        }

        val peeraddrs = asen.findPeer(target, RESOLVE_TIMEOUT.toLong())
        peeraddrs.forEach { peeraddr ->
            try {
                return openConnection(selectorManager, this, peeraddr)
            } catch (throwable: Throwable) {
                debug(throwable)
            }
        }

        throw Exception("No hop connection established")
    }

    suspend fun connect(peeraddr: Peeraddr): Connection {
        val pnsChannels = connections(peeraddr)
        if (pnsChannels.isNotEmpty()) {
            return pnsChannels.iterator().next()
        }
        return openConnection(selectorManager, this, peeraddr)
    }

    suspend fun connect(asen: Asen, peerId: PeerId): Connection {
        val pnsChannels = connections(peerId)
        if (pnsChannels.isNotEmpty()) {
            return pnsChannels.iterator().next()
        }
        return resolveAddress(asen, peerId)
    }

    suspend fun connections(peeraddr: Peeraddr): Set<Connection> {
        return connections().filter { connection -> connection.remotePeeraddr == peeraddr }.toSet()
    }

    suspend fun connections(peerId: PeerId): Set<Connection> {
        return connections().filter { connection -> connection.remotePeerId() == peerId }.toSet()
    }

    suspend fun connections(): Set<Connection> {
        mutex.withLock {
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
    }

    suspend fun registerChannel(connection: Connection) {
        mutex.withLock {
            connections.add(connection)
        }
    }

    suspend fun removeChannel(connection: Connection) {
        mutex.withLock {
            connections.remove(connection)
        }
    }

    suspend fun shutdown() {
        connections().forEach { connection: Connection -> connection.close() }
        connections.clear()
    }


    internal suspend fun openConnection(
        selectorManager: SelectorManager,
        connector: Connector,
        peeraddr: Peeraddr
    ): Connection {

        val socket = aSocket(selectorManager).tcp().connect(
            InetSocketAddress(peeraddr.address(), peeraddr.port.toInt())
        )
        checkNotNull(socket)

        val connection = Connection(peeraddr, connector, socket)
        connector.registerChannel(connection)
        return connection
    }

}
