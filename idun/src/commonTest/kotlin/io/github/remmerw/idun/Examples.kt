package io.github.remmerw.idun

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
        // run the service with the given port and with the data stored in storage
        server.runService(storage, port)

        val client = newIdun()

        val request =
            createRequest(
                TestEnv.loopbackPeeraddr(server.peerId(), port),
                raw
            ) // request is a pns-URI

        val data = client.fetchData(request) // fetch request
        assertEquals(data.decodeToString(), "Moin")

        client.shutdown()
        server.shutdown()
        storage.delete() // Note: this one deletes all content in the storage
    }
}