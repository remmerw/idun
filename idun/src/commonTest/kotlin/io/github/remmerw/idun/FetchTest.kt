package io.github.remmerw.idun


import kotlinx.coroutines.runBlocking
import kotlinx.io.buffered
import kotlinx.io.files.SystemFileSystem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class FetchTest {
    @Test
    fun testClientServerDownload(): Unit = runBlocking {
        val serverPort = TestEnv.randomPort()

        val storage = newStorage()
        val server = newIdun()
        server.runService(storage, serverPort)

        val fid = TestEnv.createContent(storage, 100)
        assertNotNull(fid)

        val loopback = TestEnv.loopbackPeeraddr(server.peerId(), serverPort)

        val temp = storage.tempFile()

        val client = newIdun()
        val request = createRequest(loopback, fid)

        val channel = client.channel(request)

        SystemFileSystem.sink(temp).buffered().use { sink ->
            channel.transferTo(sink) {}
        }
        client.shutdown()

        val length = SystemFileSystem.metadataOrNull(temp)?.size ?: 0
        assertEquals(length, fid.size())

        SystemFileSystem.delete(temp)
        server.shutdown()
        storage.delete()
    }
}
