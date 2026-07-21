package io.github.lawn_bowls.game

/**
 * Accumulates shots across ends for a two-player match capped at [maxEnds]: once [endsPlayed]
 * reaches [maxEnds], the match is over as soon as the scores aren't level — a tie keeps the match
 * going with further tie-break ends (still recorded via [recordEnd]) until it's broken.
 */
class Match(private val maxEnds: Int = 7) {

    val scores = IntArray(2)

    var endsPlayed = 0
        private set

    val isComplete: Boolean
        get() = endsPlayed >= maxEnds && scores[0] != scores[1]

    val winner: Int?
        get() = if (isComplete) (if (scores[0] > scores[1]) 0 else 1) else null

    /** Void ends (jack knocked dead) don't count toward [endsPlayed] — they're replayed instead. */
    fun recordEnd(result: EndResult) {
        if (result.isVoid) return
        result.winner?.let { scores[it] += result.shots }
        endsPlayed++
    }
}
