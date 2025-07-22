package io.github.remmerw.idun

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertTrue

class DialTest {


    @Test
    fun testDial(): Unit = runBlocking(Dispatchers.IO) {
        val serverPort = TestEnv.randomPort()

        val storage = newStorage()

        val server = newIdun(storage, serverPort)

        val publicPeeraddrs = TestEnv.loopbackAddress(serverPort)

        server.publishAddresses(publicPeeraddrs, 25, 120)

        assertTrue(server.hasReservations())

        val client = newIdun()

        val addresses = client.resolveAddresses(server.peerId(), 60)
        assertTrue(addresses.isNotEmpty())
        client.shutdown()


        server.shutdown()
        storage.delete()
    }


}