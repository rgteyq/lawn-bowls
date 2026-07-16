package io.github.lawn_bowls.physics

import com.badlogic.gdx.math.Vector2
import io.github.lawn_bowls.model.Bowl
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AussieBowlsPhysicsTest {

    private val dt = 1f / 60f

    @Test
    fun `linear friction brings the bowl to rest around the tuned 15s green pace`() {
        val physics = AussieBowlsPhysics()
        physics.release(Vector2(2.5f, 0f), Vector2(0f, 1f), AussieBowlsPhysics.REFERENCE_RELEASE_SPEED)

        var elapsed = 0f
        while (physics.isMoving && elapsed < 30f) {
            physics.update(dt)
            elapsed += dt
        }

        assertTrue(!physics.isMoving, "bowl should have come to rest within 30s")
        // A wider tolerance than the friction-only figure: a strong hook bias legitimately keeps
        // adding a little sideways energy right up until TAPER_FLOOR_SPEED, so a heavily-biased
        // bowl settles a bit later than TARGET_STOP_TIME_SECONDS, not exactly at it.
        assertEquals(AussieBowlsPhysics.TARGET_STOP_TIME_SECONDS, elapsed, 3f)
    }

    @Test
    fun `lateral hook accelerates from around halfway down the rink, not just at the very end`() {
        val physics = AussieBowlsPhysics(delivery = AussieBowlsPhysics.Delivery.FOREHAND)
        physics.release(Vector2(2.5f, 0f), Vector2(0f, 1f), AussieBowlsPhysics.REFERENCE_RELEASE_SPEED)

        var elapsed = 0f
        var lastX = physics.position.x
        var earlyDrift = 0f // first second: full pace, well above the HOOK_TRIGGER_RATIO
        var lateDrift = 0f  // later: well past halfway down the rink, hook should be ramped up

        while (physics.isMoving && elapsed < 20f) {
            physics.update(dt)
            elapsed += dt
            val drift = abs(physics.position.x - lastX)
            lastX = physics.position.x

            if (elapsed in 1f..2f) earlyDrift += drift
            if (elapsed in 8f..9f) lateDrift += drift
        }

        assertTrue(
            lateDrift > earlyDrift * 3f,
            "hook should ramp up sharply once the bowl is past halfway down the rink: " +
                "early=$earlyDrift late=$lateDrift"
        )
    }

    @Test
    fun `backhand pulls left and forehand pulls right of the bowl's heading`() {
        val forehand = AussieBowlsPhysics(delivery = AussieBowlsPhysics.Delivery.FOREHAND)
        val backhand = AussieBowlsPhysics(delivery = AussieBowlsPhysics.Delivery.BACKHAND)
        // Heading straight up the rink (0, 1): "left" of that heading is -x, "right" is +x.
        forehand.release(Vector2(2.5f, 0f), Vector2(0f, 1f), AussieBowlsPhysics.REFERENCE_RELEASE_SPEED)
        backhand.release(Vector2(2.5f, 0f), Vector2(0f, 1f), AussieBowlsPhysics.REFERENCE_RELEASE_SPEED)

        repeat(60) {
            forehand.update(dt)
            backhand.update(dt)
        }

        val forehandDrift = forehand.position.x - 2.5f
        val backhandDrift = backhand.position.x - 2.5f
        assertTrue(forehandDrift > 0f, "forehand should drift right (+x), was $forehandDrift")
        assertTrue(backhandDrift < 0f, "backhand should drift left (-x), was $backhandDrift")
    }

    @Test
    fun `update(bowl, deltaTime) matches the standalone simulation for the same delivery`() {
        val physics = AussieBowlsPhysics()
        val reference = AussieBowlsPhysics(delivery = AussieBowlsPhysics.Delivery.BACKHAND)
        reference.release(Vector2(2.5f, 0f), Vector2(0f, 1f), AussieBowlsPhysics.REFERENCE_RELEASE_SPEED)

        val bowl = Bowl(
            position = Vector2(2.5f, 0f),
            velocity = Vector2(0f, AussieBowlsPhysics.REFERENCE_RELEASE_SPEED),
            isBackhand = true,
            initialSpeed = AussieBowlsPhysics.REFERENCE_RELEASE_SPEED
        )

        repeat(600) {
            reference.update(dt)
            physics.update(bowl, dt)
        }

        assertEquals(reference.position.x, bowl.position.x, 1e-4f)
        assertEquals(reference.position.y, bowl.position.y, 1e-4f)
        assertEquals(reference.velocity.x, bowl.velocity.x, 1e-4f)
        assertEquals(reference.velocity.y, bowl.velocity.y, 1e-4f)
    }

    @Test
    fun `update(bowl, deltaTime) leaves a dead bowl untouched`() {
        val physics = AussieBowlsPhysics()
        val bowl = Bowl(
            position = Vector2(2.5f, 10f),
            velocity = Vector2(0f, 1.5f),
            initialSpeed = 1.5f,
            isAlive = false
        )

        physics.update(bowl, dt)

        assertEquals(2.5f, bowl.position.x)
        assertEquals(10f, bowl.position.y)
        assertEquals(1.5f, bowl.velocity.y)
    }
}
