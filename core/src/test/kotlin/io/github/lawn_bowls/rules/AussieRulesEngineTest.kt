package io.github.lawn_bowls.rules

import com.badlogic.gdx.math.Vector2
import io.github.lawn_bowls.model.Bowl
import io.github.lawn_bowls.model.Jack
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AussieRulesEngineTest {

    private val engine = AussieRulesEngine()

    @Test
    fun `a moving bowl outside the side boundary is not killed while still rolling`() {
        val bowl = Bowl(position = Vector2(-0.2f, 10f), velocity = Vector2(1f, 0f))
        val jack = Jack(position = Vector2(2.5f, 20f))

        engine.updateEntityBounds(bowl, jack)

        assertTrue(bowl.isAlive)
    }

    @Test
    fun `a bowl at rest outside the side boundary is dead`() {
        val bowl = Bowl(position = Vector2(-0.2f, 10f), velocity = Vector2(0f, 0f))
        val jack = Jack(position = Vector2(2.5f, 20f))

        engine.updateEntityBounds(bowl, jack)

        assertFalse(bowl.isAlive)
    }

    @Test
    fun `a bowl at rest inside the side boundary stays alive`() {
        val bowl = Bowl(position = Vector2(2.5f, 10f), velocity = Vector2(0f, 0f))
        val jack = Jack(position = Vector2(2.5f, 20f))

        engine.updateEntityBounds(bowl, jack)

        assertTrue(bowl.isAlive)
    }

    @Test
    fun `the jack is killed by crossing the side boundary immediately, unlike a bowl`() {
        val bowl = Bowl(position = Vector2(2.5f, 10f), velocity = Vector2(0f, 0f))
        val jack = Jack(position = Vector2(-0.1f, 20f), velocity = Vector2(1f, 0f))

        engine.updateEntityBounds(bowl, jack)

        assertFalse(jack.isAlive)
    }
}
