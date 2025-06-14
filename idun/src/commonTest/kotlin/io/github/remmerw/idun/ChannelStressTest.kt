package io.github.remmerw.idun

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ChannelStressTest {
    @Test
    fun channelTestRun(): Unit = runBlocking {
        // create server instance with default values
        val serverPort = TestEnv.randomPort()
        val storage = newStorage()
        val server = newIdun()

        val loopback = TestEnv.loopbackPeeraddr(server.peerId(), serverPort)
        server.runService(storage, serverPort)
        val client = newIdun()// client instance default values
        repeat(100) {

            val node =
                storage.storeData(
                    TestEnv.getRandomBytes(SPLITTER_SIZE.toInt())
                ) // store some text
            val request = createRequest(loopback, node)

            val data = client.fetchData(request) // fetch request
            assertNotNull(data)
            assertTrue(storage.fetchData(node).contentEquals(data))
        }
        client.shutdown()
        server.shutdown()
        storage.delete()
    }
}