package io.github.remmerw.idun

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue


class ConnectTest {


    @Test
    fun testServerIdle(): Unit = runBlocking {
        if (!TestEnv.supportLongRunningTests()) {
            return@runBlocking
        }
        val storage = newStorage()
        val server = newIdun(storage)

        val client = newIdun()


        client.reachable(
            TestEnv.loopbackPeeraddr(server.peerId(), server.localPort())
        )


        val root = client.fetchRoot(server.peerId())
        assertNotNull(root)

        delay(10000) // timeout is 10 sec (should be reached)
        assertEquals(server.numIncomingConnections(), 1)
        // but connection is still valid (keep alive is true)
        client.shutdown()
        server.shutdown()
        storage.delete()
    }

    @Test
    fun testClientClose(): Unit = runBlocking {

        val storage = newStorage()
        val server = newIdun(storage)

        val client = newIdun()


        client.reachable(
            TestEnv.loopbackPeeraddr(server.peerId(), server.localPort())
        )


        val root = client.fetchRoot(server.peerId())
        assertNotNull(root)

        val data = client.fetchData(server.peerId(), root)
        assertNotNull(data)
        assertTrue(data.contentEquals(byteArrayOf()))

        assertEquals(server.numIncomingConnections(), 1)


        assertEquals(server.numOutgoingConnections(), 0)
        assertEquals(server.numIncomingConnections(), 1)

        assertEquals(client.numOutgoingConnections(), 1)
        assertEquals(client.numIncomingConnections(), 0)
        client.shutdown()

        server.shutdown()
        storage.delete()
    }

    @Test
    fun testServerClose(): Unit = runBlocking {

        val storage = newStorage()
        val server = newIdun(storage)

        val client = newIdun()


        client.reachable(
            TestEnv.loopbackPeeraddr(server.peerId(), server.localPort())
        )


        val root = client.fetchRoot(server.peerId()) // Intern it sets keep alive to true
        assertNotNull(root)

        val data = client.fetchData(server.peerId(), root)
        assertNotNull(data)
        assertTrue(data.contentEquals(byteArrayOf()))


        assertEquals(server.numOutgoingConnections(), 0)
        assertEquals(server.numIncomingConnections(), 1)

        assertEquals(client.numOutgoingConnections(), 1)
        assertEquals(client.numIncomingConnections(), 0)


        assertEquals(server.numReservations(), 0)
        assertTrue(server.numIncomingConnections() > 0)

        client.shutdown()

        server.shutdown()
        storage.delete()
    }
}
