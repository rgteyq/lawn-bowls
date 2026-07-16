package io.github.lawn_bowls.game

import com.badlogic.gdx.math.Vector2
import io.github.lawn_bowls.model.Bowl
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class EndTest {

    @Test
    fun `players alternate one delivery at a time starting with player 0`() {
        val end = End(bowlsPerPlayer = 2)

        assertEquals(0, end.currentPlayer)
        end.recordDelivery()
        assertEquals(1, end.currentPlayer)
        end.recordDelivery()
        assertEquals(0, end.currentPlayer)
    }

    @Test
    fun `bowlsRemaining tracks each player independently`() {
        val end = End(bowlsPerPlayer = 3)

        end.recordDelivery() // player 0
        end.recordDelivery() // player 1

        assertEquals(2, end.bowlsRemaining(0))
        assertEquals(2, end.bowlsRemaining(1))
    }

    @Test
    fun `end completes only once both players deliver all their bowls`() {
        val end = End(bowlsPerPlayer = 2)

        repeat(3) { end.recordDelivery() } // p0, p1, p0 -> p0 done, p1 has 1 left
        assertFalse(end.isComplete)

        end.recordDelivery() // p1's second and final bowl
        assertTrue(end.isComplete)
    }

    @Test
    fun `canDeliver is false while a bowl is still moving and true once everything is at rest`() {
        val end = End(bowlsPerPlayer = 4)
        val moving = Bowl(velocity = Vector2(1f, 0f))
        val stopped = Bowl(velocity = Vector2(0f, 0f))
        val dead = Bowl(velocity = Vector2(2f, 0f), isAlive = false)

        assertFalse(end.canDeliver(listOf(moving, stopped)))
        assertTrue(end.canDeliver(listOf(stopped, dead)))
    }

    @Test
    fun `canDeliver is false once the end is complete even with no bowls moving`() {
        val end = End(bowlsPerPlayer = 1)
        end.recordDelivery()
        end.recordDelivery()

        assertTrue(end.isComplete)
        assertFalse(end.canDeliver(emptyList()))
    }
}
