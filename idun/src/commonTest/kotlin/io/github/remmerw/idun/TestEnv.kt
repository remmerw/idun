package io.github.remmerw.idun

import io.github.remmerw.borr.PeerId
import io.github.remmerw.dagr.Data
import kotlinx.io.Buffer
import kotlinx.io.RawSink
import kotlinx.io.RawSource
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.files.SystemTemporaryDirectory
import kotlinx.io.readByteArray
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.atomics.AtomicLong
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.concurrent.atomics.incrementAndFetch
import kotlin.concurrent.withLock
import kotlin.random.Random
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

const val ITERATIONS: Int = 4096

fun randomBytes(number: Int): ByteArray {
    val bytes = ByteArray(number)
    Random.nextBytes(bytes)
    return bytes
}

fun randomLong(): Long {
    return Random.nextLong()
}

fun createContent(storage: FileStorage, name: String, data: ByteArray): Node {
    val temp = tempFile(name)
    SystemFileSystem.sink(temp).buffered().use { source ->
        source.write(data)
    }

    val node = storage.storeFile(temp, OCTET_MIME_TYPE)

    SystemFileSystem.delete(temp)
    return node
}

fun createContent(storage: FileStorage, iteration: Int): Node {
    val temp = tempFile()
    SystemFileSystem.sink(temp).buffered().use { source ->
        repeat(iteration) {
            source.write(randomBytes(UShort.MAX_VALUE.toInt()))
        }
    }

    val node = storage.storeFile(temp, OCTET_MIME_TYPE)

    SystemFileSystem.delete(temp)
    return node
}


fun loopbackAddress(port: Int): InetSocketAddress {
    return InetSocketAddress(InetAddress.getLoopbackAddress(), port)
}

fun createContent(
    storage: FileStorage,
    name: String,
    mimeType: String,
    data: ByteArray
): Node {
    val temp = tempFile(name)
    SystemFileSystem.sink(temp).buffered().use { source ->
        source.write(data)
    }

    val node = storage.storeFile(temp, mimeType)

    SystemFileSystem.delete(temp)
    return node

}


data class Node(
    val cid: Long, val size: Long,
    val name: String = UNNAMED,
    val mimeType: String = OCTET_MIME_TYPE
) {
    init {
        require(size >= 0) { "Invalid size" }
        require(name.length < MAX_CHARS_SIZE) { "invalid name size" }
    }
}


@OptIn(ExperimentalAtomicApi::class)
data class FileStorage(private val directory: Path) : Storage {
    private val lock = ReentrantLock()

    @OptIn(ExperimentalAtomicApi::class)
    private val cid = AtomicLong(0L)


    init {
        var maxCid = 0L
        val files = SystemFileSystem.list(directory())
        for (file in files) {
            try {
                val res = file.name.hexToLong()
                if (res > maxCid) {
                    maxCid = res
                }
            } catch (throwable: Throwable) {
                debug(throwable)
            }
        }
        cid.store(maxCid)
    }

    internal fun currentCid(): Long {
        return cid.load()
    }

    fun directory(): Path {
        return directory
    }

    fun reset() {
        cleanupDirectory(directory)
        cid.store(0L)
    }

    fun delete() {
        reset()
        SystemFileSystem.delete(directory, false)
    }

    override suspend fun getData(cid: Long, offset: Long): Data {
        val file = path(cid)
        require(SystemFileSystem.exists(file)) { "Block does not exists" }
        val size = SystemFileSystem.metadataOrNull(file)!!.size
        val length = size - offset
        val source = SystemFileSystem.source(file).buffered()
        source.skip(offset)
        return Data(source, length)
    }

    fun transferBlock(sink: RawSink, cid: Long): Int {
        val file = path(cid)
        require(SystemFileSystem.exists(file)) { "Block does not exists" }

        SystemFileSystem.source(file).buffered().use { source ->
            return source.transferTo(sink).toInt()
        }
    }


    @OptIn(ExperimentalStdlibApi::class)
    private fun path(cid: Long): Path {
        return Path(directory, cid.toHexString())
    }

    // Note: remove the cid block (add all links blocks recursively)
    fun delete(node: Node) {
        val file = path(node.cid)
        SystemFileSystem.delete(file, false)
    }

    fun storeData(data: ByteArray): Node {
        require(data.size <= MAX_SIZE) { "Exceeds limit of data length" }
        val buffer = Buffer()
        buffer.write(data)
        return storeSource(buffer, UNNAMED, OCTET_MIME_TYPE)
    }

    fun storeText(text: String): Node {
        val data = text.encodeToByteArray()
        require(data.size <= MAX_SIZE) { "Exceeds limit of data length" }
        val buffer = Buffer()
        buffer.write(data)
        return storeSource(buffer, UNNAMED, PLAIN_TEXT)
    }

    fun storeFile(path: Path, mimeType: String): Node {
        require(SystemFileSystem.exists(path)) { "Path does not exists" }
        val metadata = SystemFileSystem.metadataOrNull(path)
        checkNotNull(metadata) { "Path has no metadata" }
        require(metadata.isRegularFile) { "Path is not a regular file" }
        require(mimeType.isNotBlank()) { "MimeType is blank" }
        val size = SystemFileSystem.metadataOrNull(path)!!.size
        val cid = cid.incrementAndFetch()
        val sink = path(cid)
        SystemFileSystem.source(path).use { source ->
            SystemFileSystem.sink(sink, false).buffered().use { sink ->
                sink.transferFrom(source)
            }
            return Node(cid, size, path.name, mimeType)
        }
    }

    fun storeSource(source: RawSource, name: String, mimeType: String): Node {
        lock.withLock {
            require(name.isNotBlank()) { "Name is blank" }
            require(mimeType.isNotBlank()) { "MimeType is blank" }
            val cid = cid.incrementAndFetch()
            val sink = path(cid)
            SystemFileSystem.sink(sink, false).buffered().use { sink ->
                sink.transferFrom(source)
            }
            val size = SystemFileSystem.metadataOrNull(sink)!!.size
            return Node(cid, size, name, mimeType)
        }
    }

    fun transferTo(node: Node, path: Path) {
        SystemFileSystem.sink(path, false).use { sink ->
            transferBlock(sink, node.cid)
        }
    }

    internal fun readByteArray(node: Node): ByteArray {
        val sink = Buffer()
        transferBlock(sink, node.cid)
        return sink.readByteArray()
    }


    fun fetchData(node: Node): ByteArray {
        return readByteArray(node)
    }

    fun fetchText(node: Node): String {
        return fetchData(node).decodeToString()
    }
}


fun cleanupDirectory(dir: Path) {
    if (SystemFileSystem.exists(dir)) {
        val files = SystemFileSystem.list(dir)
        for (file in files) {
            SystemFileSystem.delete(file)
        }
    }
}

@OptIn(ExperimentalUuidApi::class)
internal fun tempFile(name: String = Uuid.random().toHexString()): Path {
    return Path(SystemTemporaryDirectory, name)
}

fun newStorage(directory: Path): FileStorage {
    SystemFileSystem.createDirectories(directory)
    require(
        SystemFileSystem.metadataOrNull(directory)?.isDirectory == true
    ) {
        "Path is not a directory."
    }
    return FileStorage(directory)
}

@OptIn(ExperimentalUuidApi::class)
private fun tempDirectory(): Path {
    val path = Path(SystemTemporaryDirectory, Uuid.random().toHexString())
    SystemFileSystem.createDirectories(path)
    return path
}

fun newStorage(): FileStorage {
    return newStorage(tempDirectory())
}


fun pnsUri(peerId: PeerId, node: Node): String {
    return pnsUri(
        peerId,
        cid = node.cid,
        name = node.name,
        mimeType = node.mimeType,
        size = node.size
    )
}