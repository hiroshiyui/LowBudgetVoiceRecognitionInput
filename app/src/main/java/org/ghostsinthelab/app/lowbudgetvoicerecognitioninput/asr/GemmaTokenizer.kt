package org.ghostsinthelab.app.lowbudgetvoicerecognitioninput.asr

import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * Pure-Kotlin BPE tokenizer for Gemma 4's `tokenizer.json` format.
 *
 * Covers what's needed for Gemma 4 E2B on-device ASR:
 *  - Parse HF tokenizers JSON (model.type = "BPE", byte_fallback = true).
 *  - Encode: match special tokens (added_tokens) first; BPE-encode the rest with
 *    SentencePiece `▁`-prefix whitespace handling and `<0xNN>`-byte fallback.
 *  - Decode: buffer raw bytes across tokens so multi-byte UTF-8 characters that
 *    cross token boundaries reassemble correctly; `▁` → space; skip specials.
 *
 * Deliberate non-goals (not needed for ASR):
 *  - Normalizer / NFKC (Gemma's normalizer is None).
 *  - Regex-based pre-tokenizers; Gemma's pre-tokenizer just adds the SPM metaspace.
 *  - Streaming JSON — tokenizer.json is ~19 MB, parsed fully once at construction.
 */
class GemmaTokenizer private constructor(
    private val vocab: Map<String, Int>,
    private val idToPiece: Array<String>,
    private val mergeRank: Map<Pair<String, String>, Int>,
    private val addedTokens: List<AddedToken>,
    private val byteFallbackIds: IntArray,
    private val specialIds: Set<Int>,
) {
    data class AddedToken(val id: Int, val content: String, val special: Boolean)

    /** Encode plain text into token IDs. Does NOT add BOS / chat turn markers — caller handles those. */
    fun encode(text: String): IntArray {
        val out = ArrayList<Int>(text.length)
        encodeSegment(text, out, prependMetaspace = true)
        return out.toIntArray()
    }

    /**
     * Encode text with awareness of added-token strings. Any occurrence of a special-token
     * string (e.g. `<|audio|>`, `<bos>`) is emitted as its own ID and the surrounding text
     * is BPE-encoded separately. SentencePiece metaspace is only prepended at the very
     * start of the input — never between consecutive special tokens, which would corrupt
     * the chat-template structure (extra `▁` tokens between `<turn|>` and `\n`, etc).
     */
    fun encodeWithSpecials(text: String): IntArray {
        val out = ArrayList<Int>(text.length)
        var cursor = 0
        var atInputStart = true
        while (cursor < text.length) {
            val nextMatch = findNextAddedToken(text, cursor)
            if (nextMatch == null) {
                encodeSegment(text.substring(cursor), out, prependMetaspace = atInputStart)
                break
            }
            if (nextMatch.start > cursor) {
                encodeSegment(
                    text.substring(cursor, nextMatch.start),
                    out,
                    prependMetaspace = atInputStart,
                )
            }
            out.add(nextMatch.token.id)
            atInputStart = false
            cursor = nextMatch.start + nextMatch.token.content.length
        }
        return out.toIntArray()
    }

    /** Decode token IDs back into text. Skips special tokens by default. */
    fun decode(ids: IntArray, skipSpecialTokens: Boolean = true): String {
        val bytes = ByteArrayOutputStream()
        for (id in ids) {
            if (skipSpecialTokens && id in specialIds) continue
            if (id < 0 || id >= idToPiece.size) continue
            val piece = idToPiece[id]
            if (piece.isEmpty()) continue

            val byteVal = parseByteFallback(piece)
            if (byteVal != null) {
                bytes.write(byteVal)
            } else {
                val pieceBytes = piece.toByteArray(Charsets.UTF_8)
                bytes.write(pieceBytes, 0, pieceBytes.size)
            }
        }
        return bytes.toString("UTF-8").replace('\u2581', ' ')
    }

    fun idOf(piece: String): Int? = vocab[piece]
    fun vocabSize(): Int = idToPiece.size
    fun isSpecial(id: Int): Boolean = id in specialIds

    // ---------- internals ----------

    private data class AddedTokenMatch(val start: Int, val token: AddedToken)

    private fun findNextAddedToken(text: String, from: Int): AddedTokenMatch? {
        var best: AddedTokenMatch? = null
        for (at in addedTokens) {
            if (at.content.isEmpty()) continue
            val idx = text.indexOf(at.content, startIndex = from)
            if (idx < 0) continue
            if (best == null || idx < best.start ||
                (idx == best.start && at.content.length > best.token.content.length)
            ) {
                best = AddedTokenMatch(idx, at)
            }
        }
        return best
    }

    private fun encodeSegment(segment: String, out: MutableList<Int>, prependMetaspace: Boolean) {
        if (segment.isEmpty()) return
        // SentencePiece metaspace: internal spaces always become ▁. The leading ▁ is only
        // added when we're at the very start of the input — otherwise segments that begin
        // with non-space characters right after a special token (e.g. `<|turn>model\n`'s
        // "model\n" portion) or pure whitespace segments (`<turn|>\n<|turn>`'s "\n") get
        // a spurious `▁` that the model was never trained to see.
        val withMeta = buildString(segment.length + 1) {
            if (prependMetaspace && segment[0] != ' ') append('\u2581')
            for (ch in segment) {
                if (ch == ' ') append('\u2581') else append(ch)
            }
        }
        bpeEncode(withMeta, out)
    }

    private fun bpeEncode(text: String, out: MutableList<Int>) {
        if (text.isEmpty()) return
        // Initial pieces: one UTF-16 code unit per slot, but combine surrogate pairs.
        val pieces = ArrayList<String>(text.length)
        var i = 0
        while (i < text.length) {
            val ch = text[i]
            if (ch.isHighSurrogate() && i + 1 < text.length && text[i + 1].isLowSurrogate()) {
                pieces.add(text.substring(i, i + 2))
                i += 2
            } else {
                pieces.add(ch.toString())
                i++
            }
        }

        // Repeatedly merge the lowest-ranked adjacent pair until no merges apply.
        while (pieces.size >= 2) {
            var bestRank = Int.MAX_VALUE
            var bestIdx = -1
            for (j in 0 until pieces.size - 1) {
                val rank = mergeRank[pieces[j] to pieces[j + 1]] ?: continue
                if (rank < bestRank) {
                    bestRank = rank
                    bestIdx = j
                }
            }
            if (bestIdx < 0) break
            pieces[bestIdx] = pieces[bestIdx] + pieces[bestIdx + 1]
            pieces.removeAt(bestIdx + 1)
        }

        for (piece in pieces) {
            val id = vocab[piece]
            if (id != null) {
                out.add(id)
            } else {
                // Byte fallback: emit each UTF-8 byte as <0xNN>.
                for (b in piece.toByteArray(Charsets.UTF_8)) {
                    val unsigned = b.toInt() and 0xFF
                    out.add(byteFallbackIds[unsigned])
                }
            }
        }
    }

    private fun parseByteFallback(piece: String): Int? {
        // Matches "<0xNN>" pattern from SPM byte-fallback tokens.
        if (piece.length != 6 || piece[0] != '<' || piece[1] != '0' ||
            piece[2] != 'x' || piece[5] != '>'
        ) return null
        val hi = hexVal(piece[3]); val lo = hexVal(piece[4])
        if (hi < 0 || lo < 0) return null
        return (hi shl 4) or lo
    }

    private fun hexVal(c: Char): Int = when (c) {
        in '0'..'9' -> c - '0'
        in 'a'..'f' -> 10 + (c - 'a')
        in 'A'..'F' -> 10 + (c - 'A')
        else -> -1
    }

    companion object {
        fun load(tokenizerJson: File): GemmaTokenizer {
            val root = JSONObject(tokenizerJson.readText())

            val model = root.getJSONObject("model")
            check(model.getString("type") == "BPE") {
                "Only BPE model type is supported, got ${model.getString("type")}"
            }

            val vocabObj = model.getJSONObject("vocab")
            val vocab = HashMap<String, Int>(vocabObj.length())
            val maxId = run {
                var m = 0
                val iter = vocabObj.keys()
                while (iter.hasNext()) {
                    val k = iter.next()
                    val id = vocabObj.getInt(k)
                    vocab[k] = id
                    if (id > m) m = id
                }
                m
            }

            val addedArr: JSONArray = root.optJSONArray("added_tokens") ?: JSONArray()
            var maxAddedId = maxId
            for (i in 0 until addedArr.length()) {
                val id = addedArr.getJSONObject(i).getInt("id")
                if (id > maxAddedId) maxAddedId = id
            }
            val idToPiece = Array(maxAddedId + 1) { "" }
            for ((piece, id) in vocab) idToPiece[id] = piece

            val addedTokens = ArrayList<AddedToken>(addedArr.length())
            val specialIds = HashSet<Int>()
            for (i in 0 until addedArr.length()) {
                val a = addedArr.getJSONObject(i)
                val token = AddedToken(
                    id = a.getInt("id"),
                    content = a.getString("content"),
                    special = a.optBoolean("special", false),
                )
                addedTokens.add(token)
                idToPiece[token.id] = token.content
                if (token.special) specialIds.add(token.id)
            }
            addedTokens.sortByDescending { it.content.length }

            val mergesArr = model.getJSONArray("merges")
            val mergeRank = HashMap<Pair<String, String>, Int>(mergesArr.length())
            for (i in 0 until mergesArr.length()) {
                val entry = mergesArr.get(i)
                val pair: Pair<String, String>? = when (entry) {
                    is JSONArray -> if (entry.length() == 2) entry.getString(0) to entry.getString(1) else null
                    is String -> entry.split(' ', limit = 2).let { parts ->
                        if (parts.size == 2) parts[0] to parts[1] else null
                    }
                    else -> null
                }
                if (pair != null) mergeRank[pair] = i
            }

            val byteFallbackIds = IntArray(256) { -1 }
            for (b in 0..255) {
                val token = "<0x%02X>".format(b)
                val id = vocab[token]
                if (id != null) byteFallbackIds[b] = id
            }
            require(byteFallbackIds.all { it >= 0 }) {
                "Missing byte-fallback tokens in vocab"
            }

            return GemmaTokenizer(
                vocab = vocab,
                idToPiece = idToPiece,
                mergeRank = mergeRank,
                addedTokens = addedTokens,
                byteFallbackIds = byteFallbackIds,
                specialIds = specialIds,
            )
        }
    }
}
