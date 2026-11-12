package org.example.voicepitchlooper

import kotlin.math.pow

/** One character position (or one pitch token such as C#4) is exactly one beat.
 *
 * Example notation: D3A3F# GB3G3G — one ASCII space is one rest.
 * Write adjacent pitch tokens without separators: C3D3 E3 is four beats.
 * Older '_' / '.' / '-' rests are still accepted on input.
 */
data class ScoreNote(val label: String, val frequency: Double?)

object ScoreParser {
    /** Single source of truth for the maximum score size: 1,024 complete 4/4 bars. */
    const val MAX_BEATS = 4_096

    /** New input format: each literal space means exactly one silent beat. */
    fun parse(input: String): List<ScoreNote> = parseImpl(input, legacyWhitespace = false)

    /**
     * Keep 4/4 score boundaries complete, even when a pasted phrase ends on beat 1-3.
     * Padding is deliberately separate from parse(): setPitch() and other callers
     * still need to parse an individual note without turning it into four beats.
     * The returned trailing rests are real beats during playback and in the editor.
     */
    fun completeMeasures(notes: List<ScoreNote>): List<ScoreNote> {
        val missing = (4 - notes.size % 4) % 4
        require(notes.size + missing <= MAX_BEATS) { "音符は4096拍以内にしてください" }
        return if (missing == 0) notes else
            notes + List(missing) { ScoreNote("休符", null) }
    }

    fun parseCompleteMeasures(input: String): List<ScoreNote> = completeMeasures(parse(input))

    /** Only for saved scores from <=0.6.6, where spaces were ignored separators. */
    fun parseLegacy(input: String): List<ScoreNote> = parseImpl(input, legacyWhitespace = true)

    /** Compact, unambiguous representation. No separators between pitched notes. */
    fun serialize(notes: List<ScoreNote>): String = buildString {
        for (note in notes) append(if (note.frequency == null) " " else note.label)
    }

    private fun parseImpl(input: String, legacyWhitespace: Boolean): List<ScoreNote> {
        val result = mutableListOf<ScoreNote>()
        // Square brackets are accepted when surrounding the complete phrase,
        // e.g. [CDEF GAB]. Do not trim: trailing spaces are meaningful rests.
        val left = input.indexOfFirst { !it.isWhitespace() }
        val right = input.indexOfLast { !it.isWhitespace() }
        val wrapped = left >= 0 && right > left && input[left] == '[' && input[right] == ']'
        var i = if (wrapped) left + 1 else 0
        val end = if (wrapped) right else input.length
        while (i < end) {
            val ch = input[i].uppercaseChar()
            when {
                ch.isWhitespace() -> {
                    if (!legacyWhitespace) result.add(ScoreNote("休符", null))
                    i++
                }
                ch == ',' || ch == '|' -> i++ // Legacy visual separators, never required.
                ch == '_' || ch == '.' || ch == '-' -> {
                    result.add(ScoreNote("休符", null))
                    i++
                }
                ch in "ABCDEFG" -> {
                    i++
                    var accidental = 0
                    var accidentalLabel = ""
                    if (i < end && input[i] == '#') {
                        accidental = 1
                        accidentalLabel = "#"
                        i++
                    } else if (i < end && input[i] == 'b') {
                        accidental = -1
                        accidentalLabel = "b"
                        i++
                    }
                    var octave = 4
                    if (i < end && input[i] in '1'..'6') {
                        octave = input[i].digitToInt()
                        i++
                    }
                    val semitone = when (ch) {
                        'C' -> 0; 'D' -> 2; 'E' -> 4; 'F' -> 5
                        'G' -> 7; 'A' -> 9; else -> 11
                    } + accidental
                    val midi = (octave + 1) * 12 + semitone
                    val hz = 440.0 * 2.0.pow((midi - 69) / 12.0)
                    result.add(ScoreNote("$ch$accidentalLabel$octave", hz))
                }
                else -> error("使用できない文字: '${input[i]}'（位置 ${i + 1}）")
            }
            require(result.size <= MAX_BEATS) { "音符は4096個以内にしてください" }
        }
        require(result.isNotEmpty()) { "楽譜を入力してください" }
        require(result.any { it.frequency != null }) { "音符を1つ以上入力してください" }
        return result
    }
}
