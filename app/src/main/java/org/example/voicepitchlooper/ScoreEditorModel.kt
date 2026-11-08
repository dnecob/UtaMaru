package org.example.voicepitchlooper

/** Editable score model. Each cell represents exactly one beat, as in the original app. */
class ScoreEditorModel(source: List<ScoreNote>) {
    private val items = source.toMutableList()

    init {
        require(items.isNotEmpty() && items.size <= 256)
        // A three-beat pasted ending must still provide an editable fourth beat.
        // All later measure operations assume the model has complete 4/4 bars.
        while (items.size % 4 != 0) items.add(ScoreNote("休符", null))
    }

    companion object {
        /** Returns diatonic position, alteration and octave for staff rendering. */
        fun pitchParts(label: String): Triple<Int, Int, Int>? {
            val m = Regex("^([A-G])([#b]?)([1-6])$").matchEntire(label) ?: return null
            val diatonic = when (m.groupValues[1]) {
                "C" -> 0; "D" -> 1; "E" -> 2; "F" -> 3;
                "G" -> 4; "A" -> 5; else -> 6
            }
            val alteration = when (m.groupValues[2]) { "#" -> 1; "b" -> -1; else -> 0 }
            return Triple(diatonic, alteration, m.groupValues[3].toInt())
        }
        fun semitoneOf(label: String): Int {
            val parts = pitchParts(label) ?: error("音名を読み取れません: $label")
            val natural = intArrayOf(0, 2, 4, 5, 7, 9, 11)[parts.first]
            return Math.floorMod(natural + parts.second, 12)
        }
    }

    val size: Int get() = items.size
    fun noteAt(step: Int): ScoreNote = items[step]
    fun notes(): List<ScoreNote> = items.toList()

    /** Natural notes and sharps; flats from a text score are retained until edited. */
    fun setPitch(step: Int, semitoneInOctave: Int, octave: Int) {
        require(step in items.indices && semitoneInOctave in 0..11 && octave in 1..6)
        val names = arrayOf("C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B")
        items[step] = ScoreParser.parse(names[semitoneInOctave] + octave).single()
    }

    fun togglePitch(step: Int, semitone: Int, octave: Int) {
        val previous = items[step]
        val newLabel = arrayOf("C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B")[semitone] + octave
        if (previous.label == newLabel) setRest(step) else setPitch(step, semitone, octave)
    }

    fun setRest(step: Int) {
        items[step] = ScoreNote("休符", null)
    }

    /** Pad to a chosen number of beats without truncating an existing score. */
    fun ensureAtLeastBeats(beats: Int) {
        require(beats in 1..256)
        while (items.size < beats) items.add(ScoreNote("休符", null))
    }

    fun appendRests(count: Int) {
        require(count > 0)
        check(items.size + count <= 256) { "音符は最大256拍（64小節）までです" }
        repeat(count) { items.add(ScoreNote("休符", null)) }
    }

    /** Whole-measure insertion keeps 4/4 measure boundaries aligned. */
    fun insertRests(index: Int, count: Int) {
        require(count > 0 && count % 4 == 0 && index in 0..items.size)
        check(items.size + count <= 256) { "音符は最大256拍（64小節）までです" }
        repeat(count) { items.add(index, ScoreNote("休符", null)) }
    }

    /** Truncate only one complete measure, never below two measures. */
    fun removeLastMeasure() {
        check(items.size >= 12) { "2小節（8拍）より短くできません" }
        repeat(4) { items.removeAt(items.lastIndex) }
    }

    fun lastMeasureHasNotes(): Boolean = items.takeLast(4).any { it.frequency != null }

    /** Restore a full editor snapshot, including trailing rests and exact note spelling. */
    fun restoreNotes(snapshot: List<ScoreNote>) {
        require(snapshot.isNotEmpty() && snapshot.size <= 256)
        items.clear()
        items.addAll(ScoreParser.completeMeasures(snapshot))
    }

    /** Keep the selected measure; delete every beat after it (whole measures in a 4/4 score). */
    fun removeAllMeasuresAfter(selectedBeat: Int): Int {
        require(selectedBeat in items.indices)
        val retainedBeats = ((selectedBeat / 4) + 1) * 4
        // The score editor has always kept two measures as its minimum.
        check(retainedBeats >= 8) { "最低2小節は残します。第2小節以降を選択してください" }
        if (retainedBeats >= items.size) return 0
        val removed = items.size - retainedBeats
        items.subList(retainedBeats, items.size).clear()
        return removed
    }

    /** All-or-nothing octave transposition. Never silently clamp notes outside C1..B6. */
    fun transposeAllOctaves(direction: Int): Int {
        require(direction == -1 || direction == 1)
        val changes = items.mapIndexedNotNull { index, note ->
            if (note.frequency == null) return@mapIndexedNotNull null
            val parts = pitchParts(note.label) ?: error("音名を読み取れません: ${note.label}")
            val next = parts.third + direction
            check(next in 1..6) {
                "${note.label} を移動すると音域C1～B6を超えます。楽譜は変更していません"
            }
            index to ScoreParser.parse(note.label.dropLast(1) + next).single()
        }
        check(changes.isNotEmpty()) { "移動する音符がありません" }
        for ((index, value) in changes) items[index] = value
        return changes.size
    }

    /** Remove only wholly empty trailing bars. Never change the first two bars. */
    fun trimEmptyTrailingMeasures(): Int {
        var removed = 0
        while (items.size >= 12 && items.takeLast(4).all { it.frequency == null }) {
            repeat(4) { items.removeAt(items.lastIndex) }
            removed++
        }
        return removed
    }

    /**
     * Insert one silent beat at the selected position, shifting all later beats right.
     * Keep the 4/4 measure count intact: use one trailing rest when possible,
     * otherwise extend by a whole measure (four beats) before shifting.
     * Never discard a pitched note at the end of the score.
     */
    fun insertRestAndShift(selectedBeat: Int): Boolean {
        require(selectedBeat in items.indices)
        val needNewMeasure = items.last().frequency != null
        if (needNewMeasure) {
            check(items.size + 4 <= 256) {
                "末尾に音符があり、64小節を超えるため休符を挿入できません"
            }
            appendRests(4)
        }
        items.add(selectedBeat, ScoreNote("休符", null))
        // The final beat must be a rest, since we either had a trailing rest
        // or just appended four empty beats. The shifted note is never lost.
        check(items.last().frequency == null)
        items.removeAt(items.lastIndex)
        return needNewMeasure
    }

    /**
     * Delete the selected beat, shift every later beat left by one,
     * and place one silent beat at the end, preserving 4/4 measure boundaries.
     * Also supports a rest at the selection, since it is a beat deletion.
     */
    fun deleteBeatAndCloseGap(selectedBeat: Int) {
        require(selectedBeat in items.indices)
        items.removeAt(selectedBeat)
        items.add(ScoreNote("休符", null))
    }

    fun appendRest() = appendRests(1)

    fun insertRest(index: Int) {
        check(items.size < 256) { "音符は最大256拍までです" }
        require(index in 0..items.size)
        items.add(index, ScoreNote("休符", null))
    }

    fun delete(step: Int) {
        check(items.size > 1) { "楽譜は最低1拍必要です" }
        items.removeAt(step)
    }

    fun toScoreText(): String {
        require(items.any { it.frequency != null }) { "音符を1つ以上配置してください" }
        return ScoreParser.serialize(items)
    }
}
