package io.github.remmerw.idun

import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals

class Examples {
    @Test
    fun simpleRequestResponse(): Unit = runBlocking {
        // create local server and client instance
        val port = TestEnv.randomPort()
        val storage = newStorage()
        val raw = storage.storeText("Moin") // store some text

        val server = newIdun()

        // startup the service
        launch {
            server.startup(storage, port, 25, 120)
        }

        delay(30000) // 30 sec delay, so server can make reservations

        val client = newIdun()

        val data = client.fetchData(server.peerId(), raw.cid()) // fetch request
        assertEquals(data.decodeToString(), "Moin")

        client.shutdown()
        server.shutdown()
        storage.delete() // Note: this one deletes all content in the storage
    }
}