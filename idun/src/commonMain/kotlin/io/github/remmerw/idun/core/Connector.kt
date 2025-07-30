package io.github.remmerw.idun.core

import io.github.remmerw.asen.Asen
import io.github.remmerw.borr.PeerId
import io.github.remmerw.dagr.Dagr
import io.github.remmerw.idun.RESOLVE_TIMEOUT
import io.github.remmerw.idun.TIMEOUT
import io.github.remmerw.idun.debug
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.net.InetSocketAddress
import java.util.concurrent.ConcurrentHashMap

internal class Connector(val dagr: Dagr) {
    private val connections: MutableMap<PeerId, Connection> = ConcurrentHashMap()
    private val reachable: MutableMap<PeerId, InetSocketAddress> = ConcurrentHashMap()
    private val mutex = Mutex()

    fun reachable(peerId: PeerId, address: InetSocketAddress) {
        reachable.put(peerId, address)
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
                    reachable.put(target, address)
                    return connection
                }
            } catch (throwable: Throwable) {
                debug(throwable)
            }
        }
        throw Exception("No hop connection established")
    }

    private fun connect(peerId: PeerId, address: InetSocketAddress): Connection? {
        val connection = connection(peerId)
        if (connection != null && connection.isConnected) {
            return connection
        }
        return openConnection(
            this, peerId, address
        )
    }

    suspend fun connect(asen: Asen, peerId: PeerId): Connection {
        mutex.withLock {
            var connection = connection(peerId)
            if (connection != null && connection.isConnected) {
                return connection
            }
            val address = reachable[peerId]
            if (address != null) {
                connection = connect(peerId, address)
            }
            if (connection != null && connection.isConnected) {
                return connection
            }
            return resolveConnection(asen, peerId)
        }
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

    fun shutdown() {
        connections.values.forEach { connection: Connection -> connection.close() }
        connections.clear()
    }


    internal fun openConnection(
        connector: Connector,
        peerId: PeerId,
        remoteAddress: InetSocketAddress
    ): Connection? {
        try {
            val intern = dagr.connect(remoteAddress, TIMEOUT)

            if (intern != null) {
                val connection = Connection(peerId, connector, intern)
                connections.put(peerId, connection)
                return connection
            }
        } catch (throwable: Throwable) {
            debug(throwable)
        }
        return null
    }
}
