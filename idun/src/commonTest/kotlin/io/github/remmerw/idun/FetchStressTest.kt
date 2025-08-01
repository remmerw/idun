package io.github.remmerw.idun

import kotlinx.coroutines.runBlocking
import kotlinx.io.Buffer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.time.measureTime

class FetchStressTest {
    @Test
    fun stressFetchCalls(): Unit = runBlocking {
        val iterations = 10

        val storage = newStorage()
        val server = newIdun(storage)

        var fid: Node? = null
        var now = measureTime {

            val split = splitterSize()
            fid = TestEnv.createContent(
                storage, "text.bin",
                TestEnv.randomBytes(split)
            )
        }
        assertNotNull(fid)

        println(
            "Store Data Time: " + now.inWholeMilliseconds + "[ms]"
        )



        repeat(iterations) {

            now = measureTime {
                val client = newIdun()


                client.reachable(
                    server.peerId(), TestEnv.loopbackAddress(server.localPort())
                )

                val sink = Buffer()
                client.transferTo(sink, pnsUri(server.peerId(), fid) )
                assertEquals(fid.size(), sink.size)
                sink.clear()

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
