package io.github.remmerw.idun.core

import io.github.remmerw.asen.Asen
import io.github.remmerw.asen.Peeraddr
import io.github.remmerw.borr.PeerId
import io.github.remmerw.dagr.Dagr
import io.github.remmerw.idun.CONNECT_TIMEOUT
import io.github.remmerw.idun.RESOLVE_TIMEOUT
import io.github.remmerw.idun.debug
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.net.InetSocketAddress
import java.util.concurrent.ConcurrentHashMap

internal class Connector(val dagr: Dagr) {
    private val connections: MutableMap<PeerId, Connection> = ConcurrentHashMap()
    private val reachable: MutableMap<PeerId, Peeraddr> = ConcurrentHashMap()
    private val mutex = Mutex()

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

        // Note: this can be done in the future parallel
        addresses.forEach { address ->
            try {
                val connection = openConnection(this, target, address)
                if (connection != null) {
                    return connection
                }
            } catch (throwable: Throwable) {
                debug(throwable)
            }
        }
        throw Exception("No hop connection established")
    }

    private suspend fun connect(peeraddr: Peeraddr): Connection? {
        val connection = connection(peeraddr.peerId)
        if (connection != null && connection.isConnected) {
            return connection
        }
        return openConnection(
            this,
            peeraddr.peerId, peeraddr.toInetSocketAddress()
        )
    }

    suspend fun connect(asen: Asen, peerId: PeerId): Connection {
        mutex.withLock {
            var connection = connection(peerId)
            if (connection != null && connection.isConnected) {
                return connection
            }
            val peeraddr = reachable[peerId]
            if (peeraddr != null) {
                connection = connect(peeraddr)
            }
            if (connection != null && connection.isConnected) {
                return connection
            }
            return resolveConnection(asen, peerId)
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


    fun remove(connection: Connection) {
        connections.remove(connection.remotePeerId())
    }

    suspend fun shutdown() {
        connections.values.forEach { connection: Connection -> connection.close() }
        connections.clear()
    }


    internal suspend fun openConnection(
        connector: Connector,
        peerId: PeerId,
        remoteAddress: InetSocketAddress
    ): Connection? {
        try {
            val intern = dagr.connect(remoteAddress, CONNECT_TIMEOUT)

            if (intern != null) {
                val connection = Connection(peerId, connector, intern)
                intern.enableKeepAlive()
                connections.put(peerId, connection)
                return connection
            }
        } catch (throwable: Throwable) {
            debug(throwable)
        }
        return null
    }
}
