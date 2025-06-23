package io.github.remmerw.idun

import io.github.remmerw.idun.core.Raw
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AlpnTest {
    @Test
    fun alpnTest(): Unit = runBlocking(Dispatchers.IO) {
        val serverPort = TestEnv.randomPort()
        val storage = newStorage()

        val server = newIdun()
        server.runService(storage, serverPort)

        val input = TestEnv.getRandomBytes(100) //

        storage.root(input)
        val cid = storage.root().cid()

        val cmp = (storage.root() as Raw).data()
        assertTrue(input.contentEquals(cmp))


        val client = newIdun()

        assertTrue(
            client.reachable(
                TestEnv.loopbackPeeraddr(server.peerId(), serverPort)
            )
        )


        val root = client.fetchRoot(server.peerId())
        checkNotNull(root)

        assertEquals(root, storage.root().cid())

        val data = client.fetchData(server.peerId(), cid)
        assertTrue(input.contentEquals(data))
        client.shutdown()

        server.shutdown()
        storage.delete()
    }

}