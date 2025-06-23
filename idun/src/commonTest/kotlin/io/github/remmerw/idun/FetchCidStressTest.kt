package io.github.remmerw.idun

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FetchCidStressTest {
    @Test
    fun fetchCidIterations(): Unit = runBlocking(Dispatchers.IO) {
        val serverPort = TestEnv.randomPort()
        val storage = newStorage()
        val server = newIdun()

        checkNotNull(server)
        checkNotNull(server.keys())

        server.runService(storage, serverPort)
        storage.root("Homepage".encodeToByteArray())
        val raw = storage.root().cid()

        val client = newIdun()

        assertTrue(
            client.reachable(
                TestEnv.loopbackPeeraddr(server.peerId(), serverPort)
            )
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