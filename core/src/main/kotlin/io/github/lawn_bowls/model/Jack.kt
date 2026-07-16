package io.github.lawn_bowls.model

import com.badlogic.gdx.math.Vector2

data class Jack(
    val position: Vector2 = Vector2(),
    val velocity: Vector2 = Vector2(),
    val radius: Float = 0.0315f,
    var isAlive: Boolean = true,
    var isInDitch: Boolean = false
)
