package io.github.remmerw.idun

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

        // startup the service
        server.startup(storage, port, 25, 60)

        delay(60000) // wait for 60 sec for server to settle

        println("Num reservations " + server.numReservations())
        assertTrue(server.hasReservations())

        val client = newIdun()

        val data = client.fetchData(server.peerId(), raw.cid()) // fetch request
        assertEquals(data.decodeToString(), "Moin")

        client.shutdown()
        server.shutdown()
        storage.delete() // Note: this one deletes all content in the storage
    }
}