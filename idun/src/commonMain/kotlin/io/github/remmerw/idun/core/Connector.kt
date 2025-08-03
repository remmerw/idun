package io.github.remmerw.idun.core

import io.github.remmerw.borr.PeerId
import io.github.remmerw.dagr.Connection
import io.github.remmerw.dagr.Dagr
import io.github.remmerw.idun.Idun
import io.github.remmerw.idun.RESOLVE_TIMEOUT
import io.github.remmerw.idun.TIMEOUT
import io.github.remmerw.idun.debug
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.net.InetSocketAddress
import java.util.concurrent.ConcurrentHashMap

internal class Connector(val dagr: Dagr) {
    private val reachable: MutableMap<PeerId, InetSocketAddress> = ConcurrentHashMap()
    private val mutex = Mutex()

    fun reachable(peerId: PeerId, address: InetSocketAddress) {
        reachable.put(peerId, address)
    }

    private suspend fun resolveConnection(idun: Idun, target: PeerId): Connection {

        val addresses = mutableListOf<InetSocketAddress>()
        addresses.addAll(
            idun.resolveAddresses(
                target, RESOLVE_TIMEOUT.toLong()
            )
        )

        addresses.removeAll(idun.observable()) // do not call yourself

        addresses.forEach { address ->
            try {
                val connection = openConnection(address)
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


    suspend fun connect(idun: Idun, peerId: PeerId): Connection {
        mutex.withLock {
            var connection: Connection? = null
            val address = reachable[peerId]
            if (address != null) {
                connection = openConnection(address)
            }
            if (connection != null && connection.isConnected) {
                return connection
            }
            return resolveConnection(idun, peerId)
        }
    }


    internal fun openConnection(
        remoteAddress: InetSocketAddress
    ): Connection? {
        try {
            return dagr.connect(remoteAddress, TIMEOUT)
        } catch (throwable: Throwable) {
            debug(throwable)
        }
        return null
    }
}
