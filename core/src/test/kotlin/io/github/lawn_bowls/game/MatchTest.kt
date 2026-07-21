package io.github.lawn_bowls.game

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MatchTest {

    @Test
    fun `recordEnd accumulates shots for the winner and increments endsPlayed`() {
        val match = Match(maxEnds = 7)

        match.recordEnd(EndResult(winner = 0, shots = 3, isVoid = false))
        match.recordEnd(EndResult(winner = 1, shots = 1, isVoid = false))

        assertEquals(3, match.scores[0])
        assertEquals(1, match.scores[1])
        assertEquals(2, match.endsPlayed)
    }

    @Test
    fun `void result changes neither scores nor endsPlayed`() {
        val match = Match(maxEnds = 7)

        match.recordEnd(EndResult(winner = null, shots = 0, isVoid = true))

        assertEquals(0, match.scores[0])
        assertEquals(0, match.scores[1])
        assertEquals(0, match.endsPlayed)
    }

    @Test
    fun `tied match at the end cap is not complete and keeps playing tie-break ends until broken`() {
        val match = Match(maxEnds = 7)
        // 6 ends alternating 1 shot each -> 3-3, then a scoreless 7th end -> still 3-3 at the cap.
        repeat(6) {
            match.recordEnd(EndResult(winner = it % 2, shots = 1, isVoid = false))
        }
        match.recordEnd(EndResult(winner = null, shots = 0, isVoid = false))

        assertEquals(7, match.endsPlayed)
        assertEquals(match.scores[0], match.scores[1])
        assertFalse(match.isComplete)
        assertNull(match.winner)

        match.recordEnd(EndResult(winner = 0, shots = 2, isVoid = false))

        assertEquals(8, match.endsPlayed)
        assertTrue(match.isComplete)
        assertEquals(0, match.winner)
    }
}
