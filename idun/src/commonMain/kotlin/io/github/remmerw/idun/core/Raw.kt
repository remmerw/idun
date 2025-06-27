package io.github.remmerw.idun.core

import io.github.remmerw.idun.Node
import io.github.remmerw.idun.splitterSize

@Suppress("ArrayInDataClass")
internal data class Raw(
    private val cid: Long,
    private val data: ByteArray
) : Node {

    fun data(): ByteArray {
        return data
    }

    override fun name(): String {
        return UNDEFINED_NAME
    }

    override fun mimeType(): String {
        return OCTET_MIME_TYPE
    }

    override fun size(): Long {
        return data.size.toLong()
    }

    override fun cid(): Long {
        return cid
    }

    init {
        require(data.size <= splitterSize()) { "Invalid data size" }
    }
}
