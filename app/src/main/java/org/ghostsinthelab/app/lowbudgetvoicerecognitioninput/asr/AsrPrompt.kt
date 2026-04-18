package org.ghostsinthelab.app.lowbudgetvoicerecognitioninput.asr

/**
 * Builds the chat-template prompt string for Gemma 4 E2B ASR.
 *
 * Reproduces the relevant parts of `chat_template.jinja` manually (we don't ship a
 * Jinja engine). Output shape, once tokenised with [GemmaTokenizer.encodeWithSpecials],
 * is `<bos> <|turn> user \n {instructions} \n {audio_token}×N \n <turn|> \n <|turn> model \n`.
 *
 * Generation is stopped on the model's end-of-turn token (id 106 per config).
 */
object AsrPrompt {
    const val AUDIO_TOKEN_ID = 258881
    val EOS_IDS = intArrayOf(1, 106)

    private val instructionByLanguage = mapOf(
        "en-US" to "Transcribe the following speech segment in English (US) into English text. Only output the transcription, with no extra commentary.",
        "en-GB" to "Transcribe the following speech segment in English (UK) into English text. Only output the transcription, with no extra commentary.",
        "zh-Hant-TW" to "請將以下語音段落轉錄為繁體中文（台灣）文字，只輸出轉錄結果，不要加任何說明。",
        "zh-Hans-CN" to "请将以下语音段落转录为简体中文（中国大陆）文字，只输出转录结果，不要加任何说明。",
        "ja" to "以下の音声を日本語の文字に書き起こしてください。書き起こしのみを出力し、余計な説明は一切加えないでください。",
        "ko" to "다음 음성을 한국어 문자로 전사하세요. 전사 결과만 출력하고 다른 설명은 하지 마세요.",
    )

    fun render(numAudioTokens: Int, language: String = "en-US"): String {
        val instruction = instructionByLanguage[language]
            ?: error("Unsupported language: $language")
        return buildString {
            append("<bos>")
            append("<|turn>user\n")
            append(instruction)
            append('\n')
            repeat(numAudioTokens) { append("<|audio|>") }
            append('\n')
            append("<turn|>\n")
            append("<|turn>model\n")
        }
    }
}
