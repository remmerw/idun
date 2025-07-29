package io.github.remmerw.idun.core

import io.github.remmerw.idun.MAX_CHARS_SIZE
import io.github.remmerw.idun.Node


internal data class Fid(
    private val cid: Long,
    private val size: Long,
    private val name: String,
    private val mimeType: String,
    private val links: Int
) : Node {

    fun links(): Int {
        return links
    }

    override fun size(): Long {
        return size
    }

    override fun name(): String {
        return name
    }

    override fun mimeType(): String {
        return mimeType
    }

    override fun cid(): Long {
        return cid
    }

    init {
        require(size >= 0) { "Invalid size" }
        require(name.length <= MAX_CHARS_SIZE) { "Invalid name length" }
    }
}

