package io.github.remmerw.idun

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CloseTest {
    @Test
    fun closeConnect(): Unit = runBlocking(Dispatchers.IO) {

        val serverPort = TestEnv.randomPort()
        val storage = newStorage()
        val server = newIdun()
        server.runService(storage, serverPort)
        storage.root("Homepage".encodeToByteArray())
        val raw = storage.root().cid()


        val client = newIdun()
        assertTrue(
            client.reachable(
                TestEnv.loopbackPeeraddr(server.peerId(), serverPort)
            )
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