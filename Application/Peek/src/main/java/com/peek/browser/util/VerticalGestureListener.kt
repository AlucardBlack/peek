/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package com.peek.browser.util

import android.view.GestureDetector
import android.view.MotionEvent
import com.peek.browser.Config

class VerticalGestureListener : GestureDetector.SimpleOnGestureListener() {

    enum class GestureDirection {
        None,
        Up,
        Down,
        Horizontal,
    }

    private var mLastGestureDirection: GestureDirection? = null

    override fun onFling(e1: MotionEvent?, e2: MotionEvent, velocityX: Float, velocityY: Float): Boolean {
        if (e1 == null) {
            return false
        }
        mLastGestureDirection = GestureDirection.None
        val swipeMinDistance = 5
        val swipeThresholdVelocity = 80

        val relSwipeMinDistance = (swipeMinDistance * Config.sDensityDpi / 160.0f).toInt()
        val relSwipeThresholdVelocity = (swipeThresholdVelocity * Config.sDensityDpi / 160.0f).toInt()

        val swipeVel = Math.abs(velocityY)
        val swipeYDelta = e1.y - e2.y
        val swipeXDelta = e1.x - e2.x

        if (Math.abs(swipeXDelta) > Math.abs(swipeYDelta) * .67f) {
            mLastGestureDirection = GestureDirection.Horizontal
            return false
        }

        if (swipeVel > relSwipeThresholdVelocity) {
            if (swipeYDelta > relSwipeMinDistance) {
                mLastGestureDirection = GestureDirection.Up
            } else if (swipeYDelta < -relSwipeMinDistance) {
                mLastGestureDirection = GestureDirection.Down
            }
        }
        return false
    }

    fun getLastGestureDirection(): GestureDirection? {
        return mLastGestureDirection
    }

    fun resetLastGestureDirection() {
        mLastGestureDirection = GestureDirection.None
    }
}
