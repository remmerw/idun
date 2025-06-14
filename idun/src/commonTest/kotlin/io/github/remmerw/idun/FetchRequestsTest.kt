package io.github.remmerw.idun

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class FetchRequestsTest {
    @Test
    fun fetchRequests(): Unit = runBlocking {
        val serverPort = TestEnv.randomPort()
        val storage = newStorage()
        val server = newIdun()

        server.runService(storage, serverPort)
        val loopback = TestEnv.loopbackPeeraddr(server.peerId(), serverPort)

        val content = "Moin Moin"
        val bytes = TestEnv.getRandomBytes(5000)

        val fid = TestEnv.createContent(storage, "index.txt", content.encodeToByteArray())
        assertNotNull(fid)

        val bin = TestEnv.createContent(storage, "payload.bin", bytes)
        assertNotNull(bin)


        val client = newIdun()
        var request = createRequest(loopback, fid)
        var data = client.channel(request).readAllBytes()

        assertNotNull(data)
        assertTrue(data.contentEquals(content.encodeToByteArray()))


        request = createRequest(loopback, bin)
        data = client.channel(request).readAllBytes()

        assertNotNull(data)
        assertTrue(data.contentEquals(bytes))
        client.shutdown()
        server.shutdown()
        storage.delete()
    }
}