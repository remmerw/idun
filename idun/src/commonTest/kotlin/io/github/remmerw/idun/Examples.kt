package io.github.remmerw.idun

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class Examples {
    @Test
    fun simpleRequestResponse(): Unit = runBlocking {
        // create local server and client instance
        val port = TestEnv.randomPort()
        val storage = newStorage()
        val raw = storage.storeText("Moin") // store some text

        val server = newIdun()
        // run the service with the given port and with the data stored in storage
        server.runService(storage, port)

        val client = newIdun()

        // >>> make a direct connection possible (otherwise the asen functionality has to be used)
        assertTrue(
            client.reachable(
                TestEnv.loopbackPeeraddr(server.peerId(), port)
            )
        )
        // <<< end


        val data = client.fetchData(server.peerId(), raw.cid()) // fetch request
        assertEquals(data.decodeToString(), "Moin")

        client.shutdown()
        server.shutdown()
        storage.delete() // Note: this one deletes all content in the storage
    }
}