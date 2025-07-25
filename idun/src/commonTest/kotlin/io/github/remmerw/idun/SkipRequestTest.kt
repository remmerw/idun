package io.github.remmerw.idun

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.io.Buffer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.use

class SkipRequestTest {


    @Test
    fun skipRequest(): Unit = runBlocking(Dispatchers.IO) {

        val storage = newStorage()
        val server = newIdun(storage)

        val fid = TestEnv.createContent(storage, 10)

        val skip = fid.size() / 2 - 100
        val rest = fid.size() - skip

        assertEquals(skip+rest, fid.size())

        val client = newIdun()

        client.reachable(
            TestEnv.loopbackPeeraddr(server.peerId(), server.localPort())
        )

        val request = pnsUri(server.peerId(), fid.cid())

        val bytes = ByteArray(4096)
        Buffer().use { sink ->
            client.request(request).channel.asInputStream().use { inputStream ->
                inputStream.skip(skip)
                do {
                    val read = inputStream.read(bytes)
                    if (read > 0) {
                        sink.write(bytes, 0, read)
                    }
                } while (read > 0)
            }
            assertEquals(sink.size, rest)
        }


        client.shutdown()
        server.shutdown()
        storage.delete()

    }
}