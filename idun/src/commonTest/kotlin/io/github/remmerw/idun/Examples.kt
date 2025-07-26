package io.github.remmerw.idun

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class Examples {
    @Test
    fun simpleRequestResponse(): Unit = runBlocking(Dispatchers.IO) {

        val storage = newStorage()
        val raw = storage.storeText("Moin") // store some text

        val server = newIdun(storage)

        val peeraddrs = server.observedAddresses()
        checkNotNull(peeraddrs)
        println("Observed addresses ${peeraddrs.size}")


        // publish your addresses
        server.publishAddresses(peeraddrs, 25, 60)


        println("Num reservations " + server.numReservations())
        assertTrue(server.numReservations() > 0)


        val client = newIdun()

        val data = client.fetchData(server.peerId(), raw.cid())
        assertEquals(data.decodeToString(), "Moin")

        client.shutdown()
        server.shutdown()
        storage.delete() // Note: this one deletes all content in the storage
    }
}