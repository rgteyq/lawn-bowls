package io.github.lawn_bowls.game

import io.github.lawn_bowls.model.Bowl

/**
 * Turn order and bowl allocation for a single "end" between two players (index 0 and 1): they
 * alternate single deliveries until both have delivered [bowlsPerPlayer] bowls.
 */
class End(val bowlsPerPlayer: Int = 4, val startingPlayer: Int = 0) {

    var currentPlayer: Int = startingPlayer
        private set

    private val delivered = IntArray(2)

    val isComplete: Boolean
        get() = delivered[0] >= bowlsPerPlayer && delivered[1] >= bowlsPerPlayer

    fun bowlsRemaining(player: Int): Int = bowlsPerPlayer - delivered[player]

    /** True once every bowl already in play has come to rest (dead or stopped). */
    fun allSettled(bowls: Iterable<Bowl>): Boolean = bowls.none { it.isAlive && !it.velocity.isZero }

    /**
     * Safe for [currentPlayer] to deliver: the end isn't finished yet, and every bowl already in
     * play has come to rest (dead or stopped) — deliveries happen one at a time, never mid-roll.
     */
    fun canDeliver(bowls: Iterable<Bowl>): Boolean = !isComplete && allSettled(bowls)

    /** Records that [currentPlayer] just delivered a bowl and hands the turn to the other player. */
    fun recordDelivery() {
        delivered[currentPlayer]++
        currentPlayer = 1 - currentPlayer
    }
}
