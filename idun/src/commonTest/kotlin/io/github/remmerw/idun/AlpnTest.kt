package io.github.remmerw.idun

import io.github.remmerw.idun.core.Raw
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AlpnTest {
    @Test
    fun alpnTest(): Unit = runBlocking(Dispatchers.IO) {

        val storage = newStorage()

        val server = newIdun(storage)


        val input = TestEnv.randomBytes(100) //

        storage.root(input)
        val cid = storage.root().cid()

        val cmp = (storage.root() as Raw).data()
        assertTrue(input.contentEquals(cmp))


        val client = newIdun()

        client.reachable(
            TestEnv.loopbackPeeraddr(server.peerId(), server.localPort())
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