package io.github.remmerw.idun

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.io.Buffer
import kotlin.test.Test
import kotlin.test.assertEquals

class ParallelTest {


    @Test
    fun testClientServerDownload(): Unit = runBlocking(Dispatchers.IO) {

        val storage = newStorage()
        val server = newIdun()
        server.startup(storage = storage)

        val fid1 = TestEnv.createContent(storage, 10)
        val fid2 = TestEnv.createContent(storage, 10)
        val fid3 = TestEnv.createContent(storage, 10)

        val client = newIdun()

        client.reachable(
            server.peerId(), TestEnv.loopbackAddress(server.localPort())
        )

        val tasks = listOf(
            launch {
                val request = pnsUri(server.peerId(), fid1)
                println("A $request")
                Buffer().use { sink ->
                    client.transferTo(sink, request)
                    assertEquals(sink.size, fid1.size())
                }
                println("A finished")
            },


            launch {
                val request = pnsUri(server.peerId(), fid2)
                println("B $request")
                Buffer().use { sink ->
                    client.transferTo(sink, request)
                    assertEquals(sink.size, fid2.size())
                }
                println("B finished")
            },

            launch {
                val request = pnsUri(server.peerId(), fid3)
                println("C $request")
                Buffer().use { sink ->
                    client.transferTo(sink, request)
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