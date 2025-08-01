package io.github.remmerw.idun

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
        val server = newIdun(storage)

        storage.root("Homepage".encodeToByteArray())
        val raw = storage.root()


        val client = newIdun()

        client.reachable(
            server.peerId(), TestEnv.loopbackAddress(server.localPort())
        )

        val data = client.fetchRaw(server.peerId())
        assertContentEquals(data, raw)
        client.shutdown()

        delay(100)
        assertEquals(server.numIncomingConnections(), 0)
        server.shutdown()
        storage.delete()
    }


}