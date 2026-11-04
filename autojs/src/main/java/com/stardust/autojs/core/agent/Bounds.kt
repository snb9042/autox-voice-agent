package com.stardust.autojs.core.agent

import android.graphics.Rect
import kotlin.math.sqrt

data class Bounds(val left: Int, val top: Int, val right: Int, val bottom: Int) {

    fun centerX(): Int = (left + right) / 2
    fun centerY(): Int = (top + bottom) / 2

    fun distanceTo(other: Bounds): Double {
        val dx = (centerX() - other.centerX()).toDouble()
        val dy = (centerY() - other.centerY()).toDouble()
        return sqrt(dx * dx + dy * dy)
    }

    companion object {
        fun fromRect(rect: Rect): Bounds = Bounds(rect.left, rect.top, rect.right, rect.bottom)

        fun closestTo(target: Bounds, candidates: List<Bounds>): Bounds? =
            candidates.minByOrNull { it.distanceTo(target) }
    }
}
