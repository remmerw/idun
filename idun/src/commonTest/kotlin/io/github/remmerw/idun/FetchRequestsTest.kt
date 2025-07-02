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


        val content = "Moin Moin"
        val bytes = TestEnv.getRandomBytes(5000)

        val fid = TestEnv.createContent(storage, "index.txt", content.encodeToByteArray())
        assertNotNull(fid)

        val bin = TestEnv.createContent(storage, "payload.bin", bytes)
        assertNotNull(bin)


        val client = newIdun()

        client.reachable(
            TestEnv.loopbackPeeraddr(server.peerId(), serverPort)
        )


        var data = client.channel(server.peerId(), fid.cid()).readAllBytes()
        assertNotNull(data)
        assertTrue(data.contentEquals(content.encodeToByteArray()))


        data = client.channel(server.peerId(), bin.cid()).readAllBytes()
        assertNotNull(data)
        assertTrue(data.contentEquals(bytes))


        client.shutdown()
        server.shutdown()
        storage.delete()
    }
}