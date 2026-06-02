package `is`.xyz.mpv.tts

/** Splits mixed Vietnamese/English subtitle text for code-switching TTS. */
internal object SubtitleTextSegmenter {
    enum class Lang { VI, EN }

    data class Segment(val lang: Lang, val text: String)

    // Latin words (brand names, English phrases in VI subtitles).
    private val pattern = Regex("([A-Za-z][A-Za-z0-9'\\-]*)|([^A-Za-z]+)")

    fun segment(text: String): List<Segment> {
        val raw = mutableListOf<Segment>()
        for (match in pattern.findAll(text)) {
            val en = match.groupValues[1]
            val other = match.groupValues[2]
            if (en.isNotEmpty()) {
                raw.add(Segment(Lang.EN, en))
            } else {
                val vi = other.trim()
                if (vi.isNotEmpty())
                    raw.add(Segment(Lang.VI, vi))
            }
        }
        return mergeAdjacent(raw)
    }

    private fun mergeAdjacent(segments: List<Segment>): List<Segment> {
        if (segments.isEmpty())
            return segments

        val merged = mutableListOf<Segment>()
        var current = segments[0]
        for (i in 1 until segments.size) {
            val next = segments[i]
            if (next.lang == current.lang) {
                val joiner = if (current.lang == Lang.VI) " " else " "
                current = Segment(current.lang, current.text + joiner + next.text)
            } else {
                merged.add(current)
                current = next
            }
        }
        merged.add(current)
        return merged
    }
}
