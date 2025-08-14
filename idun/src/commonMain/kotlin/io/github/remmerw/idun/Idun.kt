package io.github.remmerw.idun

import com.eygraber.uri.Uri
import io.github.remmerw.borr.Keys
import io.github.remmerw.borr.PeerId
import io.github.remmerw.borr.decode58
import io.github.remmerw.borr.encode58
import io.github.remmerw.borr.generateKeys
import io.github.remmerw.buri.BEString
import io.github.remmerw.dagr.Acceptor
import io.github.remmerw.dagr.ClientConnection
import io.github.remmerw.dagr.Dagr
import io.github.remmerw.dagr.Data
import io.github.remmerw.dagr.connectDagr
import io.github.remmerw.dagr.newDagr
import io.github.remmerw.idun.core.Fid
import io.github.remmerw.idun.core.RAW
import io.github.remmerw.idun.core.Raw
import io.github.remmerw.idun.core.concat
import io.github.remmerw.idun.core.createRaw
import io.github.remmerw.idun.core.decode
import io.github.remmerw.idun.core.decodeNode
import io.github.remmerw.idun.core.encoded
import io.github.remmerw.idun.core.newSignature
import io.github.remmerw.idun.core.removeNode
import io.github.remmerw.idun.core.storeSource
import io.github.remmerw.idun.core.verifySignature
import io.github.remmerw.nott.MemoryStore
import io.github.remmerw.nott.Store
import io.github.remmerw.nott.defaultBootstrap
import io.github.remmerw.nott.newNott
import io.github.remmerw.nott.nodeId
import io.github.remmerw.nott.requestGet
import io.github.remmerw.nott.requestPut
import io.github.remmerw.nott.sha1
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.io.Buffer
import kotlinx.io.RawSink
import kotlinx.io.RawSource
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.files.SystemTemporaryDirectory
import kotlinx.io.readByteArray
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.AtomicLong
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.concurrent.atomics.incrementAndFetch
import kotlin.concurrent.withLock
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

internal const val RESOLVE_TIMEOUT: Int = 60
internal const val TIMEOUT: Int = 10

internal const val MAX_SIZE: Int = 65536 // Note same as dagr Settings


class Idun internal constructor(
    private val keys: Keys,
    private val store: Store
) {
    @OptIn(ExperimentalAtomicApi::class)
    private val reservations = AtomicInt(0)


    private val scope = CoroutineScope(Dispatchers.IO)
    private var dagr: Dagr? = null

    suspend fun startup(port: Int = 0, storage: Storage) {
        dagr = newDagr(port, TIMEOUT, object : Acceptor {
            override suspend fun request(request: Long): Data {
                return storage.getData(request)
            }
        })
    }

    private val reachable: MutableMap<PeerId, InetSocketAddress> = ConcurrentHashMap()

    fun reachable(peerId: PeerId, address: InetSocketAddress) {
        reachable.put(peerId, address)
    }

    @OptIn(ExperimentalAtomicApi::class)
    private suspend fun resolveConnection(target: PeerId): ClientConnection {

        val addresses = resolveAddresses(target, RESOLVE_TIMEOUT)

        val done: AtomicReference<ClientConnection?> = AtomicReference(null)

        withContext(Dispatchers.IO) {
            addresses.forEach { address ->
                launch {
                    try {
                        val connection = connectDagr(address, TIMEOUT)
                        if (connection != null) {
                            reachable.put(target, address)
                            // done
                            done.store(connection)
                            coroutineContext.cancelChildren()
                        }
                    } catch (throwable: Throwable) {
                        debug(throwable)
                    }
                }
            }
        }


        return done.load() ?: throw Exception("No connection established")
    }


    private suspend fun connect(peerId: PeerId): ClientConnection {
        var connection: ClientConnection? = null
        val address = reachable[peerId]
        if (address != null) {
            connection = connectDagr(address, TIMEOUT)
        }
        if (connection != null && !connection.isClosed) {
            return connection
        }
        return resolveConnection(peerId)

    }

    fun localPort(): Int {
        return if (dagr != null) {
            dagr!!.localPort()
        } else {
            -1
        }
    }


    fun publishedAddresses(): List<InetSocketAddress> {
        val inetAddresses: MutableSet<InetSocketAddress> = mutableSetOf()

        try {
            if (localPort() > 0) {
                val interfaces = NetworkInterface.getNetworkInterfaces()
                for (networkInterface in interfaces) {
                    if (networkInterface.isUp) {
                        val addresses = networkInterface.inetAddresses
                        for (inetAddress in addresses) {
                            if (!inetAddress.isAnyLocalAddress
                                && !inetAddress.isLinkLocalAddress
                                && !inetAddress.isSiteLocalAddress
                                && !inetAddress.isLoopbackAddress
                                && !inetAddress.isMulticastAddress
                            ) {
                                inetAddresses.add(
                                    InetSocketAddress(
                                        inetAddress, localPort()
                                    )
                                )
                            }
                        }
                    }
                }
            }
        } catch (throwable: Throwable) {
            debug(throwable)
        }
        return inetAddresses.toList()
    }


    @OptIn(ExperimentalAtomicApi::class)
    suspend fun resolveAddresses(peerId: PeerId, timeout: Int): List<InetSocketAddress> {
        val bootstrap: MutableSet<InetSocketAddress> = mutableSetOf()
        bootstrap.addAll(defaultBootstrap())
        bootstrap.addAll(store.addresses(25))

        val target = sha1(peerId.hash)

        val lastSec = AtomicLong(0)
        val increment = AtomicInt(0)
        val done = AtomicBoolean(false)
        val result: AtomicReference<List<InetSocketAddress>?> = AtomicReference(null)

        withTimeoutOrNull(timeout.toLong() * 1000) {
            val nott = newNott(nodeId = nodeId(), port = 0, bootstrap = bootstrap)
            try {
                val channel = requestGet(nott, target) {
                    if (done.load()) {
                        0 // done
                    } else {
                        5000
                    }
                }

                for (data in channel) {

                    try {
                        val seq = data.seq
                        val v = data.v
                        val sig = data.sig
                        val k = data.k



                        require(k.contentEquals(peerId.hash)) {
                            "invalid public key (not the expected one)"
                        }

                        val content = (v as BEString).toByteArray()


                        verifySignature(peerId, seq, content, sig)

                        val addresses = decode(content)
                        require(addresses.isNotEmpty()) { "empty addresses (not allowed)" }


                        val last = lastSec.load()
                        if (seq > last) {
                            lastSec.store(seq)
                            increment.store(0)
                            result.store(addresses)
                        } else if (seq == last) {
                            val value = increment.incrementAndFetch()
                            if (value > 3) {
                                done.store(true)
                            }
                        }
                    } catch (throwable: Throwable) {
                        debug(throwable)
                    }
                }
            } catch (_: CancellationException) {
            } finally {
                nott.shutdown()
            }
        }

        return result.load() ?: throw Exception("No address resolved")
    }

    fun keys(): Keys {
        return keys
    }


    @OptIn(ExperimentalAtomicApi::class)
    fun numReservations(): Int {
        return reservations.load()
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


        val uri = Uri.parse(request)
        val cid = uri.extractCid()
        val peerId = uri.extractPeerId()

        connect(peerId).use { connection ->
            val buffer = Buffer()
            connection.request(cid, buffer)
            val node = decodeNode(cid, buffer)

            val size = node.size

            var totalRead = 0L
            var remember = 0
            if (node is Raw) {
                val buffer = Buffer()
                buffer.write(node.data)
                buffer.skip(offset)
                val totalRead: Long = buffer.size
                rawSink.write(buffer, totalRead)

                val percent = ((totalRead * 100.0f) / size).toInt()
                progress.invoke(percent / 100.0f)
            } else {
                node as Fid

                val links = node.links

                val div = offset.floorDiv(splitterSize())

                require(div < Int.MAX_VALUE) { "Invalid number of links" }

                val index = div.toInt()

                var left = offset.mod(splitterSize())

                require(left + (index * splitterSize().toLong()) == offset) {
                    "Wrong calculation of offset"
                }

                for (i in index..(links - 1)) {

                    val link = i + 1 + node.cid
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

    fun peerId(): PeerId {
        return keys.peerId
    }


    @OptIn(ExperimentalAtomicApi::class, ExperimentalTime::class)
    suspend fun publishAddresses(
        addresses: List<InetSocketAddress>,
        maxPublifications: Int,
        timeout: Int
    ) {

        val bootstrap: MutableSet<InetSocketAddress> = mutableSetOf()
        bootstrap.addAll(defaultBootstrap())
        bootstrap.addAll(store.addresses(25))

        reservations.store(0)


        var data = byteArrayOf()
        for (address in addresses) {
            val encoded = address.encoded()
            data = concat(data, encoded)
        }

        val v = BEString(data)
        val cas: Long? = null
        val seq: Long = Clock.System.now().toEpochMilliseconds()
        val sig = newSignature(keys(), seq, data)
        val k = keys().peerId.hash
        val target = sha1(k)


        withTimeoutOrNull(timeout.toLong() * 1000) {
            val nott = newNott(nodeId = nodeId(), port = 0, bootstrap = bootstrap)
            try {
                val channel = requestPut(
                    nott, target, v, cas, k, null, seq, sig
                ) {
                    if (reservations.load() >= maxPublifications) {
                        0 // done
                    } else {
                        5000 // wait 5 sec and put
                    }
                }

                for (address in channel) {
                    debug("put to $address")
                    reservations.incrementAndFetch()
                }
            } catch (_: CancellationException) {

            } finally {
                nott.shutdown()
            }
        }

    }


    suspend fun fetchRaw(peerId: PeerId, cid: Long): ByteArray {

        connect(peerId).use { connection ->
            val buffer = Buffer()
            connection.request(cid, buffer)
            val type: Byte = buffer.readByte()

            require(type == RAW) { "cid does not reference a raw node" }
            return buffer.readByteArray()
        }

    }


    fun shutdown() {
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
    store: Store = MemoryStore()
): Idun {
    return Idun(keys, store)
}

internal const val MAX_CHARS_SIZE = 4096
private const val SPLITTER_SIZE = Short.MAX_VALUE

fun splitterSize(): Int {
    return SPLITTER_SIZE.toInt()
}

interface Node {
    val cid: Long
    val size: Long
    val name: String
    val mimeType: String
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

    internal fun getData(cid: Long): Data {
        val file = path(cid)
        require(SystemFileSystem.exists(file)) { "Block does not exists" }
        val size = SystemFileSystem.metadataOrNull(file)!!.size

        return Data(SystemFileSystem.source(file), size.toInt())
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
                buffer.write(node.data)
                val totalRead: Long = node.size
                sink.write(buffer, totalRead)

            } else {
                node as Fid
                val links = node.links

                repeat(links) { i ->
                    val link = i + 1 + node.cid
                    transferBlock(sink, link)
                }
            }
        }
    }

    internal fun readByteArray(node: Node): ByteArray {
        if (node is Raw) {
            return node.data
        } else {
            node as Fid
            val links = node.links
            val sink = Buffer()
            repeat(links) { i ->
                val link = i + 1 + node.cid
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
        cid = node.cid,
        name = node.name,
        mimeType = node.mimeType,
        size = node.size
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


fun debug(message: String) {
    if (DEBUG) {
        println(message)
    }
}

private const val DEBUG: Boolean = false
private const val ERROR: Boolean = true
