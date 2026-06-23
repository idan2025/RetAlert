package network.retalert.domain

import java.security.MessageDigest

/** Media kinds. */
const val MEDIA_PHOTO = "photo"
const val MEDIA_AUDIO = "audio"

/** Chunk payload size (bytes). Parity with CHUNK_SIZE. */
const val MEDIA_CHUNK_SIZE = 4096

/** One framed media packet on the wire. Wire format = msgpack array
 *  `[alert_id, kind, seq, total, sha256, payload]` — byte-identical to Python. */
data class MediaChunk(
    val alertId: String,
    val kind: String,        // photo | audio
    val seq: Int,             // 0-based chunk index
    val total: Int,           // total chunks (0 = streaming/live)
    val sha256: String,       // hash of full payload (photo) or chunk (audio)
    val payload: ByteArray,
) {
    fun asBytes(): ByteArray = MsgPack.pack(listOf(alertId, kind, seq, total, sha256, payload))

    companion object {
        fun fromBytes(data: ByteArray): MediaChunk {
            val a = MsgPack.unpack(data) as List<*>
            return MediaChunk(
                alertId = a[0] as String,
                kind = a[1] as String,
                seq = (a[2] as Number).toInt(),
                total = (a[3] as Number).toInt(),
                sha256 = a[4] as String,
                payload = (a[5] as ByteArray),
            )
        }
    }
}

/** A reassembled photo or a captured audio chunk. Mirrors `Media`. */
data class Media(
    val alertId: String,
    val kind: String,
    val data: ByteArray = ByteArray(0),
    val sha256: String = "",
    val complete: Boolean = false,
) {
    val size: Int get() = data.size
}

/** Frames, chunks, reassembles, and tier-gates media transfers. Mirrors MediaChannel.
 *  Photo/audio are High-tier-only. [linkSendFn] is the transport seam (:reticulum wires). */
class MediaChannel(
    private val ti: TransportIntelligence? = null,
    private val linkSendFn: ((ByteArray) -> Unit)? = null,
    private val onComplete: ((Media) -> Unit)? = null,
    private val onAudioChunk: ((Media) -> Unit)? = null,
    private val chunkSize: Int = MEDIA_CHUNK_SIZE,
) {
    private val rx = LinkedHashMap<String, RxEntry>()
    private val lock = Any()

    private data class RxEntry(
        val chunks: MutableMap<Int, ByteArray> = LinkedHashMap(),
        var total: Int = 0,
        val sha: String = "",
        val kind: String = MEDIA_PHOTO,
    )

    /** Photo/audio are High-tier-only. Returns (ok, bestTierOrNull). */
    fun gate(kind: String = MEDIA_PHOTO): Pair<Boolean, String?> {
        if (ti == null) return true to null  // no intelligence: caller decides
        val up = ti.upInterfaces()
        if (up.isEmpty()) return false to null
        val best = up.maxByOrNull { Tiers.rankOf(it.tier) }?.tier
        return (best == Tiers.HIGH) to best
    }

    /** Chunk + send a photo. Returns chunk count, or 0 if tier-gated / no link. */
    fun sendPhoto(alertId: String, data: ByteArray): Int {
        val (ok, _) = gate(MEDIA_PHOTO)
        if (!ok) return 0
        check(linkSendFn != null) { "no link_send_fn wired" }
        val sha = sha256Hex(data)
        val chunks = split(data)
        val total = chunks.size
        chunks.forEachIndexed { i, payload ->
            val chunk = MediaChunk(alertId, MEDIA_PHOTO, i, total, sha, payload)
            linkSendFn!!(chunk.asBytes())
        }
        return total
    }

    /** Send one audio chunk (live stream; total=0 = open-ended). */
    fun sendAudioChunk(alertId: String, data: ByteArray, seq: Int, total: Int = 0): Boolean {
        val (ok, _) = gate(MEDIA_AUDIO)
        if (!ok) return false
        check(linkSendFn != null) { "no link_send_fn wired" }
        val sha = sha256Hex(data)
        val chunk = MediaChunk(alertId, MEDIA_AUDIO, seq, total, sha, data)
        linkSendFn!!(chunk.asBytes())
        return true
    }

    private fun split(data: ByteArray): List<ByteArray> {
        if (data.isEmpty()) return listOf(ByteArray(0))
        val out = ArrayList<ByteArray>()
        var i = 0
        while (i < data.size) {
            val end = minOf(i + chunkSize, data.size)
            out.add(data.copyOfRange(i, end))
            i = end
        }
        return out
    }

    /** Ingest one wire frame. Returns a completed Media (photo) or audio Media
     *  per chunk, else null while a photo is still assembling. */
    fun receive(frame: ByteArray): Media? {
        val chunk = try { MediaChunk.fromBytes(frame) } catch (_: Exception) { return null }

        if (chunk.kind == MEDIA_AUDIO) {
            val media = Media(chunk.alertId, MEDIA_AUDIO, chunk.payload, chunk.sha256, complete = false)
            onAudioChunk?.invoke(media)
            return media
        }

        // photo: reassemble by seq.
        synchronized(lock) {
            val entry = rx.getOrPut(chunk.alertId) { RxEntry(total = chunk.total, sha = chunk.sha256) }
            entry.chunks[chunk.seq] = chunk.payload
            if (chunk.total != 0 && entry.chunks.size >= chunk.total) {
                val totalLen = (0 until chunk.total).sumOf { entry.chunks[it]!!.size }
                val ordered = ByteArray(totalLen)
                var off = 0
                for (i in 0 until chunk.total) {
                    val b = entry.chunks[i]!!
                    System.arraycopy(b, 0, ordered, off, b.size)
                    off += b.size
                }
                val media = Media(chunk.alertId, MEDIA_PHOTO, ordered, entry.sha, complete = true)
                rx.remove(chunk.alertId)
                onComplete?.invoke(media)
                return media
            }
            return null
        }
    }

    private fun sha256Hex(data: ByteArray): String {
        val md = MessageDigest.getInstance("SHA-256")
        return md.digest(data).joinToString("") { "%02x".format(it) }
    }
}