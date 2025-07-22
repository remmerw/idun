package io.github.remmerw.idun

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals

class FetchCidStressTest {
    @Test
    fun fetchCidIterations(): Unit = runBlocking(Dispatchers.IO) {

        val storage = newStorage()
        val server = newIdun(storage)

        checkNotNull(server)
        checkNotNull(server.keys())


        storage.root("Homepage".encodeToByteArray())
        val raw = storage.root().cid()

        val client = newIdun()


        client.reachable(
            TestEnv.loopbackPeeraddr(server.peerId(), server.localPort())
        )


        repeat(TestEnv.ITERATIONS) {
            val value = client.fetchRoot(server.peerId())
            assertEquals(value, raw)
        }
        client.shutdown()

        server.shutdown()
        storage.delete()
    }
}