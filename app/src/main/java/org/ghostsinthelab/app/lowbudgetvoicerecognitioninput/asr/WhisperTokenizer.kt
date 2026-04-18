package org.ghostsinthelab.app.lowbudgetvoicerecognitioninput.asr

import java.io.File

/**
 * Reads sherpa-onnx's Whisper `tokens.txt` (one line: `<piece>\t<id>`) and
 * provides ID ↔ piece lookups plus the special tokens needed to build a
 * decoder prefix prompt and detect end-of-transcript.
 *
 * We never BPE-encode arbitrary text for ASR — the decoder prompt consists
 * entirely of special tokens whose IDs we look up by name. The tokenizer is
 * used as an encoder only for those specials, and as a decoder for whatever
 * the model emits.
 *
 * Gotcha: Whisper uses GPT-2-style byte-level BPE. A single UTF-8 character
 * can be split across multiple tokens, and each token's "piece" string may
 * itself be the byte-to-unicode remapped form (characters in `Ā..ſ`). We
 * handle that by reverse-mapping each piece's characters to raw bytes and
 * concatenating into one byte stream before UTF-8 decoding. The exact mapping
 * used by sherpa-onnx's exporter is confirmed by inspecting tokens.txt on
 * device first — this class keeps two decode paths (`decodePlain` for when
 * pieces are already UTF-8, `decodeByteLevel` for GPT-2 byte-level) and the
 * caller picks based on what we observe.
 */
class WhisperTokenizer private constructor(
    private val idToPiece: Array<String>,
    private val pieceToId: Map<String, Int>,
) {
    val vocabSize: Int get() = idToPiece.size

    fun idOf(piece: String): Int? = pieceToId[piece]
    fun pieceOf(id: Int): String? = idToPiece.getOrNull(id)

    /** Concatenate pieces as-is. Correct when pieces are stored in their visible UTF-8 form. */
    fun decodePlain(ids: IntArray, skipSpecials: Boolean = true): String {
        val sb = StringBuilder()
        for (id in ids) {
            val piece = pieceOf(id) ?: continue
            if (skipSpecials && piece.startsWith("<|") && piece.endsWith("|>")) continue
            sb.append(piece)
        }
        return sb.toString()
    }

    /**
     * Decodes assuming GPT-2 byte-level BPE — each character in a piece maps
     * back to a single byte via the standard GPT-2 byte-to-unicode table.
     */
    fun decodeByteLevel(ids: IntArray, skipSpecials: Boolean = true): String {
        val bytes = java.io.ByteArrayOutputStream()
        for (id in ids) {
            val piece = pieceOf(id) ?: continue
            if (skipSpecials && piece.startsWith("<|") && piece.endsWith("|>")) continue
            for (ch in piece) {
                val b = unicodeToByte[ch.code] ?: ch.code
                bytes.write(b)
            }
        }
        return bytes.toString("UTF-8")
    }

    /**
     * IDs needed to build the decoder prompt:
     *   `<|startoftranscript|> <|lang|> <|transcribe|> <|notimestamps|>`
     * plus `<|endoftext|>` to know when to stop. Looked up by name.
     */
    data class SpecialIds(
        val startOfTranscript: Int,
        val transcribe: Int,
        val notimestamps: Int,
        val endOfText: Int,
        val languageIds: Map<String, Int>, // "en" -> id, "zh" -> id, ...
    )

    fun specialIds(): SpecialIds {
        val langs = listOf("en", "zh", "ja", "ko")
        val langIds = langs.mapNotNull { l -> idOf("<|$l|>")?.let { l to it } }.toMap()
        return SpecialIds(
            startOfTranscript = idOf("<|startoftranscript|>") ?: error("missing sot"),
            transcribe = idOf("<|transcribe|>") ?: error("missing transcribe"),
            notimestamps = idOf("<|notimestamps|>") ?: error("missing notimestamps"),
            endOfText = idOf("<|endoftext|>") ?: error("missing endoftext"),
            languageIds = langIds,
        )
    }

    companion object {
        fun load(tokensFile: File): WhisperTokenizer {
            val pieceToId = HashMap<String, Int>()
            var maxId = 0
            tokensFile.useLines { lines ->
                for (line in lines) {
                    if (line.isEmpty()) continue
                    // Sherpa-onnx Whisper tokens.txt uses space (not tab) as the piece/id
                    // separator; the id is always the last whitespace-separated field.
                    val sep = line.indexOfLast { it == ' ' || it == '\t' }
                    if (sep < 0) continue
                    val piece = line.substring(0, sep)
                    val id = line.substring(sep + 1).toIntOrNull() ?: continue
                    pieceToId[piece] = id
                    if (id > maxId) maxId = id
                }
            }
            val arr = Array(maxId + 1) { "" }
            for ((piece, id) in pieceToId) arr[id] = piece
            return WhisperTokenizer(arr, pieceToId)
        }

        /**
         * GPT-2 byte-to-unicode mapping (as used by Whisper's tokenizer).
         * Printable ASCII + the first-1024 Unicode block translates to single bytes.
         */
        private val byteToUnicode: IntArray = IntArray(256).also { m ->
            val bs = (('!'.code..'~'.code) + ('¡'.code..'¬'.code) + ('®'.code..'ÿ'.code))
                .toMutableList()
            val cs = bs.toMutableList()
            var n = 0
            for (b in 0..255) {
                if (b !in bs) {
                    bs.add(b)
                    cs.add(256 + n)
                    n++
                }
            }
            for (i in bs.indices) m[bs[i]] = cs[i]
        }

        private val unicodeToByte: Map<Int, Int> =
            (0..255).associateBy { byteToUnicode[it] }
    }
}
