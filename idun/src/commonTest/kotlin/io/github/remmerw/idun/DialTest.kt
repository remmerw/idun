package io.github.remmerw.idun

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertTrue

class DialTest {


    @Test
    fun testDial(): Unit = runBlocking(Dispatchers.IO) {
        val storage = newStorage()

        val server = newIdun(storage)

        val peeraddrs = server.observedAddresses()
        checkNotNull(peeraddrs)
        println("Observed addresses ${peeraddrs.size}")

        server.publishAddresses(peeraddrs, 25, 120)

        assertTrue(server.numReservations() > 0)

        val client = newIdun()

        val addresses = client.resolveAddresses(server.peerId(), 60)
        assertTrue(addresses.isNotEmpty())
        client.shutdown()


        server.shutdown()
        storage.delete()
    }


}