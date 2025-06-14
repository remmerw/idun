package io.github.remmerw.idun.core

import io.github.remmerw.asen.Asen
import io.github.remmerw.asen.PeerId
import io.github.remmerw.asen.Peeraddr
import io.github.remmerw.idun.RESOLVE_TIMEOUT
import io.github.remmerw.idun.debug
import io.github.remmerw.idun.extractPeerId
import io.github.remmerw.idun.extractPeeraddr
import io.ktor.network.selector.SelectorManager
import io.ktor.network.sockets.InetSocketAddress
import io.ktor.network.sockets.aSocket
import kotlinx.atomicfu.locks.reentrantLock
import kotlinx.atomicfu.locks.withLock

internal class Connector(private val selectorManager: SelectorManager) {
    private val channels: MutableSet<Channel> = mutableSetOf()
    private val lock = reentrantLock()

    private fun resolve(target: PeerId): Channel? {
        val pnsChannels = channels(target)
        if (pnsChannels.isNotEmpty()) {
            return pnsChannels.iterator().next()
        }
        return null
    }

    private suspend fun resolveAddress(asen: Asen, target: PeerId): Channel {
        val connection = resolve(target)
        if (connection != null) {
            return connection
        }

        val peeraddrs = asen.findPeer(target, RESOLVE_TIMEOUT.toLong())
        peeraddrs.forEach { peeraddr ->
            try {
                return openChannel(selectorManager, this, peeraddr)
            } catch (throwable: Throwable) {
                debug(throwable)
            }
        }

        throw Exception("No hop connection established")
    }


    suspend fun connect(asen: Asen, uri: String): Channel {
        val peeraddr = extractPeeraddr(uri)
        if (peeraddr != null) {
            return connect(peeraddr)
        }

        val peerId = extractPeerId(uri)
        if (peerId != null) {
            return connect(asen, peerId)
        }
        throw Exception("Invalid URI $uri")
    }

    suspend fun connect(peeraddr: Peeraddr): Channel {
        val pnsChannels = channels(peeraddr)
        if (pnsChannels.isNotEmpty()) {
            return pnsChannels.iterator().next()
        }
        return openChannel(selectorManager, this, peeraddr)
    }

    suspend fun connect(asen: Asen, peerId: PeerId): Channel {
        val pnsChannels = channels(peerId)
        if (pnsChannels.isNotEmpty()) {
            return pnsChannels.iterator().next()
        }
        return resolveAddress(asen, peerId)
    }

    fun channels(peeraddr: Peeraddr): Set<Channel> {
        return channels().filter { channel -> channel.remotePeeraddr == peeraddr }.toSet()
    }

    fun channels(peerId: PeerId): Set<Channel> {
        return channels().filter { channel -> channel.remotePeerId() == peerId }.toSet()
    }

    fun channels(): Set<Channel> {
        lock.withLock {
            val result: MutableSet<Channel> = mutableSetOf()
            val delete: MutableSet<Channel> = mutableSetOf()
            for (channel in channels) {
                if (channel.isConnected) {
                    result.add(channel)
                } else {
                    delete.add(channel)
                }
            }
            delete.forEach { channel -> removeChannel(channel) }
            return result
        }
    }

    fun registerChannel(channel: Channel) {
        lock.withLock {
            channels.add(channel)
        }
    }

    fun removeChannel(channel: Channel) {
        lock.withLock {
            channels.remove(channel)
        }
    }

    fun shutdown() {
        channels().forEach { channel: Channel -> channel.close() }
        channels.clear()
    }


    internal suspend fun openChannel(
        selectorManager: SelectorManager,
        connector: Connector,
        peeraddr: Peeraddr
    ): Channel {

        val socket = aSocket(selectorManager).tcp().connect(
            InetSocketAddress(peeraddr.address(), peeraddr.port.toInt())
        )
        checkNotNull(socket)

        val channel = Channel(peeraddr, connector, socket)
        connector.registerChannel(channel)
        return channel
    }

}
