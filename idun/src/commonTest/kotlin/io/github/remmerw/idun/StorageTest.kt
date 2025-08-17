package io.github.remmerw.idun

import com.eygraber.uri.Uri
import io.github.remmerw.borr.PeerId
import io.github.remmerw.idun.TestEnv.randomBytes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.io.Buffer
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


class StorageTest {

    @Test
    fun resetTest() {
        val storage = newStorage()
        val node = storage.storeData("aaa".encodeToByteArray())
        val data = storage.fetchData(node)
        checkNotNull(data)
        assertTrue(storage.currentCid() > 0)
        storage.reset()
        assertTrue(storage.currentCid() == 0L)
        storage.delete()

    }

    @Test
    fun testPns() {
        val peerId = PeerId(randomBytes(32))
        val uri = pnsUri(
            peerId,
            cid = 10L,
            name = "unknown",
            mimeType = OCTET_MIME_TYPE,
            size = 0
        )
        assertNotNull(uri)
        val data = Uri.parse(uri)
        assertEquals(data.extractPeerId(), peerId)
        assertEquals(data.extractCid(), 10)
        assertEquals(data.extractSize(), 0)
        assertEquals(data.extractName(), "unknown")
        assertEquals(data.extractMimeType(), OCTET_MIME_TYPE)
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
            val buffer = Buffer()
            storage.transferBlock(buffer, fault)
            fail()
        } catch (_: Exception) {
            // ok
        }
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
        val temp = tempFile()
        storage.transferTo(fid, temp)

        assertEquals(SystemFileSystem.metadataOrNull(temp)?.size, fid.size)

        SystemFileSystem.delete(temp)
        storage.delete(fid)
        storage.delete()
    }


    @Test
    fun performanceContentReadWrite(): Unit = runBlocking {
        val storage = newStorage()
        val packetSize = 10000
        val maxData = 25000


        val inputFile = tempFile()
        SystemFileSystem.sink(inputFile).buffered().use { source ->
            repeat(maxData) {
                source.write(randomBytes(packetSize))
            }
        }

        val size = SystemFileSystem.metadataOrNull(inputFile)?.size
        val expected = packetSize * maxData.toLong()
        assertEquals(expected, size)

        val fid = storage.storeFile(inputFile, OCTET_MIME_TYPE)
        assertNotNull(fid)

        val temp = tempFile()

        storage.transferTo(fid, temp)
        assertEquals(SystemFileSystem.metadataOrNull(temp)?.size, size)


        val outputFile1 = tempFile()
        storage.transferTo(fid, outputFile1)


        val outputFile2 = tempFile()
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
            randomBytes(data)
        )

        val size = fid.size

        assertEquals(data, size.toInt())
        storage.delete()
    }


    @Test
    fun storeInputStream(): Unit = runBlocking {


        val storage = newStorage()

        val data = 100000
        val fid = TestEnv.createContent(
            storage, "test.bin", OCTET_MIME_TYPE,
            randomBytes(data)
        )

        val temp = tempFile()
        storage.transferTo(fid, temp)

        assertEquals(SystemFileSystem.metadataOrNull(temp)?.size, fid.size)

        // cleanup
        SystemFileSystem.delete(temp)
        storage.delete()
    }

    @Test
    fun testString(): Unit = runBlocking {
        val storage = newStorage()
        val text = "Hello Moin und Ten Elf"
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

        val dataTest = randomBytes(1000)

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
        val data = randomBytes(UShort.MAX_VALUE.toInt())
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
                        throwable.printStackTrace()
                        fail()
                    }
                }
            }
        }


        assertEquals(finished.load(), instances)
        storage.delete()
    }
}