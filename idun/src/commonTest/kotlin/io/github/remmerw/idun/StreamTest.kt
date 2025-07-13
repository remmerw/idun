package io.github.remmerw.idun

import io.github.remmerw.idun.core.OCTET_MIME_TYPE
import kotlinx.io.Buffer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class StreamTest {

    @Test
    fun testStream() {
        val storage = newStorage()
        val packetSize = 3
        val maxData = UShort.MAX_VALUE.toInt()

        // prepare data
        val fid = TestEnv.createContent(
            storage, "test.bin", OCTET_MIME_TYPE,
            TestEnv.randomBytes(maxData * packetSize)
        )


        val response = storage.response(fid.cid())

        assertNotNull(response)

        response.channel.asInputStream().use { stream ->
            checkNotNull(stream)

            stream.skip(UShort.MAX_VALUE.toLong())

            val result = Buffer()
            val bytes = ByteArray(4096)
            do {
                val read = stream.read(bytes)
                if (read > 0) {
                    result.write(bytes, 0, read)
                }
            } while (read > 0)

            assertEquals(result.size.toInt(), UShort.MAX_VALUE.toInt() * 2)
        }

        // cleanup
        storage.delete()
    }

}