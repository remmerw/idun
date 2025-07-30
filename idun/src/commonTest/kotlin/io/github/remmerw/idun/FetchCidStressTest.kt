package io.github.remmerw.idun

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertContentEquals

class FetchCidStressTest {
    @Test
    fun fetchCidIterations(): Unit = runBlocking(Dispatchers.IO) {

        val storage = newStorage()
        val server = newIdun(storage)

        checkNotNull(server)
        checkNotNull(server.keys())

        storage.root("Homepage".encodeToByteArray())
        val data = storage.root()

        val client = newIdun()


        client.reachable(
            server.peerId(), TestEnv.loopbackAddress(server.localPort())
        )


        repeat(TestEnv.ITERATIONS) {
            val value = client.fetchRaw(server.peerId())
            assertContentEquals(value, data)
        }
        client.shutdown()

        server.shutdown()
        storage.delete()
    }
}