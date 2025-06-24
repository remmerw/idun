package io.github.remmerw.idun

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.runBlocking
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.concurrent.atomics.incrementAndFetch
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DialTest {


    @Test
    fun testDial(): Unit = runBlocking(Dispatchers.IO) {
        val serverPort = TestEnv.randomPort()

        val storage = newStorage()
        val server = newIdun()
        server.runService(storage, serverPort)

        val peerStore = server.peerStore()

        storage.root("Homepage".encodeToByteArray())
        val raw = storage.root().cid()

        val publicPeeraddrs = TestEnv.peeraddrs(server.peerId(), serverPort)

        server.makeReservations(publicPeeraddrs, 25, 120)

        assertTrue(server.hasReservations())

        val client = newIdun(
            peerStore = peerStore,
        )
        assertTrue(
            client.reachable(
                TestEnv.loopbackPeeraddr(server.peerId(), serverPort)
            )
        )

        val cid = client.fetchRoot(server.peerId())
        assertEquals(cid, raw)
        client.shutdown()


        server.shutdown()
        storage.delete()
    }

    @OptIn(ExperimentalAtomicApi::class)
    @Test
    fun testDialAll(): Unit = runBlocking(Dispatchers.IO) {
        val serverPort = TestEnv.randomPort()

        val storage = newStorage()
        val server = newIdun()
        server.runService(storage, serverPort)

        val publicPeeraddrs = TestEnv.peeraddrs(server.peerId(), serverPort)

        server.makeReservations(publicPeeraddrs, 25, 120)

        val reservations = server.reservations()

        val success = AtomicInt(0)

        for (relay in reservations) {
            val client = newIdun()
            try {
                client.findPeer(relay, server.peerId())
                success.incrementAndFetch()
            } catch (throwable: Throwable) {
                throwable.printStackTrace()
            } finally {
                client.shutdown()
            }
        }
        assertTrue(server.hasReservations())
        println("Success " + success.load() + " Total " + reservations.size)

        assertTrue(success.load() > 0)

        server.shutdown()
        storage.delete()
    }
}