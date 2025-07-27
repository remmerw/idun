package io.github.remmerw.idun

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

class FetchStressDataTest {
    @Test
    fun stressFetchData(): Unit = runBlocking(Dispatchers.IO) {

        val iterations = 1000
        val storage = newStorage()
        val server = newIdun(storage)

        val client = newIdun()

        client.reachable(
            TestEnv.loopbackPeeraddr(server.peerId(), server.localPort())
        )

        try {
            repeat(iterations) { iter ->
                val data = TestEnv.randomBytes(splitterSize())
                val raw = storage.storeData(data)


                val cmp = client.fetchData(server.peerId(), raw.cid())
                assertEquals(data.size, cmp.size, "Failed for iteration $iter")
                assertContentEquals(data, cmp, "Failed for iteration $iter")
            }
        } finally {
            client.shutdown()
            server.shutdown()
            storage.delete()
        }
    }
}
