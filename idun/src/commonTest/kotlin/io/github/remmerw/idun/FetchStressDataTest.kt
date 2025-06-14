package io.github.remmerw.idun

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertTrue

class FetchStressDataTest {
    @Test
    fun stressFetchData(): Unit = runBlocking {
        val serverPort = TestEnv.randomPort()
        val iterations = 100
        val storage = newStorage()
        val server = newIdun()
        server.runService(storage, serverPort)
        val client = newIdun()
        repeat(iterations) {
            val data = TestEnv.getRandomBytes(SPLITTER_SIZE.toInt())
            val raw = storage.storeData(data)

            val request =
                createRequest(
                    TestEnv.loopbackPeeraddr(server.peerId(), serverPort),
                    raw.cid()
                )

            val cmp = client.fetchData(request)
            assertTrue(data.contentEquals(cmp))
        }
        client.shutdown()
        server.shutdown()
        storage.delete()
    }
}
