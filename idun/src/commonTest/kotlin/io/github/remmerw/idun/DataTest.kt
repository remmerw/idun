package io.github.remmerw.idun

import io.github.remmerw.idun.core.OCTET_MIME_TYPE
import kotlinx.coroutines.runBlocking
import kotlinx.io.Buffer
import kotlinx.io.buffered
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readByteArray
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class DataTest {

    @Test
    fun storeAndGetBlock(): Unit = runBlocking {
        val storage = newStorage()
        val maxSize = 50

        // prepare data
        val bytes = Random.nextBytes(maxSize)
        val buffer = Buffer()
        buffer.write(bytes)

        // (1) store block
        val cid = storage.nextCid()
        storage.storeBlock(cid, buffer)

        // (2) get block
        val cmp = storage.fetchBlock(cid)
        assertNotNull(cmp)

        // tests
        val cmpBytes = cmp.readByteArray()
        assertContentEquals(bytes, cmpBytes)

        // cleanup
        storage.deleteBlock(cid)
        storage.delete()
    }

    @Test
    fun storeAndGetRaw(): Unit = runBlocking {
        val storage = newStorage()
        val maxSize = 50

        val random = Random
        val bytes = ByteArray(maxSize)
        random.nextBytes(bytes)

        // (1) store data
        val node = storage.storeData(bytes)

        // tests
        assertNotNull(node)
        assertEquals(node.size(), maxSize.toLong())


        // (2) get data
        val data = storage.fetchData(node)
        assertEquals(data, bytes)
        storage.delete()
    }

    @Test
    fun storeAndGetFile(): Unit = runBlocking {
        val storage = newStorage()

        val temp = storage.tempFile()
        val iteration = 10
        SystemFileSystem.sink(temp).buffered().use { source ->
            repeat(iteration) {
                source.write(TestEnv.getRandomBytes(SPLITTER_SIZE.toInt()))
            }
        }
        // (1) store the file
        val node = storage.storeFile(temp, OCTET_MIME_TYPE)

        // tests
        assertNotNull(node)
        assertEquals(node.size(), (iteration * SPLITTER_SIZE.toInt()).toLong())
        assertEquals(node.name(), temp.name)
        assertEquals(node.mimeType(), OCTET_MIME_TYPE)

        // (2) get the file
        val outputFile = storage.tempFile()
        storage.transferTo(node, outputFile)

        // tests
        assertEquals(
            SystemFileSystem.metadataOrNull(outputFile)?.size,
            SystemFileSystem.metadataOrNull(temp)?.size
        )

        // cleanup
        SystemFileSystem.delete(outputFile)
        SystemFileSystem.delete(temp)
        storage.delete()
    }
}