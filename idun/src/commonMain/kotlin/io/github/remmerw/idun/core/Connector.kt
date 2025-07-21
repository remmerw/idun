package io.github.remmerw.idun.core

import io.github.remmerw.asen.Asen
import io.github.remmerw.asen.Peeraddr
import io.github.remmerw.asen.SocketAddress
import io.github.remmerw.asen.core.hostname
import io.github.remmerw.borr.PeerId
import io.github.remmerw.dagr.Listener
import io.github.remmerw.dagr.connectDagr
import io.github.remmerw.idun.CONNECT_TIMEOUT
import io.github.remmerw.idun.RESOLVE_TIMEOUT
import io.github.remmerw.idun.debug
import io.ktor.util.collections.ConcurrentMap
import java.net.InetSocketAddress

internal class Connector() {
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

        // Note: this can be done in the future parallel
        addresses.forEach { address ->
            try {
                val connection = openConnection(this, target, address)
                if(connection != null){
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
            peeraddr.peerId, peeraddr.toSocketAddress()
        )
    }

    suspend fun connect(asen: Asen, peerId: PeerId): Connection {
        var connection = connection(peerId)
        if (connection != null) {
            return connection
        }
        val peeraddr = reachable[peerId]
        if (peeraddr != null) {
            connection = connect(peeraddr)
        }
        if(connection != null) {
            return connection
        }
        return resolveConnection(asen, peerId)

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

    suspend fun shutdown() {
        connections.values.forEach { connection: Connection -> connection.close() }
        connections.clear()
    }


    internal suspend fun openConnection(
        connector: Connector,
        peerId: PeerId,
        address: SocketAddress
    ): Connection? {

        val remoteAddress = InetSocketAddress(
            hostname(address.address),
            address.port.toInt()
        )

        val intern = connectDagr(remoteAddress, CONNECT_TIMEOUT, object : Listener {
            override fun close(connection: io.github.remmerw.dagr.Connection) {
                connections.remove(peerId)
            }
        })

        if(intern != null) {
            val connection = Connection(peerId, connector, intern)
            connections.put(peerId, connection)
            return connection
        }
        return null
    }

}
