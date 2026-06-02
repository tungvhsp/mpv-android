package `is`.xyz.mpv.tts

/** Removes markup from subtitle lines before sending text to Sherpa TTS. */
object SubtitleTextSanitizer {
    /** Sherpa can crash on very long ASS lines; keep a safe upper bound. */
    private const val MAX_CHARS = 400

    private val assTag = Regex("""\{[^}]*\}""")
    private val htmlTag = Regex("""<[^>]+>""")
    private val whitespace = Regex("""\s+""")

    fun forTts(text: String): String {
        val cleaned = text
            .replace("\\N", " ")
            .replace('\n', ' ')
            .replace('\r', ' ')
            .replace(assTag, "")
            .replace(htmlTag, "")
            .replace(whitespace, " ")
            .trim()
        return if (cleaned.length <= MAX_CHARS) cleaned else cleaned.take(MAX_CHARS).trim()
    }
}
