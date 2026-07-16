package io.github.lawn_bowls.model

import com.badlogic.gdx.math.Vector2

data class Bowl(
    val position: Vector2 = Vector2(),
    val velocity: Vector2 = Vector2(),
    val radius: Float = 0.065f,
    val isBackhand: Boolean = false,
    val initialSpeed: Float = 0.0f,
    val owner: Int = 0,
    var isAlive: Boolean = true,
    var isToucher: Boolean = false,
    var isInDitch: Boolean = false
)
