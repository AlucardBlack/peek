/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package com.peek.browser.physics

class Circle(x: Float, y: Float, r: Float) {
    @JvmField var mX: Float = 0f
    @JvmField var mY: Float = 0f
    @JvmField var mRadius: Float = 0f

    init {
        Update(x, y, r)
    }

    fun Update(x: Float, y: Float, r: Float) {
        mX = x
        mY = y
        mRadius = r
    }

    fun Intersects(c: Circle, radiusScaler: Float): Boolean {
        val r0 = mRadius * radiusScaler
        val r1 = c.mRadius * radiusScaler

        val d1 = (mX - c.mX) * (mX - c.mX) + (mY - c.mY) * (mY - c.mY)
        val d2 = (r0 + r1) * (r0 + r1)

        return d1 <= d2
    }
}
