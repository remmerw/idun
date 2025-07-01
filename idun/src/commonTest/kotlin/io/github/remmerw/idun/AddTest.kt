package io.github.remmerw.idun

import io.github.remmerw.idun.core.OCTET_MIME_TYPE
import io.ktor.utils.io.core.remaining
import kotlinx.coroutines.runBlocking
import kotlinx.io.buffered
import kotlinx.io.readByteArray
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class AddTest {


    @Test
    fun add_and_remove(): Unit = runBlocking {
        assertFailsWith<Exception> {
            val storage = newStorage()
            val content = "Hello cat"
            val node = storage.storeText(content)
            assertEquals(content.encodeToByteArray().size, node.size().toInt())


            assertTrue(storage.hasBlock(node.cid()))

            val text = node.cid()

            assertTrue(storage.hasBlock(text))
            storage.delete(node)
            assertFalse(storage.hasBlock(text))

            try {
                storage.fetchBlock(node.cid()) // closed exception expected
            } finally {
                storage.delete()
            }
        }
    }


    @Test
    fun create_text() {
        val storage = newStorage()
        val content1 = "Pa gen fichye ki make pou efase"
        val text1 = TestEnv.createContent(
            storage, "Pa gen fichye ki make pou efase.txt",
            "text/plain", content1.encodeToByteArray()
        )
        assertNotNull(text1)

        val content2 = "Non pa valab"
        val text2 = TestEnv.createContent(
            storage, "Non pa valab.txt",
            "text/plain",
            content2.encodeToByteArray()
        )
        assertNotNull(text2)
        storage.delete()
    }

    @Test
    fun add_wrap(): Unit = runBlocking {
        val storage = newStorage()
        val packetSize = 1000
        val maxData = 1000
        val fid = TestEnv.createContent(
            storage, "test.bin", OCTET_MIME_TYPE,
            TestEnv.getRandomBytes(maxData * packetSize)
        )

        assertNotNull(fid)
        assertTrue(fid.name().isNotEmpty())

        val channel = storage.channel(fid)
        val bytes = channel.readAllBytes()
        assertEquals(bytes.size.toLong(), fid.size())
        storage.delete()
    }


    @Test
    fun add(): Unit = runBlocking {
        val packetSize = 1000
        val maxData = 1000

        val storage = newStorage()
        val fid = TestEnv.createContent(
            storage, "test.bin", OCTET_MIME_TYPE,
            TestEnv.getRandomBytes(maxData * packetSize)
        )


        assertNotNull(fid)
        assertEquals(fid.mimeType(), OCTET_MIME_TYPE)

        val channel = storage.channel(fid)
        assertEquals(fid.size(), channel.readAllBytes().size.toLong())

        storage.delete()
    }


    @Test
    fun add_wrap_small(): Unit = runBlocking {
        val packetSize = 200
        val maxData = 1000

        val storage = newStorage()
        val fid = TestEnv.createContent(
            storage, "test.bin", OCTET_MIME_TYPE,
            TestEnv.getRandomBytes(maxData * packetSize)
        )


        val channel = storage.channel(fid)
        assertEquals(fid.size(), channel.readAllBytes().size.toLong())

        storage.delete()
    }

    @Test
    fun createFile(): Unit = runBlocking {
        val packetSize = 200
        val maxData = 1000

        val storage = newStorage()
        val fid = TestEnv.createContent(
            storage, "test.bin", OCTET_MIME_TYPE,
            TestEnv.getRandomBytes(maxData * packetSize)
        )


        val channel = storage.channel(fid)
        assertEquals(channel.readAllBytes().size.toLong(), fid.size())
        storage.delete()
    }


    @Test
    fun test_inputStream(): Unit = runBlocking {
        val storage = newStorage()
        val text = "moin zehn"
        val node = storage.storeText(text)
        assertTrue(storage.hasBlock(node.cid()))

        val bytes = storage.fetchData(node)
        assertNotNull(bytes)
        assertEquals(bytes.size, text.length)
        assertEquals(text, bytes.decodeToString())
        storage.delete()
    }


    @Test
    fun test_inputStreamBig(): Unit = runBlocking {
        val storage = newStorage()
        val text = TestEnv.getRandomBytes((splitterSize() * 2) - 50)
        val fid = TestEnv.createContent(storage, "random.bin", OCTET_MIME_TYPE, text)
        val channel = storage.channel(fid)
        assertTrue(text.contentEquals(channel.readAllBytes()))
        storage.delete()
    }

    @Test
    fun test_reader(): Unit = runBlocking {
        val storage = newStorage()
        val text = "0123456789 jjjjjjjj"

        val fid =
            TestEnv.createContent(storage, "text.txt", OCTET_MIME_TYPE, text.encodeToByteArray())
        assertTrue(storage.hasBlock(fid.cid()))

        val channel = createChannel(fid, storage)

        channel.seek(0)
        channel.next()?.buffered().use { buffer ->
            assertNotNull(buffer)
            assertEquals(text, buffer.readByteArray().decodeToString())
        }

        var pos = 11
        channel.seek(pos.toLong())
        channel.next()!!.buffered().use { buffer ->
            assertEquals(text.substring(pos), buffer.readByteArray().decodeToString())
        }

        pos = 5
        channel.seek(pos.toLong())
        channel.next()!!.buffered().use { buffer ->
            assertEquals(text.substring(pos), buffer.readByteArray().decodeToString())
        }
        storage.delete()
    }

    @Test
    fun test_readerBig(): Unit = runBlocking {
        val split = splitterSize()
        val storage = newStorage()

        val root = storage.root()
        assertNotNull(root)
        assertEquals(root.size(), 0)

        val text = TestEnv.getRandomBytes((split * 2) - 50)
        val fid = TestEnv.createContent(storage, "random.bin", OCTET_MIME_TYPE, text)
        storage.root(fid)
        val newRoot = storage.root()
        assertNotNull(newRoot)
        assertEquals(newRoot.size(), fid.size())

        assertTrue(storage.hasBlock(fid.cid()))

        val channel = storage.channel(fid)

        channel.seek(0)
        channel.next()!!.buffered().use { buffer ->
            val data = buffer.readByteArray()
            assertEquals(split, data.size)
        }
        channel.next()!!.buffered().use { buffer ->
            val data = buffer.readByteArray()
            assertEquals(split - 50, data.size)
        }

        var pos = split + 50
        channel.seek(pos.toLong())
        channel.next()!!.buffered().use { buffer ->
            val data = buffer.readByteArray()
            assertEquals(split - 100, data.size)
            assertEquals(0, buffer.remaining.toInt())
        }



        pos = split - 50
        channel.seek(pos.toLong())
        channel.next()!!.buffered().use { buffer ->
            val data = buffer.readByteArray()
            assertEquals(50, data.size)
            assertEquals(0, buffer.remaining.toInt())
        }
        storage.delete()
    }


    @Test
    fun createRawText(): Unit = runBlocking {
        val storage = newStorage()

        val name = "Домашняя страница"
        val node = storage.storeText(name)
        assertNotNull(node)

        assertEquals(storage.fetchText(node), name)
        storage.delete()
    }


}