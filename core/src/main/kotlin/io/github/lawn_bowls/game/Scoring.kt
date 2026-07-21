package io.github.lawn_bowls.game

import io.github.lawn_bowls.model.Bowl
import io.github.lawn_bowls.model.Jack
import kotlin.jvm.JvmStatic

/**
 * Result of scoring a single completed end: [winner] (null if void or no bowls counted) takes
 * [shots] points. [isVoid] means the end doesn't count at all (jack knocked dead) and should be
 * replayed rather than scored.
 */
data class EndResult(val winner: Int?, val shots: Int, val isVoid: Boolean)

/** Computes the result of a completed end from the bowls/jack's final resting state. */
object Scoring {

    /**
     * The player closest to the jack scores one shot per consecutive alive bowl of theirs, counted
     * out from the jack until the nearest bowl of the other player is reached. Only [Bowl.isAlive]
     * bowls are considered — this already includes touchers resting in the ditch and excludes
     * dead/out-of-bounds bowls, per [io.github.lawn_bowls.rules.AussieRulesEngine]'s ditch/bounds
     * handling.
     */
    @JvmStatic
    fun scoreEnd(bowls: Iterable<Bowl>, jack: Jack): EndResult {
        if (!jack.isAlive) return EndResult(winner = null, shots = 0, isVoid = true)

        val ranked = bowls.filter { it.isAlive }.sortedBy { it.position.dst(jack.position) }
        if (ranked.isEmpty()) return EndResult(winner = null, shots = 0, isVoid = false)

        val closestOwner = ranked.first().owner
        val shots = ranked.takeWhile { it.owner == closestOwner }.size
        return EndResult(winner = closestOwner, shots = shots, isVoid = false)
    }
}
