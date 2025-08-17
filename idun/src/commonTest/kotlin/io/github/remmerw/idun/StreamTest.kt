package io.github.remmerw.idun

import kotlinx.coroutines.runBlocking
import kotlinx.io.Buffer
import kotlinx.io.readByteArray
import kotlin.test.Test
import kotlin.test.assertEquals

class StreamTest {

    @Test
    fun testStream(): Unit = runBlocking {
        val storage = newStorage()
        val server = newIdun()
        server.startup(storage = storage)

        val packetSize = 3
        val maxData = UShort.MAX_VALUE.toInt()

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
        client.transferTo(sink, request, UShort.MAX_VALUE.toLong())
        val data = sink.readByteArray()

        assertEquals(data.size, UShort.MAX_VALUE.toInt() * 2)

        // cleanup
        client.shutdown()
        server.shutdown()
        storage.delete()
    }

}