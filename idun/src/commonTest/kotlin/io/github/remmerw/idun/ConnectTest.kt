package io.github.remmerw.idun

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull


class ConnectTest {


    @Test
    fun testClientClose(): Unit = runBlocking {

        val storage = newStorage()
        val server = newIdun(storage)
        val node = storage.storeData("aaa".encodeToByteArray())

        val client = newIdun()


        client.reachable(
            server.peerId(), TestEnv.loopbackAddress(server.localPort())
        )


        val data = client.fetchRaw(server.peerId(), node.cid())
        assertNotNull(data)


        assertEquals(server.numOutgoingConnections(), 0)
        assertEquals(server.numIncomingConnections(), 0)

        assertEquals(client.numOutgoingConnections(), 0)
        assertEquals(client.numIncomingConnections(), 0)
        client.shutdown()

        server.shutdown()
        storage.delete()
    }

    @Test
    fun testServerClose(): Unit = runBlocking {

        val storage = newStorage()
        val server = newIdun(storage)
        val node = storage.storeData("aaa".encodeToByteArray())
        val client = newIdun()


        client.reachable(
            server.peerId(), TestEnv.loopbackAddress(server.localPort())
        )


        val data = client.fetchRaw(server.peerId(), node.cid())
        assertNotNull(data)


        assertEquals(server.numOutgoingConnections(), 0)
        assertEquals(server.numIncomingConnections(), 0)

        assertEquals(client.numOutgoingConnections(), 0)
        assertEquals(client.numIncomingConnections(), 0)


        assertEquals(server.numReservations(), 0)

        client.shutdown()

        server.shutdown()
        storage.delete()
    }
}
