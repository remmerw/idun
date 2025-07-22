package io.github.remmerw.idun

import io.github.remmerw.idun.core.OCTET_MIME_TYPE
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class RequestTest {

    @Test
    fun storeAndRequestData(): Unit = runBlocking {

        val storage = newStorage()
        val server = newIdun(storage)

        val packetSize = 3
        val maxData = UShort.MAX_VALUE.toInt()

        // prepare data
        val fid = TestEnv.createContent(
            storage, "test.bin", OCTET_MIME_TYPE,
            TestEnv.randomBytes(maxData * packetSize)
        )


        val client = newIdun()

        client.reachable(
            TestEnv.loopbackPeeraddr(server.peerId(), server.localPort())
        )
        val request = pnsUri(server.peerId(), fid.cid())

        val response = client.request(request)

        assertNotNull(response)

        val channel = response.channel
        checkNotNull(channel)

        channel.seek(UShort.MAX_VALUE.toLong())


        val data = channel.readBytes()

        assertEquals(data.size, UShort.MAX_VALUE.toInt() * 2)


        // cleanup
        client.shutdown()
        server.shutdown()
        storage.delete()
    }
}