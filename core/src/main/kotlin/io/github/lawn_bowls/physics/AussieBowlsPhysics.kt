package io.github.lawn_bowls.physics

import com.badlogic.gdx.math.Vector2
import io.github.lawn_bowls.model.Bowl
import kotlin.math.exp

/**
 * Friction + draw/hook trajectory model for a standard Australian lawn bowls rink
 * (5.0m wide x 35.0m long), tuned for a "15 second green" pace.
 *
 * Two ways to drive it: a standalone simulation via [release]/[update] (own [position]/[velocity]),
 * or live entities via [update] applied directly to a [Bowl]'s own position/velocity each frame.
 */
class AussieBowlsPhysics(
    val rinkWidth: Float = RINK_WIDTH_M,
    val rinkLength: Float = RINK_LENGTH_M,
    private val delivery: Delivery = Delivery.FOREHAND
) {

    /**
     * Which hand the bowl is delivered from. The bias always pushes perpendicular to the bowl's
     * current heading: BACKHAND pulls left of travel, FOREHAND pulls right of travel.
     */
    enum class Delivery(val biasSign: Float) {
        FOREHAND(-1f),
        BACKHAND(1f)
    }

    val position: Vector2 = Vector2()
    val velocity: Vector2 = Vector2()

    private var releaseSpeed = 0f
    private var moving = false

    private val scratch = Vector2()
    private val lateral = Vector2()

    fun release(start: Vector2, direction: Vector2, speedMetersPerSecond: Float) {
        position.set(start)
        velocity.set(direction).nor().scl(speedMetersPerSecond)
        releaseSpeed = speedMetersPerSecond
        moving = releaseSpeed > STOP_THRESHOLD
    }

    fun update(deltaTime: Float) {
        if (!moving) return
        if (!applyStep(position, velocity, releaseSpeed, delivery.biasSign, deltaTime)) {
            moving = false
        }
    }

    /**
     * Applies one friction + hook-bias step directly to [bowl]'s own position/velocity, using its
     * [Bowl.initialSpeed] and [Bowl.isBackhand] to drive the speed ratio and bias direction.
     */
    fun update(bowl: Bowl, deltaTime: Float) {
        if (!bowl.isAlive) return
        val biasSign = if (bowl.isBackhand) Delivery.BACKHAND.biasSign else Delivery.FOREHAND.biasSign
        applyStep(bowl.position, bowl.velocity, bowl.initialSpeed, biasSign, deltaTime)
    }

    /**
     * One physics step: linear ground friction opposing the current heading, calibrated so a bowl
     * at [releaseSpeed] stops in [TARGET_STOP_TIME_SECONDS], plus the sideways draw/hook bias.
     * Returns false once the object has come to rest (velocity zeroed, nothing left to integrate).
     */
    private fun applyStep(
        position: Vector2,
        velocity: Vector2,
        releaseSpeed: Float,
        biasSign: Float,
        deltaTime: Float
    ): Boolean {
        val speed = velocity.len()
        if (speed <= STOP_THRESHOLD || releaseSpeed <= STOP_THRESHOLD) {
            velocity.setZero()
            return false
        }

        val decel = (FRICTION_DECELERATION * deltaTime).coerceAtMost(speed)
        scratch.set(velocity).nor().scl(-decel)
        velocity.add(scratch)

        // Sideways bias perpendicular to travel: the bowl's natural draw/curl toward the jack.
        // (-vy, vx) is the left-of-heading unit vector; biasSign flips it for forehand vs backhand.
        val speedRatio = speed / releaseSpeed
        // Taper the bias out as the bowl nears a dead stop: without this, a strong bias can
        // re-inject speed via the sideways kick just as friction is about to zero it out, so the
        // bowl never actually converges to rest (it did — repeatedly — while tuning this).
        val taper = (speed / TAPER_FLOOR_SPEED).coerceAtMost(1f)
        val biasMagnitude = lateralBiasMagnitude(speedRatio) * taper
        lateral.set(-velocity.y, velocity.x).nor().scl(biasSign * biasMagnitude * deltaTime)
        velocity.add(lateral)

        position.mulAdd(velocity, deltaTime)
        return true
    }

    /**
     * Bias stays constant while speed is at or above [HOOK_TRIGGER_RATIO] of release speed; once it
     * drops below that, bias ramps up exponentially, producing the late "hook" into the jack.
     */
    private fun lateralBiasMagnitude(speedRatio: Float): Float {
        if (speedRatio >= HOOK_TRIGGER_RATIO) return BASE_BIAS_FORCE
        val depletion = (HOOK_TRIGGER_RATIO - speedRatio) / HOOK_TRIGGER_RATIO
        return BASE_BIAS_FORCE * exp(HOOK_EXPONENT * depletion)
    }

    val isMoving: Boolean get() = moving

    val isInBounds: Boolean get() = position.x in 0f..rinkWidth && position.y in 0f..rinkLength

    companion object {
        const val RINK_WIDTH_M = 5.0f
        const val RINK_LENGTH_M = 35.0f

        const val TARGET_STOP_TIME_SECONDS = 15f
        const val REFERENCE_RELEASE_SPEED = 2.75f // m/s draw-hand delivery on a 15s green

        // v(t) = v0 - a*t, solved for a so the reference delivery stops at TARGET_STOP_TIME_SECONDS.
        val FRICTION_DECELERATION = REFERENCE_RELEASE_SPEED / TARGET_STOP_TIME_SECONDS

        const val STOP_THRESHOLD = 0.02f

        const val BASE_BIAS_FORCE = 0.015f
        // Under constant deceleration, speed ratio and distance traveled aren't proportional: with
        // s = fraction of total roll TIME elapsed, distance-fraction = 2s - s^2. speedRatio = 1-s,
        // so a 0.40 trigger (s=0.6) only fired at 2(0.6)-0.6^2 = 84% of the DISTANCE down the rink —
        // it read as "only kicks in at the very end" rather than "from halfway." Solving
        // 2s - s^2 = 0.5 gives s ~= 0.293, i.e. speedRatio ~= 0.71, for the hook to start at the
        // rink's halfway point by distance. Raising the trigger this much widens the ramp-active
        // window enough on its own that BASE_BIAS_FORCE/HOOK_EXPONENT didn't need to go up too —
        // total sideways drift over a full roll is already ~60% more than the original 0.40/0.015/3.5
        // tuning, just correctly positioned starting from halfway instead of the last 16%.
        const val HOOK_TRIGGER_RATIO = 0.70f
        const val HOOK_EXPONENT = 3.5f
        // Below this forward speed, bias tapers toward zero (see [applyStep]) so it can't stop the
        // bowl from ever coming to rest.
        const val TAPER_FLOOR_SPEED = 0.15f
    }
}
