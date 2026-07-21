package io.github.lawn_bowls.rules

import com.badlogic.gdx.math.Vector2
import io.github.lawn_bowls.model.Bowl
import io.github.lawn_bowls.model.Jack

/**
 * Bowls Australia boundary laws for a standard rink, 1 unit = 1 meter.
 * Width runs [RINK_MIN_X, RINK_MAX_X], the green surface runs [0, GREEN_LENGTH],
 * and the ditch adds DITCH_DEPTH beyond GREEN_LENGTH at the far end, up to DITCH_BACK_WALL.
 */
class AussieRulesEngine {

    fun updateEntityBounds(bowl: Bowl, jack: Jack) {
        checkHorizontalBounds(jack)
        checkHorizontalBounds(bowl)
        updateJackDitch(jack)
        updateBowlDitch(bowl)
    }

    private fun checkHorizontalBounds(jack: Jack) {
        if (jack.position.x <= RINK_MIN_X || jack.position.x >= RINK_MAX_X) {
            jack.isAlive = false
        }
    }

    /**
     * Unlike the jack, a bowl crossing the side boundary mid-roll doesn't kill it outright — only
     * where it ends up at rest matters, so a bowl that runs wide and draws back in before stopping
     * stays alive. [Bowl.velocity] reads as exactly zero once `AussieBowlsPhysics` has settled it
     * (see its `applyStep`), so that's the signal this is safe to evaluate.
     */
    private fun checkHorizontalBounds(bowl: Bowl) {
        if (!bowl.velocity.isZero) return
        if (bowl.position.x <= RINK_MIN_X || bowl.position.x >= RINK_MAX_X) {
            bowl.isAlive = false
        }
    }

    private fun updateJackDitch(jack: Jack) {
        if (jack.position.y <= GREEN_LENGTH) return
        jack.isInDitch = true
        if (jack.position.y >= DITCH_BACK_WALL) {
            jack.position.y = DITCH_BACK_WALL
            jack.velocity.setZero()
        }
    }

    private fun updateBowlDitch(bowl: Bowl) {
        if (bowl.position.y <= GREEN_LENGTH) return
        bowl.isInDitch = true
        if (bowl.isToucher) {
            if (bowl.position.y >= DITCH_BACK_WALL) {
                bowl.position.y = DITCH_BACK_WALL
                bowl.velocity.setZero()
            }
        } else {
            bowl.isAlive = false
            bowl.velocity.setZero()
        }
    }

    /**
     * Circle-to-circle collision (via [Vector2.dst]) between a moving bowl and the jack.
     * Resolves as an equal-mass elastic collision: velocity components along the collision
     * normal are swapped, tangential components are left untouched.
     */
    fun checkBowlToJackCollision(bowl: Bowl, jack: Jack) {
        if (!bowl.isAlive || !jack.isAlive) return
        if (bowl.velocity.isZero) return

        val distance = bowl.position.dst(jack.position)
        if (distance > bowl.radius + jack.radius) return

        val normal = Vector2(bowl.position).sub(jack.position).nor()
        val relativeVelocity = Vector2(bowl.velocity).sub(jack.velocity)
        if (relativeVelocity.dot(normal) >= 0f) return // already separating

        val bowlNormalSpeed = bowl.velocity.dot(normal)
        val jackNormalSpeed = jack.velocity.dot(normal)
        val bowlTangent = Vector2(bowl.velocity).mulAdd(normal, -bowlNormalSpeed)
        val jackTangent = Vector2(jack.velocity).mulAdd(normal, -jackNormalSpeed)
        bowl.velocity.set(bowlTangent).mulAdd(normal, jackNormalSpeed)
        jack.velocity.set(jackTangent).mulAdd(normal, bowlNormalSpeed)

        if (!bowl.isToucher && !bowl.isInDitch && !jack.isInDitch) {
            bowl.isToucher = true
            println("TOUCHER! Bowl struck the jack while running its course on the green.")
        }
    }

    companion object {
        const val RINK_MIN_X = 0.0f
        const val RINK_MAX_X = 5.0f
        const val GREEN_LENGTH = 35.0f
        const val DITCH_DEPTH = 0.3f
        const val DITCH_BACK_WALL = GREEN_LENGTH + DITCH_DEPTH
    }
}
