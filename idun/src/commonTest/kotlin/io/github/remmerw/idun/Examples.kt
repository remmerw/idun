package io.github.remmerw.idun

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals

class Examples {
    @Test
    fun simpleRequestResponse(): Unit = runBlocking {

        val port = TestEnv.randomPort()
        val storage = newStorage()
        val raw = storage.storeText("Moin") // store some text

        val server = newIdun()

        // startup the service
        server.startup(storage, port, 25, 60)

        delay(30000) // wait for 30 sec for server to settle

        val client = newIdun()

        val data = client.fetchData(server.peerId(), raw.cid()) // fetch request
        assertEquals(data.decodeToString(), "Moin")

        client.shutdown()
        server.shutdown()
        storage.delete() // Note: this one deletes all content in the storage
    }
}