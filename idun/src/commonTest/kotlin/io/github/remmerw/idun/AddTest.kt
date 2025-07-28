package io.github.remmerw.idun

import io.github.remmerw.idun.core.OCTET_MIME_TYPE
import kotlinx.coroutines.runBlocking
import kotlinx.io.Buffer
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
                val buffer = Buffer()
                storage.fetchBlock(buffer, node.cid()) // closed exception expected
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
            TestEnv.randomBytes(maxData * packetSize)
        )

        assertNotNull(fid)
        assertTrue(fid.name().isNotEmpty())

        val channel = storage.channel(fid)
        val bytes = channel.readBytes()
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
            TestEnv.randomBytes(maxData * packetSize)
        )


        assertNotNull(fid)
        assertEquals(fid.mimeType(), OCTET_MIME_TYPE)

        val channel = storage.channel(fid)
        assertEquals(fid.size(), channel.readBytes().size.toLong())

        storage.delete()
    }


    @Test
    fun add_wrap_small(): Unit = runBlocking {
        val packetSize = 200
        val maxData = 1000

        val storage = newStorage()
        val fid = TestEnv.createContent(
            storage, "test.bin", OCTET_MIME_TYPE,
            TestEnv.randomBytes(maxData * packetSize)
        )


        val channel = storage.channel(fid)
        assertEquals(fid.size(), channel.readBytes().size.toLong())

        storage.delete()
    }

    @Test
    fun createFile(): Unit = runBlocking {
        val packetSize = 200
        val maxData = 1000

        val storage = newStorage()
        val fid = TestEnv.createContent(
            storage, "test.bin", OCTET_MIME_TYPE,
            TestEnv.randomBytes(maxData * packetSize)
        )


        val channel = storage.channel(fid)
        assertEquals(channel.readBytes().size.toLong(), fid.size())
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
        val text = TestEnv.randomBytes((splitterSize() * 2) - 50)
        val fid = TestEnv.createContent(storage, "random.bin", OCTET_MIME_TYPE, text)
        val channel = storage.channel(fid)
        assertTrue(text.contentEquals(channel.readBytes()))
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
        val buffer = Buffer()
        channel.seek(0)
        channel.next(buffer)
        assertNotNull(buffer)
        assertEquals(text, buffer.readByteArray().decodeToString())


        buffer.clear()
        var pos = 11
        channel.seek(pos.toLong())
        channel.next(buffer)
        assertEquals(text.substring(pos), buffer.readByteArray().decodeToString())

        buffer.clear()
        pos = 5
        channel.seek(pos.toLong())
        channel.next(buffer)
        assertEquals(text.substring(pos), buffer.readByteArray().decodeToString())

        storage.delete()
    }

    @Test
    fun test_readerBig(): Unit = runBlocking {

        val storage = newStorage()

        val root = storage.root()
        assertNotNull(root)
        assertEquals(root.size, 0)

        val text = TestEnv.randomBytes((splitterSize() * 2) - 50)

        storage.root(text)
        val newRoot = storage.root()
        assertNotNull(newRoot)
        assertEquals(newRoot.size, text.size)

        val node = storage.info()
        var channel = storage.channel(node)

        val buffer = Buffer()
        channel.seek(0)
        assertTrue(channel.next(buffer) > 0)
        var data = buffer.readByteArray()
        assertEquals(text.size, data.size)


        // test
        buffer.clear()
        channel = storage.channel(node)
        channel.seek(50)
        assertTrue(channel.next(buffer) > 0)
        data = buffer.readByteArray()
        assertEquals(text.size - 50, data.size)


        // test
        buffer.clear()
        channel = storage.channel(node)
        channel.seek(100)
        assertTrue(channel.next(buffer) > 0)
        data = buffer.readByteArray()
        assertEquals(text.size - 100, data.size)
        assertEquals(0, buffer.size.toInt())



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