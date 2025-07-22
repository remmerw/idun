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
        val server = newIdun(storage, serverPort)

        val client = newIdun()


        client.reachable(
            TestEnv.loopbackPeeraddr(server.peerId(), serverPort)
        )


        repeat(iterations) {
            val data = TestEnv.randomBytes(splitterSize())
            val raw = storage.storeData(data)

            val cmp = client.fetchData(server.peerId(), raw.cid())
            assertTrue(data.contentEquals(cmp))
        }
        client.shutdown()
        server.shutdown()
        storage.delete()
    }
}
