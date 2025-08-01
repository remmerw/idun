package io.github.remmerw.idun

import io.github.remmerw.idun.core.OCTET_MIME_TYPE
import kotlinx.coroutines.runBlocking
import kotlinx.io.Buffer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class StreamTest {

    @Test
    fun testStream(): Unit = runBlocking {
        val storage = newStorage()
        val server = newIdun(storage)

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

        val response = client.request(request)

        assertNotNull(response)

        response.asInputStream().use { stream ->
            checkNotNull(stream)

            stream.skip(splitterSize().toLong())

            val result = Buffer()
            val bytes = ByteArray(4096)
            do {
                val read = stream.read(bytes)
                if (read > 0) {
                    result.write(bytes, 0, read)
                }
            } while (read > 0)

            assertEquals(result.size.toInt(), splitterSize() * 2)
        }

        // cleanup
        client.shutdown()
        server.shutdown()
        storage.delete()
    }

}