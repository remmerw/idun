package io.github.remmerw.idun

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals

class CloseTest {
    @Test
    fun closeConnect(): Unit = runBlocking(Dispatchers.IO) {

        val storage = newStorage()
        val server = newIdun(storage)

        storage.root("Homepage".encodeToByteArray())
        val raw = storage.root().cid()


        val client = newIdun()

        client.reachable(
            TestEnv.loopbackPeeraddr(server.peerId(), server.localPort())
        )

        val cid = client.fetchRoot(server.peerId())
        assertEquals(cid, raw)
        client.shutdown()

        delay(100)
        assertEquals(server.numIncomingConnections(), 0)
        server.shutdown()
        storage.delete()
    }


}