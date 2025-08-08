package io.github.remmerw.idun

import io.github.remmerw.idun.core.Raw
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

class CloseTest {
    @Test
    fun closeConnect(): Unit = runBlocking(Dispatchers.IO) {

        val storage = newStorage()
        val server = newIdun()
        server.startup(storage = storage)

        val node = storage.storeData("Homepage".encodeToByteArray())

        val raw = (node as Raw).data

        val client = newIdun()

        client.reachable(
            server.peerId(), TestEnv.loopbackAddress(server.localPort())
        )

        val data = client.fetchRaw(server.peerId(), node.cid)
        assertContentEquals(data, raw)

        delay(5000 + 1000) // timeout 5 sec + 1 sec extra
        assertEquals(server.numIncomingConnections(), 0)

        client.shutdown()

        server.shutdown()
        storage.delete()
    }


}