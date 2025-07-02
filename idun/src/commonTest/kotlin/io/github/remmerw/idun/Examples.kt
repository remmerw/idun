package io.github.remmerw.idun

import io.github.remmerw.asen.createPeeraddr
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class Examples {
    @Test
    fun simpleRequestResponse(): Unit = runBlocking(Dispatchers.IO) {

        val port = TestEnv.randomPort()
        val storage = newStorage()
        val raw = storage.storeText("Moin") // store some text

        val server = newIdun()


        val address = server.observedAddress()
        checkNotNull(address)
        println("public address ${address.size}")

        val peeraddr = if (address.size == 16) { // ipv6 might be ok to dial
            createPeeraddr(
                server.peerId(),
                address, port.toUShort()
            )
        } else { // ip4 is probably not a dialable IP (so for testing just 127.0.0.1)
            createPeeraddr(
                server.peerId(),
                byteArrayOf(127, 0, 0, 1), port.toUShort()
            )
        }

        // startup the service
        server.startup(storage, listOf(peeraddr), port, 25, 60)

        delay(60000) // wait for 60 sec for server to settle

        println("Num reservations " + server.numReservations())
        assertTrue(server.hasReservations())

        val client = newIdun()

        val data = client.fetchData(server.peerId(), raw.cid())
        assertEquals(data.decodeToString(), "Moin")

        client.shutdown()
        server.shutdown()
        storage.delete() // Note: this one deletes all content in the storage
    }
}