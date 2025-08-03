package io.github.remmerw.idun

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.io.Buffer
import kotlin.test.Test
import kotlin.test.assertEquals

class SkipRequestTest {


    @Test
    fun skipRequest(): Unit = runBlocking(Dispatchers.IO) {

        val storage = newStorage()
        val server = newIdun()
        server.startup(storage = storage)

        val fid = TestEnv.createContent(storage, 10)

        val skip = fid.size() / 2 - 100
        val rest = fid.size() - skip

        assertEquals(skip + rest, fid.size())

        val client = newIdun()

        client.reachable(
            server.peerId(), TestEnv.loopbackAddress(server.localPort())
        )

        val request = pnsUri(server.peerId(), fid)


        val sink = Buffer()
        client.transferTo(sink, request, skip)
        assertEquals(sink.size, rest)
        sink.clear()


        client.shutdown()
        server.shutdown()
        storage.delete()

    }
}