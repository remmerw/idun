package io.github.remmerw.idun

import io.github.remmerw.idun.core.OCTET_MIME_TYPE
import io.github.remmerw.idun.core.Raw
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.io.buffered
import kotlinx.io.files.SystemFileSystem
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.concurrent.atomics.incrementAndFetch
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlin.time.measureTime


class HaloTest {

    @Test
    fun resetTest() {
        val data = newStorage()
        assertNotNull(data.root())
        data.reset()
        assertNotNull(data.root())
        val root = data.root() as Raw
        assertEquals(root.data().size, 0)
        assertNotNull(root.cid())
        data.delete()

    }


    @Test
    fun fetchDataTest(): Unit = runBlocking {
        val storage = newStorage()
        val test = "Moin"
        val cid = storage.storeText(test)
        assertNotNull(cid)
        val bytes = storage.fetchData(cid)
        assertNotNull(bytes)
        assertEquals(test, bytes.decodeToString())
        try {
            val fault = TestEnv.randomLong()
            storage.fetchBlock(fault)
            fail()
        } catch (_: Exception) {
            // ok
        }
        storage.delete()
    }

    @Test
    fun fetchInfoTest(): Unit = runBlocking {
        val storage = newStorage()
        val test = "Moin"
        val cid = storage.storeText(test)
        assertNotNull(cid)

        var node = storage.info(cid.cid())
        assertEquals(node.cid(), cid.cid())


        val temp = storage.tempFile()
        val iteration = 10
        SystemFileSystem.sink(temp).buffered().use { source ->
            repeat(iteration) {
                source.write(TestEnv.getRandomBytes(SPLITTER_SIZE.toInt()))
            }
        }
        // (1) store the file
        val fid = storage.storeFile(temp, OCTET_MIME_TYPE)

        node = storage.info(fid.cid())
        assertEquals(node.cid(), fid.cid())
        storage.delete()
    }

    @Test
    fun rawCid() {
        val storage = newStorage()
        val cid = storage.storeText("hallo")
        assertNotNull(cid)
        storage.delete()
    }

    @Test
    fun textProgressTest(): Unit = runBlocking {
        val storage = newStorage()
        val test = "Moin bla bla"
        val cid = storage.storeText(test)
        assertNotNull(cid)

        val bytes = storage.fetchData(cid)
        assertNotNull(bytes)
        assertEquals(test, bytes.decodeToString())

        val text = storage.fetchText(cid)
        assertNotNull(text)
        assertEquals(test, text)
        storage.delete()
    }


    @Test
    fun compareFileSizes(): Unit = runBlocking {
        val storage = newStorage()
        val fid = TestEnv.createContent(storage, 100)
        assertNotNull(fid)
        val temp = storage.tempFile()
        storage.transferTo(fid, temp)

        assertEquals(SystemFileSystem.metadataOrNull(temp)?.size, fid.size())

        SystemFileSystem.delete(temp)
        storage.delete(fid)
        storage.delete()
    }

    @Test
    fun response(): Unit = runBlocking {
        val storage = newStorage()
        val fid = TestEnv.createContent(storage, 100)
        assertNotNull(fid)
        val temp = storage.tempFile()
        storage.transferTo(fid, temp)

        assertEquals(SystemFileSystem.metadataOrNull(temp)?.size, fid.size())


        val response = storage.response(fid.cid())
        assertNotNull(response)
        assertEquals(response.mimeType, fid.mimeType())
        assertEquals(response.reason, "OK")
        assertEquals(response.status, 200)
        assertEquals(response.headers.size, 3)
        assertEquals(response.encoding, "UTF-8")
        assertNotNull(response.channel)


        SystemFileSystem.delete(temp)
        storage.delete(fid)
        storage.delete()
    }

    @Test
    fun performanceContentReadWrite(): Unit = runBlocking {
        val storage = newStorage()
        val packetSize = 10000
        val maxData = 25000


        val inputFile = storage.tempFile()
        SystemFileSystem.sink(inputFile).buffered().use { source ->
            repeat(maxData) {
                source.write(TestEnv.getRandomBytes(packetSize))
            }
        }

        val size = SystemFileSystem.metadataOrNull(inputFile)?.size
        val expected = packetSize * maxData.toLong()
        assertEquals(expected, size)

        val fid = storage.storeFile(inputFile, OCTET_MIME_TYPE)
        assertNotNull(fid)

        val temp = storage.tempFile()

        storage.transferTo(fid, temp)
        assertEquals(SystemFileSystem.metadataOrNull(temp)?.size, size)


        val outputFile1 = storage.tempFile()
        storage.transferTo(fid, outputFile1)


        val outputFile2 = storage.tempFile()
        storage.transferTo(fid, outputFile2)


        assertEquals(SystemFileSystem.metadataOrNull(outputFile2)?.size, size)
        assertEquals(SystemFileSystem.metadataOrNull(outputFile1)?.size, size)

        // cleanup
        SystemFileSystem.delete(temp)
        SystemFileSystem.delete(outputFile2)
        SystemFileSystem.delete(outputFile1)
        SystemFileSystem.delete(inputFile)

        storage.delete(fid)
        storage.delete()
    }

    @Test
    fun contentWrite() {
        val storage = newStorage()
        val data = 1000000
        val fid = TestEnv.createContent(
            storage, "test.bin", OCTET_MIME_TYPE,
            TestEnv.getRandomBytes(data)
        )

        val size = fid.size()

        assertEquals(data, size.toInt())
        storage.delete()
    }


    @Test
    fun storeInputStream(): Unit = runBlocking {


        val storage = newStorage()

        val data = 100000
        val fid = TestEnv.createContent(
            storage, "test.bin", OCTET_MIME_TYPE,
            TestEnv.getRandomBytes(data)
        )

        val size = fid.size()


        assertEquals(fid.size(), size)

        val temp = storage.tempFile()
        storage.transferTo(fid, temp)

        assertEquals(SystemFileSystem.metadataOrNull(temp)?.size, size)

        // cleanup
        SystemFileSystem.delete(temp)
        storage.delete()
    }

    @Test
    fun testString(): Unit = runBlocking {
        val storage = newStorage()
        val text = "Hello Moin und Zehn Elf"
        val raw = storage.storeText(text)
        assertNotNull(raw)

        val result = storage.fetchData(raw)
        assertNotNull(result)
        assertEquals(text, result.decodeToString())
        storage.delete()
    }

    @Test
    fun reopenFileStore(): Unit = runBlocking {

        val storage = newStorage()
        assertNotNull(storage)

        val textTest = "Hello World now"
        val raw = storage.storeText(textTest)
        assertNotNull(raw)

        val dataTest = TestEnv.getRandomBytes(1000)

        val data = storage.storeData(dataTest)
        assertNotNull(data)

        val storageCmp = newStorage(storage.directory())
        assertNotNull(storageCmp)

        val testCheck = storageCmp.fetchText(raw)
        assertEquals(testCheck, textTest)

        val dataCheck = storageCmp.fetchData(data)
        assertTrue(dataCheck.contentEquals(dataTest))

        storage.delete()
        storageCmp.delete()
    }


    @Test
    fun lotsOfData(): Unit = runBlocking {

        val storage = newStorage()
        assertNotNull(storage)

        val instances = 10000
        val nodes = mutableListOf<Node>()
        var timestamp = measureTime {
            for (i in 0 until instances) {
                val data = storage.storeText("test$i")
                assertNotNull(data)
                nodes.add(data)
            }
        }
        println(
            "Time Writing lotsOfData " + timestamp.inWholeMilliseconds + " [ms]"
        )


        timestamp = measureTime {
            for (i in 0 until instances) {
                val text = storage.fetchText(nodes[i])
                assertEquals(text, "test$i")
            }
        }
        println(
            "Time Reading lotsOfData " + timestamp.inWholeMilliseconds + " [ms]"
        )

        storage.delete()
    }

    @OptIn(ExperimentalAtomicApi::class)
    @Test
    fun testParallel() {
        val storage = newStorage()
        val data = TestEnv.getRandomBytes(SPLITTER_SIZE.toInt())
        val node = storage.storeData(data)
        assertNotNull(node)


        val finished = AtomicInt(0)
        val instances = 100

        runBlocking(Dispatchers.IO) {
            repeat(instances) {
                launch {
                    try {
                        val output = storage.fetchData(node)
                        assertTrue(data.contentEquals(output))
                        finished.incrementAndFetch()
                    } catch (throwable: Throwable) {
                        TestEnv.error(throwable)
                        fail()
                    }
                }
            }
        }


        assertEquals(finished.load(), instances)
        storage.delete()
    }
}