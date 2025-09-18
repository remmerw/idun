package io.github.remmerw.idun

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

        val data = "aaa".encodeToByteArray()
        val node = storage.storeData(data)

        val client = newIdun()


        client.reachable(
            server.peerId(), loopbackAddress(server.localPort())
        )


        repeat(ITERATIONS) {
            val value = client.fetchRaw(server.peerId(), node.cid)
            assertContentEquals(value, data)
        }
        client.shutdown()

        server.shutdown()
        storage.delete()
    }
}