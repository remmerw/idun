package io.github.remmerw.idun

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class StressTest {
    @Test
    fun stressTest(): Unit = runBlocking(Dispatchers.IO) {

        val storage = newStorage()
        val server = newIdun(storage)
        val client = newIdun()

        client.reachable(
            TestEnv.loopbackPeeraddr(
                server.peerId(), server.localPort()
            )
        )
        val cmp = TestEnv.randomBytes(splitterSize())
        val node = storage.storeData(cmp)


        repeat(5000) {

            val data = client.fetchRaw(server.peerId(), node.cid())
            assertEquals(cmp.size, data.size)
            assertTrue(cmp.contentEquals(data))
        }

        client.shutdown()
        server.shutdown()
        storage.delete()
    }
}