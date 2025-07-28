package io.github.remmerw.idun.core

import io.github.remmerw.idun.Node

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

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as Raw

        if (cid != other.cid) return false
        if (!data.contentEquals(other.data)) return false

        return true
    }

    override fun hashCode(): Int {
        var result = cid.hashCode()
        result = 31 * result + data.contentHashCode()
        return result
    }
}
