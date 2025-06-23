package io.github.remmerw.idun

import io.github.remmerw.asen.Asen
import io.github.remmerw.asen.Keys
import io.github.remmerw.asen.MemoryPeers
import io.github.remmerw.asen.PeerId
import io.github.remmerw.asen.PeerStore
import io.github.remmerw.asen.Peeraddr
import io.github.remmerw.asen.generateKeys
import io.github.remmerw.asen.newAsen
import io.github.remmerw.idun.core.Connector
import io.github.remmerw.idun.core.FetchRequest
import io.github.remmerw.idun.core.Fid
import io.github.remmerw.idun.core.FidChannel
import io.github.remmerw.idun.core.Raw
import io.github.remmerw.idun.core.RawChannel
import io.github.remmerw.idun.core.createRaw
import io.github.remmerw.idun.core.decodeNode
import io.github.remmerw.idun.core.encodeCid
import io.github.remmerw.idun.core.encodePeerId
import io.github.remmerw.idun.core.encodePeeraddr
import io.github.remmerw.idun.core.extractCidFromRequest
import io.github.remmerw.idun.core.extractPeerIdFromRequest
import io.github.remmerw.idun.core.extractPeeraddrFromRequest
import io.github.remmerw.idun.core.removeNode
import io.ktor.network.selector.SelectorManager
import io.ktor.network.sockets.InetSocketAddress
import io.ktor.network.sockets.ServerSocket
import io.ktor.network.sockets.Socket
import io.ktor.network.sockets.aSocket
import io.ktor.network.sockets.isClosed
import io.ktor.network.sockets.openReadChannel
import io.ktor.network.sockets.openWriteChannel
import io.ktor.utils.io.readBuffer
import io.ktor.utils.io.writeBuffer
import io.ktor.utils.io.writeInt
import io.ktor.utils.io.writeLong
import kotlinx.atomicfu.locks.reentrantLock
import kotlinx.atomicfu.locks.withLock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.io.Buffer
import kotlinx.io.RawSource
import kotlinx.io.Sink
import kotlinx.io.Source
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.files.SystemTemporaryDirectory
import kotlinx.io.readByteArray
import kotlin.concurrent.Volatile
import kotlin.random.Random
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid


const val HTML_OK: Int = 200
internal const val RESOLVE_TIMEOUT: Int = 60
internal const val PNS = "pns"

enum class Event {
    OUTGOING_RESERVE_EVENT, INCOMING_CONNECT_EVENT
}

class Idun internal constructor(
    private val asen: Asen,
    private val events: (Event) -> Unit = {}
) {

    private val incoming: MutableSet<Socket> = mutableSetOf()
    private val lock = reentrantLock()
    private val selectorManager = SelectorManager(Dispatchers.IO)
    private val connector = Connector(selectorManager)
    private var serverSocket: ServerSocket? = null


    suspend fun runService(storage: Storage, port: Int) {

        serverSocket = aSocket(selectorManager).tcp().bind(
            InetSocketAddress("::", port)
        )

        selectorManager.launch {
            try {
                while (isActive) {

                    // accept a connection
                    val socket = serverSocket!!.accept()


                    registerIncoming(socket)
                    selectorManager.launch {
                        handleConnection(storage, socket)
                    }
                }
            } catch (_: Throwable) {
            }
        }
    }


    private suspend fun handleConnection(storage: Storage, socket: Socket) {
        try {

            val receiveChannel = socket.openReadChannel()
            val sendChannel = socket.openWriteChannel(autoFlush = false)

            while (selectorManager.isActive) {

                val buffer = receiveChannel.readBuffer(Long.SIZE_BYTES)

                val read = buffer.size
                if (read.toInt() != Long.SIZE_BYTES) {
                    break
                }

                try {
                    val cid = buffer.readLong()
                    val root = storage.root()
                    if (cid == HALO_ROOT) { // root request
                        // root cid
                        sendChannel.writeInt(Long.SIZE_BYTES)
                        sendChannel.writeLong(root.cid())

                    } else {
                        require(storage.hasBlock(cid)) { "Data not available" }
                        sendChannel.writeInt(storage.blockSize(cid))
                        storage.rawSource(cid).use { source ->
                            sendChannel.writeBuffer(source)
                        }
                    }
                    sendChannel.flush()
                } catch (throwable: Throwable) {
                    debug(throwable)
                    removeIncoming(socket)
                    socketClose(socket)
                }
            }
        } catch (throwable: Throwable) {
            debug(throwable)
        } finally {
            removeIncoming(socket)
            socketClose(socket)
        }
    }

    private fun registerIncoming(socket: Socket) {
        lock.withLock {
            incoming.add(socket)
        }
        try {
            events.invoke(Event.INCOMING_CONNECT_EVENT)
        } catch (throwable: Throwable) {
            debug(throwable) // should not occur
        }
    }

    private fun removeIncoming(socket: Socket) {
        lock.withLock {
            incoming.remove(socket)
        }
        try {
            events.invoke(Event.INCOMING_CONNECT_EVENT)
        } catch (throwable: Throwable) {
            debug(throwable) // should not occur
        }
    }

    /**
     * Find the peer addresses of given target peer ID via the relay.
     *
     * @param relay address of the relay which should be used to the relay connection
     * @param target the target peer ID which addresses should be retrieved

     * @return list of the peer addresses (usually one IPv6 address)
     */
    suspend fun findPeer(relay: Peeraddr, target: PeerId): List<Peeraddr> {
        val peeraddrs = asen.findPeer(relay, target)
        return inet6Public(peeraddrs)
    }

    /**
     * Find the peer addresses of given target peer ID via the **libp2p** relay mechanism.
     *
     * @param target the target peer ID which addresses should be retrieved
     * @param timeout in seconds
     * @return list of the peer addresses (usually one IPv6 address)
     */
    @Suppress("unused")
    suspend fun findPeer(target: PeerId, timeout: Long): List<Peeraddr> {
        val peeraddrs = asen.findPeer(target, timeout)
        return inet6Public(peeraddrs)
    }

    fun keys(): Keys {
        return asen.keys()
    }

    fun peerStore(): PeerStore {
        return asen.peerStore()
    }

    fun reservations(): List<Peeraddr> {
        return asen.reservations()
    }

    fun hasReservations(): Boolean {
        return asen.hasReservations()
    }

    fun numReservations(): Int {
        return asen.numReservations()
    }

    fun numIncomingConnections(): Int {
        return incomingConnections().size
    }

    fun incomingConnections(): List<String> {
        lock.withLock {
            val result = mutableListOf<String>()
            for (connection in incoming) {
                if (!connection.isClosed) {
                    result.add(connection.remoteAddress.toString())
                } else {
                    incoming.remove(connection)
                }
            }
            return result
        }
    }

    fun numOutgoingConnections(): Int {
        return connector.channels().size
    }

    internal suspend fun fetchRoot(request: String): String {
        val pnsChannel = connector.connect(asen, request)
        val payload = pnsChannel.request(HALO_ROOT)
        val cid = payload.readLong()
        return createRequest(pnsChannel.remotePeeraddr, cid)
    }


    @Suppress("unused")
    suspend fun response(request: String, storage: Storage? = null): Response {
        try {
            val node = info(request, storage) // is resolved
            return if (node is Fid) {
                val channel = channel(request, storage)
                contentResponse(channel, node)
            } else {
                val buffer = Buffer()
                buffer.write((node as Raw).data())

                contentResponse(RawChannel(buffer), "OK", HTML_OK)
            }
        } catch (throwable: Throwable) {
            var message = throwable.message
            if (message.isNullOrEmpty()) {
                message = "Service unavailable"
            }
            return contentResponse(RawChannel(Buffer()), message, 500)

        }
    }


    suspend fun channel(request: String, storage: Storage? = null): Channel {
        val node = info(request, storage)
        if (node is Fid) {
            val fetch = FetchRequest(asen, connector, request, storage)
            return createChannel(node, fetch)
        }
        throw Exception("Stream on directory or raw not possible")
    }

    fun peerId(): PeerId {
        return asen.peerId()
    }

    /**
     * Makes a reservation o relay nodes with the purpose that other peers can fin you via
     * the nodes peerId
     *
     * @param maxReservation number of max reservations
     * @param timeout in seconds
     */
    suspend fun makeReservations(
        peeraddrs: List<Peeraddr>,
        maxReservation: Int,
        timeout: Int,
        running: (Boolean) -> Unit = {}
    ) {
        return asen.makeReservations(peeraddrs, maxReservation, timeout, running)
    }


    suspend fun info(request: String, storage: Storage? = null): Node {
        val cid = extractCid(request)
        if (cid != null) {
            val fetch = FetchRequest(asen, connector, request, storage)
            return decodeNode(cid, fetch.fetchBlock(cid))
        } else {
            return info(fetchRoot(request), storage)
        }
    }

    suspend fun fetchData(request: String, storage: Storage? = null): ByteArray {
        val node = info(request, storage)
        if (node is Raw) {
            return node.data()
        }
        throw Exception("Uri does not reference raw node")
    }

    suspend fun reachable(peeraddr: Peeraddr): Boolean {
        try {
            connector.connect(asen, createRequest(peeraddr))
            return true
        } catch (throwable: Throwable) {
            debug(throwable)
        }
        return false
    }


    fun shutdown() {

        asen.shutdown()

        connector.shutdown()

        val connections = lock.withLock {
            incoming.toList()
        }
        connections.forEach { socket: Socket -> socketClose(socket) }

        lock.withLock {
            incoming.clear()
        }

        try {
            serverSocket?.close()
        } catch (throwable: Throwable) {
            debug(throwable)
        }

        try {
            selectorManager.close()
        } catch (throwable: Throwable) {
            debug(throwable)
        }

    }
}


fun newIdun(
    keys: Keys = generateKeys(),
    bootstrap: List<Peeraddr> = emptyList(),
    peerStore: PeerStore = MemoryPeers(),
    events: (Event) -> Unit = {}
): Idun {
    val asen = newAsen(keys, bootstrap, peerStore) {
        events.invoke(Event.OUTGOING_RESERVE_EVENT)
    }

    return Idun(asen, events)
}

internal fun socketClose(socket: Socket) {

    if (!socket.isClosed) {
        try {
            socket.close()
        } catch (throwable: Throwable) {
            debug(throwable)
        }
    }
}

private fun inet6Public(peeraddrs: List<Peeraddr>): List<Peeraddr> {
    val result = mutableListOf<Peeraddr>()
    for (peer in peeraddrs) {
        if (peer.inet6()) {
            if (!peer.isLanAddress()) {
                result.add(peer)
            }
        }
    }
    return result
}


fun createRequest(peeraddr: Peeraddr): String {
    return PNS + "://" + encodePeeraddr(peeraddr) + "@" + encodePeerId(peeraddr.peerId)
}

fun createRequest(peerId: PeerId): String {
    return PNS + "://" + encodePeerId(peerId)
}

fun createRequest(peerId: PeerId, cid: Long): String {
    return createRequest(peerId) + relativePath(cid)
}

fun createRequest(peeraddr: Peeraddr, cid: Long): String {
    return createRequest(peeraddr) + relativePath(cid)
}

fun createRequest(peerId: PeerId, node: Node): String {
    return createRequest(peerId, node.cid())
}

fun createRequest(peeraddr: Peeraddr, node: Node): String {
    return createRequest(peeraddr, node.cid())
}

fun relativePath(cid: Long): String {
    return "/" + encodeCid(cid)
}

fun extractPeerId(request: String): PeerId? {
    return extractPeerIdFromRequest(request)
}

fun extractPeeraddr(request: String): Peeraddr? {
    return extractPeeraddrFromRequest(request)
}

fun extractCid(request: String): Long? {
    return extractCidFromRequest(request)
}


interface Channel {
    fun size(): Long
    fun seek(offset: Long)
    suspend fun transferTo(sink: Sink, read: (Int) -> Unit)
    suspend fun readAllBytes(): ByteArray
    suspend fun next(): Buffer?
}

data class Response(
    val mimeType: String,
    val encoding: String,
    val status: Int,
    val reason: String,
    val headers: Map<String, String>,
    val channel: Channel
)

const val MAX_CHARS_SIZE = 4096
const val SPLITTER_SIZE = UShort.MAX_VALUE
const val HALO_ROOT = 0L


interface Node {
    fun cid(): Long
    fun size(): Long
    fun name(): String
    fun mimeType(): String
}

interface Storage : Fetch {

    fun reset()

    fun delete()

    fun directory(): Path

    fun hasBlock(cid: Long): Boolean

    fun blockSize(cid: Long): Int

    override suspend fun fetchBlock(cid: Long): Buffer

    fun rawSource(cid: Long): RawSource

    fun getBlock(cid: Long): Buffer

    fun readBlockSlice(cid: Long, offset: Int, length: Int): ByteArray

    fun readBlockSlice(cid: Long, offset: Int): ByteArray

    fun deleteBlock(cid: Long)

    fun nextCid(): Long

    fun storeBlock(cid: Long, buffer: Buffer)

    fun storeBlock(cid: Long, bytes: List<ByteArray>)

    fun root(data: ByteArray)

    fun root(node: Node)

    fun root(): Node

    fun tempFile(): Path

    fun response(request: Long?): Response {
        val cid = request ?: root().cid()

        val node = info(cid)
        return if (node is Fid) {
            val channel = channel(node)
            contentResponse(channel, node)
        } else {
            val channel = channel(node)
            contentResponse(channel, "OK", 200)
        }
    }

    fun info(cid: Long): Node {
        val block = getBlock(cid)
        return decodeNode(cid, block)
    }

    // Note: remove the cid block (add all links blocks recursively)
    fun delete(node: Node) {
        removeNode(this, node)
    }

    fun storeData(data: ByteArray): Node {
        return createRaw(this, data) {
            nextCid()
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
        SystemFileSystem.source(path).buffered().use { source ->
            return storeSource(source, path.name, mimeType)
        }

    }

    suspend fun transferTo(node: Node, path: Path) {
        SystemFileSystem.sink(path, false).buffered().use { sink ->
            channel(node).transferTo(sink) {}
        }
    }

    fun channel(node: Node): Channel {
        return createChannel(node, this)
    }

    fun storeSource(source: Source, name: String, mimeType: String): Node {
        return io.github.remmerw.idun.core.storeSource(source, this, name, mimeType) {
            nextCid()
        }
    }

    suspend fun fetchData(node: Node): ByteArray {
        return io.github.remmerw.idun.core.fetchData(node, this)
    }

    suspend fun fetchText(node: Node): String {
        return fetchData(node).decodeToString()
    }

}

private class StorageImpl(private val directory: Path) : Storage {
    @Volatile
    private var root: Node = createRaw(this, byteArrayOf()) {
        nextCid()
    }

    override fun directory(): Path {
        return directory
    }

    override fun root(data: ByteArray) {
        root(createRaw(this, data) {
            nextCid()
        })
    }

    override fun root(node: Node) {
        root = node
    }

    override fun root(): Node {
        return root
    }

    override fun reset() {
        root(byteArrayOf())
        cleanupDirectory(directory)
    }

    override fun delete() {
        cleanupDirectory(directory)
        SystemFileSystem.delete(directory, false)
    }

    override fun hasBlock(cid: Long): Boolean {
        require(cid != HALO_ROOT) { "Invalid Cid" }
        return SystemFileSystem.exists(path(cid))
    }

    override fun blockSize(cid: Long): Int {
        require(cid != HALO_ROOT) { "Invalid Cid" }
        val file = path(cid)
        require(SystemFileSystem.exists(file)) { "Block does not exists" }
        return SystemFileSystem.metadataOrNull(file)!!.size.toInt()
    }

    override fun getBlock(cid: Long): Buffer {
        require(cid != HALO_ROOT) { "Invalid Cid" }
        val file = path(cid)
        require(SystemFileSystem.exists(file)) { "Block does not exists" }
        val size = SystemFileSystem.metadataOrNull(file)!!.size
        SystemFileSystem.source(file).use { source ->
            val buffer = Buffer()
            buffer.write(source, size)
            return buffer
        }
    }

    override fun readBlockSlice(cid: Long, offset: Int, length: Int): ByteArray {
        require(cid != HALO_ROOT) { "Invalid Cid" }
        val file = path(cid)
        require(SystemFileSystem.exists(file)) { "Block does not exists" }
        SystemFileSystem.source(file).buffered().use { source ->
            source.skip(offset.toLong())
            return source.readByteArray(length)
        }
    }

    override fun readBlockSlice(cid: Long, offset: Int): ByteArray {
        require(cid != HALO_ROOT) { "Invalid Cid" }
        val file = path(cid)
        require(SystemFileSystem.exists(file)) { "Block does not exists" }
        SystemFileSystem.source(file).buffered().use { source ->
            source.skip(offset.toLong())
            return source.readByteArray()
        }
    }

    override suspend fun fetchBlock(cid: Long): Buffer {
        return getBlock(cid)
    }

    override fun rawSource(cid: Long): RawSource {
        require(cid != HALO_ROOT) { "Invalid Cid" }
        val file = path(cid)
        require(SystemFileSystem.exists(file)) { "Block does not exists" }
        return SystemFileSystem.source(file)
    }

    override fun storeBlock(cid: Long, bytes: List<ByteArray>) {
        require(cid != HALO_ROOT) { "Invalid Cid" }
        val file = path(cid)
        SystemFileSystem.sink(file, false).buffered().use { source ->
            bytes.forEach { data -> source.write(data) }
        }
    }

    override fun storeBlock(cid: Long, buffer: Buffer) {
        require(cid != HALO_ROOT) { "Invalid Cid" }
        val file = path(cid)
        SystemFileSystem.sink(file, false).use { sink ->
            sink.write(buffer, buffer.size)
        }
    }

    @OptIn(ExperimentalUuidApi::class)
    override fun tempFile(): Path {
        return Path(directory, Uuid.random().toHexString())
    }

    @OptIn(ExperimentalStdlibApi::class)
    private fun path(cid: Long): Path {
        require(cid != HALO_ROOT) { "Invalid Cid" }
        return Path(directory, cid.toHexString())
    }

    override fun deleteBlock(cid: Long) {
        require(cid != HALO_ROOT) { "Invalid Cid" }
        val file = path(cid)
        SystemFileSystem.delete(file, false)
    }

    override fun nextCid(): Long {
        val cid = Random.nextLong()
        if (cid != HALO_ROOT) {
            val exists = hasBlock(cid)
            if (!exists) {
                return cid
            }
        }
        return nextCid()
    }
}


fun contentResponse(
    channel: Channel, msg: String, status: Int
): Response {
    return Response(
        "text/html", "UTF-8", status,
        msg, emptyMap(), channel
    )
}

const val CONTENT_TYPE: String = "Content-Type"
const val CONTENT_LENGTH: String = "Content-Length"
const val CONTENT_TITLE: String = "Content-Title"

fun contentResponse(channel: Channel, node: Node): Response {
    val mimeType = node.mimeType()
    val responseHeaders: MutableMap<String, String> = mutableMapOf()
    responseHeaders[CONTENT_LENGTH] = node.size().toString()
    responseHeaders[CONTENT_TYPE] = mimeType
    responseHeaders[CONTENT_TITLE] = node.name()
    return Response(
        mimeType, "UTF-8", 200,
        "OK", responseHeaders, channel
    )
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


fun newStorage(directory: Path): Storage {
    SystemFileSystem.createDirectories(directory)
    require(
        SystemFileSystem.metadataOrNull(directory)?.isDirectory == true
    ) {
        "Path is not a directory."
    }
    return StorageImpl(directory)
}

fun decodeNode(cid: Long, block: Buffer): Node {
    return decodeNode(cid, block)
}

interface Fetch {
    suspend fun fetchBlock(cid: Long): Buffer
}

fun createChannel(node: Node, fetch: Fetch): Channel {
    val size = node.size()
    if (node is Fid) {
        return FidChannel(node, size, fetch)
    }
    val raw = node as Raw
    val buffer = Buffer()
    buffer.write(raw.data())
    return RawChannel(buffer)
}

fun debug(throwable: Throwable) {
    if (DEBUG) {
        throwable.printStackTrace()
    }
}

private const val DEBUG: Boolean = true
