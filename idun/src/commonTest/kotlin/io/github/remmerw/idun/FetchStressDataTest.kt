package io.github.remmerw.idun

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertTrue

class FetchStressDataTest {
    @Test
    fun stressFetchData(): Unit = runBlocking(Dispatchers.IO) {

        val iterations = 100
        val storage = newStorage()
        val server = newIdun(storage)

        val client = newIdun()


        client.reachable(
            TestEnv.loopbackPeeraddr(server.peerId(), server.localPort())
        )


        repeat(iterations) { iter ->
            val data = TestEnv.randomBytes(splitterSize())
            val raw = storage.storeData(data)

            val cmp = client.fetchData(server.peerId(), raw.cid())
            assertTrue(data.contentEquals(cmp), "Failed for iteration $iter")
        }
        client.shutdown()
        server.shutdown()
        storage.delete()
    }
}
