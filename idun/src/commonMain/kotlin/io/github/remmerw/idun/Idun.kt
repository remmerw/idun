package io.github.remmerw.idun

import com.eygraber.uri.Uri
import io.github.remmerw.asen.HolePunch
import io.github.remmerw.asen.MemoryPeers
import io.github.remmerw.asen.PeerStore

import io.github.remmerw.asen.Peeraddr
import io.github.remmerw.asen.bootstrap
import io.github.remmerw.asen.newAsen
import io.github.remmerw.borr.Keys
import io.github.remmerw.borr.PeerId
import io.github.remmerw.borr.decode58
import io.github.remmerw.borr.encode58
import io.github.remmerw.borr.generateKeys
import io.github.remmerw.dagr.Acceptor
import io.github.remmerw.dagr.Dagr
import io.github.remmerw.dagr.Writer
import io.github.remmerw.dagr.newDagr
import io.github.remmerw.idun.core.Connector
import io.github.remmerw.idun.core.Fid
import io.github.remmerw.idun.core.Raw
import io.github.remmerw.idun.core.Type
import io.github.remmerw.idun.core.createRaw
import io.github.remmerw.idun.core.decodeNode
import io.github.remmerw.idun.core.decodeType
import io.github.remmerw.idun.core.removeNode
import io.github.remmerw.idun.core.storeSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
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
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.atomics.AtomicLong
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.concurrent.atomics.incrementAndFetch
import kotlin.concurrent.withLock
import kotlin.random.Random
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

internal const val RESOLVE_TIMEOUT: Int = 60
internal const val TIMEOUT: Int = 10

internal const val MAX_SIZE: Int = 65536 // Note same as dagr Settings

class Idun internal constructor(
    keys: Keys,
    bootstrap: List<Peeraddr>,
    peerStore: PeerStore
) {
    private val observable: MutableSet<InetAddress> = ConcurrentHashMap.newKeySet()

    internal fun observable(): List<InetSocketAddress> {
        return observable.map { address ->
            InetSocketAddress(address, localPort())
        }
    }

    private val asen = newAsen(keys, bootstrap, peerStore, object : HolePunch {
        override fun invoke(
            peerId: PeerId,
            addresses: List<InetSocketAddress>
        ) {

            // punching only non local addresses (and not the same inet address)
            val punching = addresses.filter { address ->
                val inet = address.address
                !(observable.contains(inet) || inet.isAnyLocalAddress || inet.isLinkLocalAddress
                        || inet.isSiteLocalAddress || inet.isLoopbackAddress)
            }


            scope.launch {

                withTimeoutOrNull(1000) {
                    punching.forEach { remoteAddress ->
                        try {
                            dagr?.punching(remoteAddress)
                        } catch (throwable: Throwable) {
                            debug(throwable)
                        }
                    }
                    delay(Random.nextLong(75, 150))
                }

            }
        }
    })
    private val scope = CoroutineScope(Dispatchers.IO)
    private var dagr: Dagr? = null
    private val connector = Connector()
    private val mutex = Mutex()

    suspend fun startup(port: Int = 0, storage: Storage) {
        dagr = newDagr(port, object : Acceptor {
            override suspend fun request(writer: Writer, request: Long) {
                val sink = Buffer()
                storage.getBlock(sink, request)
                writer.writeBuffer(sink)
            }
        })
    }

    fun localPort(): Int {
        return if (dagr != null) {
            dagr!!.localPort()
        } else {
            -1
        }
    }

    suspend fun observedAddresses(): List<InetSocketAddress> {
        val observed = asen.observedAddresses()
        observable.clear()
        observable.addAll(observed)

        return observable()
    }

    suspend fun resolveAddresses(target: PeerId, timeout: Long): List<InetSocketAddress> {
        return asen.resolveAddresses(target, timeout)
    }

    fun keys(): Keys {
        return asen.keys()
    }


    fun numReservations(): Int {
        return asen.numReservations()
    }

    fun numIncomingConnections(): Int {
        return if (dagr != null) {
            dagr!!.numIncomingConnections()
        } else {
            0
        }
    }


    suspend fun transferTo(
        rawSink: RawSink, request: String,
        offset: Long = 0, progress: (Float) -> Unit = {}
    ) {
        require(offset >= 0) { "Wrong offset" }

        mutex.withLock {
            val uri = Uri.parse(request)
            val cid = uri.extractCid()
            val peerId = uri.extractPeerId()

            connector.connect(this, peerId).use { connection ->
                val buffer = Buffer()
                connection.request(cid, buffer)
                val node = decodeNode(cid, buffer)

                val size = node.size()

                var totalRead = 0L
                var remember = 0
                if (node is Raw) {
                    val buffer = Buffer()
                    buffer.write(node.data())
                    buffer.skip(offset)
                    val totalRead: Long = buffer.size
                    rawSink.write(buffer, totalRead)

                    val percent = ((totalRead * 100.0f) / size).toInt()
                    progress.invoke(percent / 100.0f)
                } else {
                    node as Fid

                    val links = node.links()

                    val div = offset.floorDiv(splitterSize())

                    require(div < Int.MAX_VALUE) { "Invalid number of links" }

                    val index = div.toInt()

                    var left = offset.mod(splitterSize())

                    require(left + (index * splitterSize().toLong()) == offset) {
                        "Wrong calculation of offset"
                    }

                    for (i in index..(links - 1)) {

                        val link = i + 1 + node.cid()
                        if (left > 0) {
                            val buffer = Buffer()
                            connection.request(link, buffer)
                            buffer.skip(left.toLong())
                            totalRead += buffer.transferTo(rawSink)
                            left = 0
                        } else {
                            totalRead += connection.request(link, rawSink)
                        }

                        if (totalRead > 0) {
                            val percent = ((totalRead * 100.0f) / size).toInt()
                            if (percent > remember) {
                                remember = percent
                                progress.invoke(percent / 100.0f)
                            }
                        }
                    }
                }
            }
        }
    }


    fun peerId(): PeerId {
        return asen.peerId()
    }


    suspend fun publishAddresses(
        addresses: List<InetSocketAddress>,
        maxPublifications: Int,
        timeout: Int
    ) {
        return asen.makeReservations(addresses, maxPublifications, timeout)
    }


    internal suspend fun fetchRaw(peerId: PeerId, cid: Long): ByteArray {
        mutex.withLock {
            connector.connect(this, peerId).use { connection ->
                val buffer = Buffer()
                connection.request(cid, buffer)
                val type: Type = decodeType(buffer.readByte())

                require(type == Type.RAW) { "cid does not reference a raw node" }
                return buffer.readByteArray()
            }
        }
    }

    // this is just for testing purpose
    fun reachable(peerId: PeerId, address: InetSocketAddress) {
        connector.reachable(peerId, address)
    }


    fun shutdown() {

        try {
            asen.shutdown()
        } catch (throwable: Throwable) {
            debug(throwable)
        }

        try {
            dagr?.shutdown()
        } catch (throwable: Throwable) {
            debug(throwable)
        }

        try {
            scope.cancel()
        } catch (throwable: Throwable) {
            debug(throwable)
        }
    }


}


fun newIdun(
    keys: Keys = generateKeys(),
    bootstrap: List<Peeraddr> = bootstrap(),
    peerStore: PeerStore = MemoryPeers()
): Idun {
    return Idun(keys, bootstrap, peerStore)
}

internal const val MAX_CHARS_SIZE = 4096
private const val SPLITTER_SIZE = Short.MAX_VALUE

fun splitterSize(): Int {
    return SPLITTER_SIZE.toInt()
}

interface Node {
    fun cid(): Long
    fun size(): Long
    fun name(): String
    fun mimeType(): String
}

@OptIn(ExperimentalAtomicApi::class)
data class Storage(private val directory: Path) {
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

    fun hasBlock(cid: Long): Boolean {
        return SystemFileSystem.exists(path(cid))
    }

    internal fun getBlock(sink: RawSink, cid: Long) {
        val file = path(cid)
        require(SystemFileSystem.exists(file)) { "Block does not exists" }
        val size = SystemFileSystem.metadataOrNull(file)!!.size
        val length = Buffer()
        length.writeInt(size.toInt())
        sink.write(length, Int.SIZE_BYTES.toLong())
        SystemFileSystem.source(file).buffered().use { source ->
            source.transferTo(sink)
        }
    }

    fun transferBlock(sink: RawSink, cid: Long): Int {
        val file = path(cid)
        require(SystemFileSystem.exists(file)) { "Block does not exists" }

        SystemFileSystem.source(file).buffered().use { source ->
            return source.transferTo(sink).toInt()
        }
    }

    fun storeBlock(cid: Long, buffer: Buffer) {
        require(buffer.size <= MAX_SIZE) { "Exceeds limit of data length" }
        val file = path(cid)
        SystemFileSystem.sink(file, false).use { sink ->
            sink.write(buffer, buffer.size)
        }
    }


    @OptIn(ExperimentalStdlibApi::class)
    private fun path(cid: Long): Path {
        return Path(directory, cid.toHexString())
    }

    fun deleteBlock(cid: Long) {
        val file = path(cid)
        SystemFileSystem.delete(file, false)
    }


    fun info(cid: Long): Node {
        val sink = Buffer()
        transferBlock(sink, cid)
        return decodeNode(cid, sink)
    }

    // Note: remove the cid block (add all links blocks recursively)
    fun delete(node: Node) {
        removeNode(this, node)
    }

    fun storeData(data: ByteArray): Node {
        require(data.size <= MAX_SIZE) { "Exceeds limit of data length" }
        lock.withLock {
            return createRaw(this, cid.incrementAndFetch(), data)
        }
    }

    fun storeText(data: String): Node {
        return storeData(data.encodeToByteArray())
    }

    fun storeFile(path: Path, mimeType: String): Node {
        require(SystemFileSystem.exists(path)) { "Path does not exists" }
        val metadata = SystemFileSystem.metadataOrNull(path)
        checkNotNull(metadata) { "Path has no metadata" }
        require(metadata.isRegularFile) { "Path is not a regular file" }
        require(mimeType.isNotBlank()) { "MimeType is blank" }
        SystemFileSystem.source(path).use { source ->
            return storeSource(source, path.name, mimeType)
        }
    }

    fun storeSource(source: RawSource, name: String, mimeType: String): Node {
        lock.withLock {
            require(name.isNotBlank()) { "Name is blank" }
            require(mimeType.isNotBlank()) { "MimeType is blank" }
            return storeSource(this, source, name, mimeType) {
                cid.incrementAndFetch()
            }
        }
    }

    fun transferTo(node: Node, path: Path) {
        SystemFileSystem.sink(path, false).use { sink ->

            if (node is Raw) {
                val buffer = Buffer()
                buffer.write(node.data())
                val totalRead: Long = node.data().size.toLong()
                sink.write(buffer, totalRead)

            } else {
                node as Fid
                val links = node.links()

                repeat(links) { i ->
                    val link = i + 1 + node.cid()
                    transferBlock(sink, link)
                }
            }
        }
    }

    internal fun readByteArray(node: Node): ByteArray {
        if (node is Raw) {
            return node.data()
        } else {
            node as Fid
            val links = node.links()
            val sink = Buffer()
            repeat(links) { i ->
                val link = i + 1 + node.cid()
                transferBlock(sink, link)
            }

            return sink.readByteArray()
        }
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

fun newStorage(): Storage {
    return newStorage(tempDirectory())
}

@OptIn(ExperimentalUuidApi::class)
private fun tempDirectory(): Path {
    val path = Path(SystemTemporaryDirectory, Uuid.random().toHexString())
    SystemFileSystem.createDirectories(path)
    return path
}

@OptIn(ExperimentalUuidApi::class)
internal fun tempFile(name: String = Uuid.random().toHexString()): Path {
    return Path(SystemTemporaryDirectory, name)
}


fun newStorage(directory: Path): Storage {
    SystemFileSystem.createDirectories(directory)
    require(
        SystemFileSystem.metadataOrNull(directory)?.isDirectory == true
    ) {
        "Path is not a directory."
    }
    return Storage(directory)
}

fun decodeNode(cid: Long, block: Buffer): Node {
    return decodeNode(cid, block)
}

fun Uri.extractPeerId(): PeerId {
    val host = validate(this)
    return PeerId(decode58(host))
}

private fun validate(pns: Uri): String {
    checkNotNull(pns.scheme) { "Scheme not defined" }
    require(pns.scheme == "pns") { "Scheme not pns" }
    val host = pns.host
    checkNotNull(host) { "Host not defined" }
    require(host.isNotBlank()) { "Host is empty" }
    return host
}

fun Uri.extractCid(): Long {
    var path = this.path
    if (path != null) {
        path = path.trim().removePrefix("/")
        if (path.isNotBlank()) {
            val cid = path.hexToLong()
            return cid
        }
    }
    return 0
}


fun Uri.extractSize(): Long {
    try {
        return this.getQueryParameter(SIZE)!!.toLong()
    } catch (throwable: Throwable) {
        debug(throwable)
    }
    return 0L
}

fun Uri.extractName(): String {
    try {
        return this.getQueryParameter(NAME)!!
    } catch (throwable: Throwable) {
        debug(throwable)
    }
    return ""
}


fun Uri.extractMimeType(): String {
    try {
        return this.getQueryParameter(MIME_TYPE)!!
    } catch (throwable: Throwable) {
        debug(throwable)
    }
    return ""
}

fun pnsUri(peerId: PeerId): String {
    return "pns://" + encode58(peerId.hash)
}

const val NAME = "name"
const val MIME_TYPE = "mimeType"
const val SIZE = "size"


fun pnsUri(peerId: PeerId, node: Node): String {
    return pnsUri(
        peerId,
        cid = node.cid(),
        name = node.name(),
        mimeType = node.mimeType(),
        size = node.size()
    )
}

fun pnsUri(peerId: PeerId, cid: Long, name: String, mimeType: String, size: Long): String {
    require(name.isNotBlank()) { "Name should be defined" }
    require(mimeType.isNotBlank()) { "MimeType should be defined" }
    require(size >= 0) { "No valid size. should be greater or equal zero" }
    val attributes = mutableMapOf(
        NAME to name,
        MIME_TYPE to mimeType,
        SIZE to size.toString()
    )
    return pnsUri(peerId, cid, attributes)
}


internal fun pnsUri(peerId: PeerId, cid: Long, attributes: Map<String, String>): String {
    val uri = pnsUri(peerId) + "/" + cid.toHexString()
    if (attributes.isEmpty()) {
        return uri
    }
    val builder = Uri.parse(uri).buildUpon()
    attributes.forEach { p0, p1 ->
        builder.appendQueryParameter(p0, p1)
    }
    return builder.toString()
}

fun debug(throwable: Throwable) {
    if (ERROR) {
        throwable.printStackTrace()
    }
}

private const val ERROR: Boolean = true
