package io.github.lawn_bowls.ai

import com.badlogic.gdx.math.Vector2
import io.github.lawn_bowls.model.Bowl
import io.github.lawn_bowls.model.Jack
import io.github.lawn_bowls.physics.AussieBowlsPhysics
import io.github.lawn_bowls.rules.AussieRulesEngine
import kotlin.random.Random

/** A chosen delivery: travel direction (unit vector) and release speed. */
data class DeliveryPlan(val direction: Vector2, val speed: Float)

/**
 * Chooses a delivery for the computer-controlled player by forward-simulating a grid of
 * candidate (angle, speed) deliveries with the same [AussieBowlsPhysics]/[AussieRulesEngine]
 * the live game uses, and picking whichever comes to rest closest to the jack. No machine
 * learning — the physics are fully deterministic, so an exhaustive-enough search already finds
 * strong shots.
 *
 * Hand (forehand/backhand) is not a separate search variable: exactly like the human delivery
 * path (`Main.releaseBowl`), it's derived from the resulting direction's sign
 * (`direction.x > 0` -> backhand), so the search only ever varies angle and speed.
 */
class BowlAi @JvmOverloads constructor(
    private val physics: AussieBowlsPhysics,
    private val rules: AussieRulesEngine,
    private val minSpeed: Float,
    private val maxSpeed: Float,
    private val difficulty: Float = 0.35f, // 0 = perfect, 1 = heavy jitter
    private val random: Random = Random.Default
) {

    fun chooseDelivery(origin: Vector2, jack: Jack, owner: Int): DeliveryPlan {
        val baseDir = Vector2(jack.position).sub(origin).nor()

        var bestDirection = baseDir
        var bestSpeed = (minSpeed + maxSpeed) / 2f
        var bestScore = Float.NEGATIVE_INFINITY

        for (angleStep in -ANGLE_STEPS..ANGLE_STEPS) {
            val angleDeg = angleStep * (ANGLE_RANGE_DEGREES / ANGLE_STEPS)
            val candidateDir = Vector2(baseDir).rotateDeg(angleDeg)

            for (speedStep in 0 until SPEED_STEPS) {
                val speed = minSpeed + (maxSpeed - minSpeed) * speedStep / (SPEED_STEPS - 1)
                val score = simulate(origin, candidateDir, speed, jack, owner)
                if (score > bestScore) {
                    bestScore = score
                    bestDirection = candidateDir
                    bestSpeed = speed
                }
            }
        }

        // Imprecise "execution" of the otherwise-best-found shot, scaled by difficulty, so the
        // AI is beatable rather than always finding the objectively best move on the grid.
        val jitterAngle = (random.nextFloat() * 2f - 1f) * MAX_ANGLE_JITTER_DEGREES * difficulty
        val jitterSpeed = (random.nextFloat() * 2f - 1f) * MAX_SPEED_JITTER * difficulty
        val executedDirection = Vector2(bestDirection).rotateDeg(jitterAngle)
        val executedSpeed = (bestSpeed + jitterSpeed).coerceIn(minSpeed, maxSpeed)

        return DeliveryPlan(executedDirection, executedSpeed)
    }

    /**
     * Forward-simulates one candidate delivery to rest against throwaway copies of the bowl and
     * jack — never the live ones — and scores the outcome: closer to the jack is better, dying
     * (off the side, or in the ditch as a non-toucher) is heavily penalized. `Jack`/`Bowl` are
     * data classes, but `.copy()` would only shallow-copy their `Vector2` fields, silently
     * sharing position/velocity with the real entities — copies are built explicitly with their
     * own new `Vector2`s instead.
     */
    private fun simulate(origin: Vector2, direction: Vector2, speed: Float, jack: Jack, owner: Int): Float {
        val scratchBowl = Bowl(
            position = Vector2(origin),
            velocity = Vector2(direction).scl(speed),
            isBackhand = direction.x > 0f,
            initialSpeed = speed,
            owner = owner
        )
        val scratchJack = Jack(
            position = Vector2(jack.position),
            velocity = Vector2(jack.velocity),
            isAlive = jack.isAlive,
            isInDitch = jack.isInDitch
        )

        var steps = 0
        while (scratchBowl.isAlive && !scratchBowl.velocity.isZero && steps < MAX_SIMULATION_STEPS) {
            physics.update(scratchBowl, SIMULATION_DT)
            rules.checkBowlToJackCollision(scratchBowl, scratchJack)
            rules.updateEntityBounds(scratchBowl, scratchJack)
            steps++
        }

        val distance = scratchBowl.position.dst(scratchJack.position)
        if (!scratchBowl.isAlive) {
            return -DEAD_PENALTY - distance
        }
        val toucherBonus = if (scratchBowl.isToucher) TOUCHER_BONUS else 0f
        return -distance + toucherBonus
    }

    companion object {
        private const val ANGLE_RANGE_DEGREES = 14f
        private const val ANGLE_STEPS = 4 // -4..4 -> 9 angles
        private const val SPEED_STEPS = 7

        // Matches the fixed delta the live game steps physics with (Gdx.graphics.getDeltaTime()
        // at a typical 60fps), so a simulated outcome matches what actually happens when this
        // plan is executed for real.
        private const val SIMULATION_DT = 1f / 60f
        private const val MAX_SIMULATION_STEPS = 20 * 60 // 20s safety cap; bowls always settle well before this

        private const val DEAD_PENALTY = 1000f
        private const val TOUCHER_BONUS = 0.5f

        private const val MAX_ANGLE_JITTER_DEGREES = 6f
        private const val MAX_SPEED_JITTER = 0.3f
    }
}
