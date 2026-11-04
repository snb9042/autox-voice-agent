package com.stardust.autojs.core.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BoundsTest {

    @Test
    fun `centerX and centerY compute the midpoint`() {
        val bounds = Bounds(0, 0, 100, 50)
        assertEquals(50, bounds.centerX())
        assertEquals(25, bounds.centerY())
    }

    @Test
    fun `distanceTo is zero for identical bounds`() {
        val a = Bounds(10, 10, 20, 20)
        val b = Bounds(10, 10, 20, 20)
        assertEquals(0.0, a.distanceTo(b), 0.0001)
    }

    @Test
    fun `closestTo picks the candidate nearest the target center`() {
        val target = Bounds(100, 100, 120, 120)
        val near = Bounds(105, 105, 125, 125)
        val far = Bounds(500, 500, 520, 520)
        val result = Bounds.closestTo(target, listOf(far, near))
        assertEquals(near, result)
    }

    @Test
    fun `closestTo returns null for an empty candidate list`() {
        assertNull(Bounds.closestTo(Bounds(0, 0, 10, 10), emptyList()))
    }

    @Test
    fun `distanceTo returns true Euclidean distance, not squared distance`() {
        val a = Bounds(0, 0, 0, 0)
        val b = Bounds(0, 0, 12, 16) // center at (6, 8) -> 3-4-5-scaled right triangle, distance 10
        assertEquals(10.0, a.distanceTo(b), 0.0001)
    }
}
