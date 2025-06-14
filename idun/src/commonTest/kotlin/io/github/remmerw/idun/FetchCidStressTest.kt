package io.github.remmerw.idun

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class FetchCidStressTest {
    @Test
    fun fetchCidIterations(): Unit = runBlocking(Dispatchers.IO) {
        val serverPort = TestEnv.randomPort()
        val storage = newStorage()
        val server = newIdun()
        server.runService(storage, serverPort)
        storage.root("Homepage".encodeToByteArray())
        val raw = storage.root().cid()

        val request = TestEnv.loopbackRequest(server.peerId(), serverPort)
        val client = newIdun()
        repeat(TestEnv.ITERATIONS) {
            val rootUri = client.fetchRoot(request)
            assertNotNull(rootUri)
            val value = extractCid(rootUri)
            assertEquals(value, raw)
        }
        client.shutdown()

        server.shutdown()
        storage.delete()
    }
}