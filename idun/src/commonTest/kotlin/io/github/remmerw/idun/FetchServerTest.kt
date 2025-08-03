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

        val storage = newStorage()
        val server = newIdun()
        server.startup(storage = storage)

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
                server.peerId(), TestEnv.loopbackAddress(server.localPort())
            )


            val out = tempFile()
            val request = pnsUri(server.peerId(), fid)

            SystemFileSystem.sink(out).buffered().use { sink ->
                client.transferTo(sink, request) { progress ->
                    {
                        println("Progress $progress")
                    }
                }
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
