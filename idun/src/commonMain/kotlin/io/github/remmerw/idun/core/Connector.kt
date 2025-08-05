package io.github.remmerw.idun.core

import io.github.remmerw.borr.PeerId
import io.github.remmerw.dagr.ClientConnection
import io.github.remmerw.dagr.connectDagr
import io.github.remmerw.idun.Idun
import io.github.remmerw.idun.RESOLVE_TIMEOUT
import io.github.remmerw.idun.TIMEOUT
import io.github.remmerw.idun.debug
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress
import java.util.concurrent.ConcurrentHashMap
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi

internal class Connector() {
    private val reachable: MutableMap<PeerId, InetSocketAddress> = ConcurrentHashMap()

    fun reachable(peerId: PeerId, address: InetSocketAddress) {
        reachable.put(peerId, address)
    }

    @OptIn(ExperimentalAtomicApi::class)
    private suspend fun resolveConnection(idun: Idun, target: PeerId): ClientConnection {

        val addresses = mutableListOf<InetSocketAddress>()
        addresses.addAll(
            idun.resolveAddresses(
                target, RESOLVE_TIMEOUT.toLong()
            )
        )

        val done: AtomicReference<ClientConnection?> = AtomicReference(null)
        addresses.removeAll(idun.observable()) // do not call yourself


        withContext(Dispatchers.IO) {
            addresses.forEach { address ->
                launch {
                    try {
                        val connection = connectDagr(address, TIMEOUT)
                        if (connection != null) {
                            reachable.put(target, address)
                            // done
                            done.store(connection)
                            coroutineContext.cancelChildren()
                        }
                    } catch (throwable: Throwable) {
                        debug(throwable)
                    }
                }
            }
        }


        return done.load() ?: throw Exception("No hop connection established")
    }


    suspend fun connect(idun: Idun, peerId: PeerId): ClientConnection {
        var connection: ClientConnection? = null
        val address = reachable[peerId]
        if (address != null) {
            connection = connectDagr(address, TIMEOUT)
        }
        if (connection != null && !connection.isClosed) {
            return connection
        }
        return resolveConnection(idun, peerId)

    }


}
