package io.github.remmerw.idun

import kotlinx.coroutines.runBlocking
import kotlinx.io.Buffer
import kotlinx.io.readByteArray
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertNotNull

class FetchRequestsTest {
    @Test
    fun fetchRequests(): Unit = runBlocking {

        val storage = newStorage()
        val server = newIdun()
        server.startup(storage = storage)

        val content = "Moin Moin"
        val bytes = TestEnv.randomBytes(5000)

        val fid = TestEnv.createContent(storage, "index.txt", content.encodeToByteArray())
        assertNotNull(fid)

        val bin = TestEnv.createContent(storage, "payload.bin", bytes)
        assertNotNull(bin)


        val client = newIdun()

        client.reachable(
            server.peerId(), TestEnv.loopbackAddress(server.localPort())
        )

        val sink = Buffer()
        client.transferTo(sink, pnsUri(server.peerId(), fid))

        assertContentEquals(sink.readByteArray(), content.encodeToByteArray())


        client.transferTo(sink, pnsUri(server.peerId(), bin))

        assertContentEquals(sink.readByteArray(), bytes)


        client.shutdown()
        server.shutdown()
        storage.delete()
    }
}