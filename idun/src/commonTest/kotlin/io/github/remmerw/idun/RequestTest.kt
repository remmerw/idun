package io.github.remmerw.idun

import io.github.remmerw.idun.core.OCTET_MIME_TYPE
import kotlinx.coroutines.runBlocking
import kotlinx.io.Buffer
import kotlinx.io.readByteArray
import kotlin.test.Test
import kotlin.test.assertEquals

class RequestTest {

    @Test
    fun storeAndRequestData(): Unit = runBlocking {

        val storage = newStorage()
        val server = newIdun()
        server.startup(storage = storage)

        val packetSize = 3
        val maxData = splitterSize()

        // prepare data
        val fid = TestEnv.createContent(
            storage, "test.bin", OCTET_MIME_TYPE,
            TestEnv.randomBytes(maxData * packetSize)
        )


        val client = newIdun()

        client.reachable(
            server.peerId(), TestEnv.loopbackAddress(server.localPort())
        )
        val request = pnsUri(server.peerId(), fid)

        val sink = Buffer()
        client.transferTo(sink, request, splitterSize().toLong())
        val data = sink.readByteArray()

        assertEquals(data.size, splitterSize() * 2)


        // cleanup
        client.shutdown()
        server.shutdown()
        storage.delete()
    }
}