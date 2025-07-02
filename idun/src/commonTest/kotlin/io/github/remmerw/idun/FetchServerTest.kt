package io.github.remmerw.idun

import kotlinx.coroutines.runBlocking
import kotlinx.io.buffered
import kotlinx.io.files.SystemFileSystem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.time.measureTime

class FetchServerTest {
    @Test
    fun fetchDataTest(): Unit = runBlocking {
        val serverPort = TestEnv.randomPort()
        val storage = newStorage()
        val server = newIdun()
        server.runService(storage, serverPort)


        var fid: Node? = null
        var timestamp = measureTime {
            fid = TestEnv.createContent(storage, 100) // 100 * SPLITTER_SIZE

        }
        assertNotNull(fid)


        println(
            ("Time Create Content " + timestamp.inWholeMilliseconds + " [ms]")
        )


        timestamp = measureTime {
            val client = newIdun()


            client.reachable(
                TestEnv.loopbackPeeraddr(server.peerId(), serverPort)
            )


            val out = storage.tempFile()
            val channel = client.channel(server.peerId(), fid.cid())


            SystemFileSystem.sink(out).buffered().use { sink ->
                channel.transferTo(sink) {}
            }
            assertEquals(SystemFileSystem.metadataOrNull(out)?.size, fid.size())
            SystemFileSystem.delete(out)

            client.shutdown()
        }

        println(
            "Time Client Read Content " + timestamp.inWholeMilliseconds + " [ms] "
        )

        server.shutdown()
        storage.delete()
    }
}
