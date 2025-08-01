package io.github.remmerw.idun

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.io.Buffer
import kotlin.io.println
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.use

class ParallelTest {


    @Test
    fun testClientServerDownload(): Unit = runBlocking(Dispatchers.IO) {

        val storage = newStorage()
        val server = newIdun(storage)

        val fid1 = TestEnv.createContent(storage, 10)
        val fid2 = TestEnv.createContent(storage, 10)
        val fid3 = TestEnv.createContent(storage, 10)

        val client = newIdun()

        client.reachable(
            server.peerId(), TestEnv.loopbackAddress(server.localPort())
        )

        val tasks = listOf(
            launch {
                val request = pnsUri(server.peerId(), fid1.cid())
                println("A $request")
                val bytes = ByteArray(4096)
                Buffer().use { sink ->
                    client.request(request).asInputStream().use { inputStream ->
                        do {
                            val read = inputStream.read(bytes)
                            if (read > 0) {
                                sink.write(bytes, 0, read)
                            }
                        } while (read > 0)
                    }
                    assertEquals(sink.size, fid1.size())
                }
                println("A finished")
            },


            launch {
                val request = pnsUri(server.peerId(), fid2.cid())
                println("B $request")
                val bytes = ByteArray(4096)
                Buffer().use { sink ->
                    client.request(request).asInputStream().use { inputStream ->
                        do {
                            val read = inputStream.read(bytes)
                            if (read > 0) {
                                sink.write(bytes, 0, read)
                            }
                        } while (read > 0)
                    }
                    assertEquals(sink.size, fid2.size())
                }
                println("B finished")
            },

            launch {
                val request = pnsUri(server.peerId(), fid3.cid())
                println("C $request")

                val bytes = ByteArray(4096)
                Buffer().use { sink ->
                    client.request(request).asInputStream().use { inputStream ->
                        do {
                            val read = inputStream.read(bytes)
                            if (read > 0) {
                                sink.write(bytes, 0, read)
                            }
                        } while (read > 0)
                    }
                    assertEquals(sink.size, fid3.size())
                }
                println("C finished")
            }
        )
        tasks.joinAll()

        client.shutdown()
        server.shutdown()
        storage.delete()

    }
}