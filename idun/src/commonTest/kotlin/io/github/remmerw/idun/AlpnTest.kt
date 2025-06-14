package io.github.remmerw.idun

import io.github.remmerw.idun.core.Raw
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
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


        val peeraddrs = TestEnv.peeraddrs(server.peerId(), serverPort)
        val peeraddr = peeraddrs.first()
        val client = newIdun()

        val requestRoot = createRequest(peeraddr)
        val rootUri = client.fetchRoot(requestRoot)
        assertNotNull(rootUri)

        val root = extractCid(rootUri)
        checkNotNull(root)

        assertEquals(root, storage.root().cid())

        val serverAddress = extractPeeraddr(rootUri)
        assertEquals(serverAddress, peeraddr)

        val request = createRequest(peeraddr, cid)

        val data = client.fetchData(request)
        assertTrue(input.contentEquals(data))
        client.shutdown()

        server.shutdown()
        storage.delete()
    }

}