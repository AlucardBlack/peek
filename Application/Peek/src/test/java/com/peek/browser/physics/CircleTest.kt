/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package com.peek.browser.physics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [Circle], a pure-geometry helper with no Android dependencies.
 * Serves as the first entry in the JVM unit-test suite.
 */
class CircleTest {

    @Test
    fun constructorStoresFields() {
        val c = Circle(1f, 2f, 3f)
        assertEquals(1f, c.mX, 0f)
        assertEquals(2f, c.mY, 0f)
        assertEquals(3f, c.mRadius, 0f)
    }

    @Test
    fun updateReplacesFields() {
        val c = Circle(0f, 0f, 1f)
        c.Update(-4f, 5f, 6f)
        assertEquals(-4f, c.mX, 0f)
        assertEquals(5f, c.mY, 0f)
        assertEquals(6f, c.mRadius, 0f)
    }

    @Test
    fun overlappingCirclesIntersect() {
        // Centers 4 apart, radii 3 + 3 = 6 > 4 -> overlap.
        val a = Circle(0f, 0f, 3f)
        val b = Circle(4f, 0f, 3f)
        assertTrue(a.Intersects(b, 1f))
    }

    @Test
    fun tangentCirclesIntersectAtBoundary() {
        // Centers 5 apart (3-4-5), radii 2 + 3 = 5 -> d1 == d2, boundary is inclusive.
        val a = Circle(0f, 0f, 2f)
        val b = Circle(3f, 4f, 3f)
        assertTrue(a.Intersects(b, 1f))
    }

    @Test
    fun disjointCirclesDoNotIntersect() {
        // Centers 10 apart, radii 2 + 3 = 5 < 10 -> no overlap.
        val a = Circle(0f, 0f, 2f)
        val b = Circle(10f, 0f, 3f)
        assertFalse(a.Intersects(b, 1f))
    }

    @Test
    fun radiusScalerShrinksEffectiveRadii() {
        // Tangent at scaler 1.0 (radii 3 + 3 = 6, centers 6 apart); halving the
        // radii to 1.5 + 1.5 = 3 < 6 breaks the intersection.
        val a = Circle(0f, 0f, 3f)
        val b = Circle(6f, 0f, 3f)
        assertTrue(a.Intersects(b, 1f))
        assertFalse(a.Intersects(b, 0.5f))
    }
}
