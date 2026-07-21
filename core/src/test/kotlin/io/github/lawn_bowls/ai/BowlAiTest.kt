package io.github.lawn_bowls.ai

import com.badlogic.gdx.math.Vector2
import io.github.lawn_bowls.model.Bowl
import io.github.lawn_bowls.model.Jack
import io.github.lawn_bowls.physics.AussieBowlsPhysics
import io.github.lawn_bowls.rules.AussieRulesEngine
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BowlAiTest {

    private val physics = AussieBowlsPhysics()
    private val rules = AussieRulesEngine()
    // difficulty = 0 -> deterministic, no jitter, for a reproducible assertion on shot quality.
    private val ai = BowlAi(physics, rules, minSpeed = 1.0f, maxSpeed = 3.5f, difficulty = 0f)

    @Test
    fun `chosen speed always stays within the allowed range`() {
        val origin = Vector2(2.5f, 1f)
        val jack = Jack(position = Vector2(2.5f, 20f))

        val plan = ai.chooseDelivery(origin, jack, owner = 1)

        assertTrue(plan.speed in 1.0f..3.5f)
    }

    @Test
    fun `chosen direction is never degenerate`() {
        val origin = Vector2(2.5f, 1f)
        val jack = Jack(position = Vector2(2.5f, 20f))

        val plan = ai.chooseDelivery(origin, jack, owner = 1)

        assertFalse(plan.direction.isZero)
    }

    @Test
    fun `the chosen delivery actually comes to rest reasonably close to the jack`() {
        val origin = Vector2(2.5f, 1f)
        val jack = Jack(position = Vector2(2.5f, 20f))

        val plan = ai.chooseDelivery(origin, jack, owner = 1)

        // Re-simulate the chosen plan the same way the live game would, to check the search
        // actually found a good draw shot rather than something merely plausible-looking.
        val bowl = Bowl(
            position = Vector2(origin),
            velocity = Vector2(plan.direction).scl(plan.speed),
            isBackhand = plan.direction.x > 0f,
            initialSpeed = plan.speed,
            owner = 1
        )
        var steps = 0
        while (bowl.isAlive && !bowl.velocity.isZero && steps < 20 * 60) {
            physics.update(bowl, 1f / 60f)
            rules.checkBowlToJackCollision(bowl, jack)
            rules.updateEntityBounds(bowl, jack)
            steps++
        }

        assertTrue(bowl.isAlive)
        assertTrue(bowl.position.dst(jack.position) < 1.5f)
    }
}
