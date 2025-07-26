package io.github.remmerw.idun

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ChannelStressTest {
    @Test
    fun channelTestRun(): Unit = runBlocking(Dispatchers.IO) {

        val storage = newStorage()
        val server = newIdun(storage)
        val client = newIdun()

        client.reachable(
            TestEnv.loopbackPeeraddr(
                server.peerId(), server.localPort()
            )
        )


        repeat(100) {

            val node =
                storage.storeData(
                    TestEnv.randomBytes(splitterSize())
                ) // store some text

            val data = client.fetchData(server.peerId(), node.cid()) // fetch request
            assertNotNull(data)
            assertTrue(storage.fetchData(node).contentEquals(data))
        }

        client.shutdown()
        server.shutdown()
        storage.delete()
    }
}