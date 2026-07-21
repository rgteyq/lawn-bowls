package io.github.lawn_bowls.game

import com.badlogic.gdx.math.Vector2
import io.github.lawn_bowls.model.Bowl
import io.github.lawn_bowls.model.Jack
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ScoringTest {

    private val jack = Jack(position = Vector2(2.5f, 25f))

    @Test
    fun `single closest bowl wins one shot`() {
        val closest = Bowl(position = Vector2(2.5f, 25.1f), owner = 0)
        val farther = Bowl(position = Vector2(2.5f, 26f), owner = 1)

        val result = Scoring.scoreEnd(listOf(closest, farther), jack)

        assertEquals(0, result.winner)
        assertEquals(1, result.shots)
        assertFalse(result.isVoid)
    }

    @Test
    fun `consecutive closest bowls from the same owner all count as shots`() {
        val bowls = listOf(
            Bowl(position = Vector2(2.5f, 25.05f), owner = 1),
            Bowl(position = Vector2(2.5f, 25.10f), owner = 1),
            Bowl(position = Vector2(2.5f, 25.15f), owner = 1),
            Bowl(position = Vector2(2.5f, 25.20f), owner = 0),
        )

        val result = Scoring.scoreEnd(bowls, jack)

        assertEquals(1, result.winner)
        assertEquals(3, result.shots)
    }

    @Test
    fun `dead bowl is excluded even if geometrically closest`() {
        val dead = Bowl(position = Vector2(2.5f, 25.01f), owner = 0, isAlive = false)
        val alive = Bowl(position = Vector2(2.5f, 26f), owner = 1)

        val result = Scoring.scoreEnd(listOf(dead, alive), jack)

        assertEquals(1, result.winner)
        assertEquals(1, result.shots)
    }

    @Test
    fun `toucher resting in the ditch still counts`() {
        val toucher = Bowl(
            position = Vector2(2.5f, 25.05f), owner = 0, isAlive = true, isToucher = true, isInDitch = true
        )
        val onGreen = Bowl(position = Vector2(2.5f, 26f), owner = 1)

        val result = Scoring.scoreEnd(listOf(toucher, onGreen), jack)

        assertEquals(0, result.winner)
        assertEquals(1, result.shots)
    }

    @Test
    fun `dead jack produces a void result regardless of bowl positions`() {
        val deadJack = Jack(position = Vector2(2.5f, 25f), isAlive = false)
        val bowl = Bowl(position = Vector2(2.5f, 25.01f), owner = 0)

        val result = Scoring.scoreEnd(listOf(bowl), deadJack)

        assertTrue(result.isVoid)
        assertNull(result.winner)
        assertEquals(0, result.shots)
    }
}
