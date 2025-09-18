package io.github.remmerw.idun

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.io.Buffer
import kotlinx.io.readByteArray
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.concurrent.atomics.incrementAndFetch
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail

class ServerTest {
    @Test
    fun fetchDataTest(): Unit = runBlocking(Dispatchers.IO) {

        val storage = newStorage()
        val server = newIdun()
        server.startup(storage = storage)


        val input = randomBytes(10000000) // 10 MB

        val fid = createContent(storage, "random.bin", input)
        assertNotNull(fid)

        val client = newIdun()

        client.reachable(
            server.peerId(), loopbackAddress(server.localPort())
        )

        val sink = Buffer()
        client.transferTo(sink, pnsUri(server.peerId(), fid))

        assertContentEquals(input, sink.readByteArray())
        client.shutdown()

        server.shutdown()
        storage.delete()
    }


    @Test
    fun serverTest(): Unit = runBlocking(Dispatchers.IO) {

        val storage = newStorage()
        val server = newIdun()
        server.startup(storage = storage)


        val text = "Hallo das ist ein Test"
        val raw = storage.storeText(text)
        assertNotNull(raw)

        val host = server.peerId()
        assertNotNull(host)
        val client = newIdun()

        client.reachable(
            server.peerId(), loopbackAddress(server.localPort())
        )

        val data = client.fetchRaw(server.peerId(), raw.cid)
        assertEquals(text, data.decodeToString())
        client.shutdown()

        server.shutdown()
        storage.delete()
    }


    @OptIn(ExperimentalAtomicApi::class)
    @Test
    fun multipleClients(): Unit = runBlocking(Dispatchers.IO) {

        val storage = newStorage()
        val server = newIdun()
        server.startup(storage = storage)

        val input = randomBytes(UShort.MAX_VALUE.toInt())

        val raw = storage.storeData(input)
        assertNotNull(raw)


        val finished = AtomicInt(0)
        val instances = 10

        repeat(instances) {
            try {
                val client = newIdun()

                client.reachable(
                    server.peerId(), loopbackAddress(server.localPort())
                )

                val output = client.fetchRaw(server.peerId(), raw.cid)
                assertTrue(input.contentEquals(output))
                finished.incrementAndFetch()
                client.shutdown()
            } catch (throwable: Throwable) {
                throwable.printStackTrace()
                fail()
            }
        }

        assertEquals(finished.load(), instances)
        server.shutdown()
        storage.delete()
    }


    @OptIn(ExperimentalAtomicApi::class)
    @Test
    fun multiplePings(): Unit = runBlocking(Dispatchers.IO) {

        val storage = newStorage()
        val server = newIdun()
        server.startup(storage = storage)


        val finished = AtomicInt(0)
        val instances = 10

        runBlocking(Dispatchers.IO) {

            repeat(instances) {
                launch {
                    val client = newIdun()
                    try {
                        client.reachable(
                            server.peerId(),
                            loopbackAddress(server.localPort())
                        )
                        finished.incrementAndFetch()
                    } catch (throwable: Throwable) {
                        throwable.printStackTrace()
                        fail()
                    } finally {
                        client.shutdown()
                    }
                }
            }
        }

        assertEquals(finished.load(), instances)

        server.shutdown()
        storage.delete()
    }
}