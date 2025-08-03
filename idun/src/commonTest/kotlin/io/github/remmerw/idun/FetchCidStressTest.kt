package io.github.remmerw.idun

import io.github.remmerw.idun.core.Raw
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertContentEquals

class FetchCidStressTest {
    @Test
    fun fetchCidIterations(): Unit = runBlocking(Dispatchers.IO) {

        val storage = newStorage()
        val server = newIdun()
        server.startup(storage = storage)

        checkNotNull(server)
        checkNotNull(server.keys())

        val node = storage.storeData("aaa".encodeToByteArray())

        val data = (node as Raw).data()

        val client = newIdun()


        client.reachable(
            server.peerId(), TestEnv.loopbackAddress(server.localPort())
        )


        repeat(TestEnv.ITERATIONS) {
            val value = client.fetchRaw(server.peerId(), node.cid())
            assertContentEquals(value, data)
        }
        client.shutdown()

        server.shutdown()
        storage.delete()
    }
}