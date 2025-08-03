package io.github.remmerw.idun


import kotlinx.coroutines.runBlocking
import kotlinx.io.files.SystemFileSystem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class FetchTest {
    @Test
    fun testClientServerDownload(): Unit = runBlocking {

        val storage = newStorage()
        val server = newIdun()
        server.startup(storage = storage)

        val fid = TestEnv.createContent(storage, 100)
        assertNotNull(fid)


        val temp = tempFile()

        val client = newIdun()

        client.reachable(
            server.peerId(), TestEnv.loopbackAddress(server.localPort())
        )

        val request = pnsUri(server.peerId(), fid)


        SystemFileSystem.sink(temp).use { sink ->
            client.transferTo(sink, request) { progress ->
                {
                    println("Progress $progress")
                }
            }
        }

        client.shutdown()

        val length = SystemFileSystem.metadataOrNull(temp)?.size ?: 0
        assertEquals(length, fid.size())

        SystemFileSystem.delete(temp)
        server.shutdown()
        storage.delete()
    }
}
