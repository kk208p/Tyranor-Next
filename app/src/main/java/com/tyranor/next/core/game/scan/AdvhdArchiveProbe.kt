package com.tyranor.next.core.game.scan

/**
 * Conservative, bounded ARC probe for WillPlus AdvHD titles.
 *
 * A root `.arc` counts as evidence only when its index is structurally valid
 * and contains at least one `.ws2` script entry. No payload is decoded and no
 * extraction happens during scans.
 */
internal object AdvhdArchiveProbe {
    const val MAX_BYTES = 64 * 1024

    fun hasBootstrap(head: ByteArray?, archiveBytes: Long? = null): Boolean {
        if (head == null || head.size < 24) return false
        val count = u32(head, 0)
        val indexSize = u32(head, 4)
        if (count == 0L || count > 500_000L || indexSize < count * 8) return false
        val base = 8L + indexSize
        if (archiveBytes != null && base > archiveBytes) return false
        return scanV2(head, count.toInt()) || scanV1(head)
    }

    /** v2: u32 count, u32 index_size, then {u32 size, u32 offset, UTF-16LE name}. */
    private fun scanV2(head: ByteArray, count: Int): Boolean {
        var p = 8
        var n = 0
        while (n < count && p + 8 <= head.size) {
            p += 8
            val start = p
            var end = -1
            while (p + 1 < head.size) {
                if (head[p] == 0.toByte() && head[p + 1] == 0.toByte()) {
                    end = p
                    break
                }
                p += 2
            }
            if (end < 0) return false
            if (end - start in 1..512) {
                val name = String(head, start, end - start, Charsets.UTF_16LE).lowercase()
                if (name.endsWith(".ws2")) return true
            }
            p = end + 2
            n++
        }
        return false
    }

    /** v1: u32 ext_count, then {char[4] ext, u32 file_count, u32 table_offset}. */
    private fun scanV1(head: ByteArray): Boolean {
        val extCount = u32(head, 0)
        if (extCount == 0L || extCount > 64) return false
        var p = 4
        repeat(extCount.toInt()) {
            if (p + 12 > head.size) return false
            val ext = String(head, p, 4, Charsets.US_ASCII).trim('\u0000').lowercase()
            val files = u32(head, p + 4)
            if (ext == "ws2" && files > 0) return true
            p += 12
        }
        return false
    }

    private fun u32(bytes: ByteArray, at: Int): Long =
        (0..3).fold(0L) { value, i -> value or ((bytes[at + i].toLong() and 255) shl (8 * i)) }
}
