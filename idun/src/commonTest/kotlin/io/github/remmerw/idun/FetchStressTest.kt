package io.github.remmerw.idun

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.measureTime

class FetchStressTest {
    @Test
    fun stressFetchCalls(): Unit = runBlocking {
        val serverPort = TestEnv.randomPort()
        val iterations = 10

        val storage = newStorage()
        val server = newIdun()
        server.runService(storage, serverPort)
        var fid: Node? = null
        var now = measureTime {

            val dataSize = SPLITTER_SIZE.toInt()
            fid = TestEnv.createContent(
                storage, "text.bin",
                TestEnv.getRandomBytes(dataSize)
            )
        }
        assertNotNull(fid)

        println(
            "Store Data Time: " + now.inWholeMilliseconds + "[ms]"
        )

        val loopback = TestEnv.loopbackPeeraddr(server.peerId(), serverPort)

        repeat(iterations) {

            now = measureTime {
                val client = newIdun()

                assertTrue(client.reachable(loopback))

                val request = createRequest(loopback, fid)

                val data = client.channel(request).readAllBytes()
                assertEquals(fid.size().toInt(), data.size)
                client.shutdown()
            }

            println(
                "Read Data Time : " + now.inWholeMilliseconds + "[ms]"
            )

        }
        server.shutdown()
        storage.delete()
    }
}
