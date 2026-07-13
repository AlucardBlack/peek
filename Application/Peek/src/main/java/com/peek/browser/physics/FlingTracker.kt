/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package com.peek.browser.physics

import android.view.MotionEvent

class FlingTracker {
    val MAX_EVENTS = 8
    val DECAY = 0.75f

    private var mEventBufSize = 0
    private var mEventBufPos = 0
    private val mEventBuf = arrayOfNulls<MotionEventCopy>(MAX_EVENTS)
    var mVX = 0f
    var mVY = 0f

    private class MotionEventCopy(var x: Float, var y: Float, var t: Long)

    init {
        for (i in mEventBuf.indices) {
            mEventBuf[i] = MotionEventCopy(0f, 0f, 0)
        }
    }

    fun addMovement(event: MotionEvent) {
        if (mEventBufSize == MAX_EVENTS) {
            mEventBufPos = (mEventBufPos + 1) % MAX_EVENTS
        } else {
            mEventBufSize++
        }

        val me = mEventBuf[(mEventBufSize - 1 + mEventBufPos) % MAX_EVENTS]!!
        me.x = event.x
        me.y = event.y
        me.t = event.eventTime
    }

    fun computeCurrentVelocity(timebase: Long) {
        //if (FlingTracker.DEBUG) {
        //    Slog.v("FlingTracker", "computing velocities for " + mEventBuf.size() + " events");
        //}
        mVX = 0f
        mVY = 0f
        var last: MotionEventCopy? = null
        var i = 0
        var j = 0
        var totalweight = 0f
        var weight = 10f
        for (x in 0 until mEventBufSize) {
            val event = mEventBuf[(MAX_EVENTS + mEventBufSize + mEventBufPos - 1 - x) % MAX_EVENTS]!!
            if (last != null) {
                val dt = (event.t - last.t).toFloat() / timebase
                if (dt == 0f) {
                    last = event
                    continue
                }
                val dx = event.x - last.x
                val dy = event.y - last.y
                //if (FlingTracker.DEBUG) {
                //    Slog.v("FlingTracker", String.format(" [%d] dx=%.1f dy=%.1f dt=%.0f vx=%.1f vy=%.1f",
                //            i,
                //            dx, dy, dt,
                //            (dx/dt),
                //            (dy/dt)
                //    ));
                //}
                mVX += weight * dx / dt
                mVY += weight * dy / dt
                totalweight += weight
                weight *= DECAY
                j++
            }
            last = event
            i++
        }
        if (j != 0) {
            mVX /= totalweight
            mVY /= totalweight
        }

        //if (FlingTracker.DEBUG) {
        //    Slog.v("FlingTracker", "computed: vx=" + mVX + " vy=" + mVY);
        //}
    }

    fun getXVelocity(): Float {
        return mVX
    }

    fun getYVelocity(): Float {
        return mVY
    }

    fun recycle() {
        mEventBufSize = 0
        mEventBufPos = 0
    }

    companion object {
        private const val DEBUG = false

        private var sTracker: FlingTracker? = null

        @JvmStatic
        fun obtain(): FlingTracker {
            var tracker = sTracker
            if (tracker == null) {
                tracker = FlingTracker()
                sTracker = tracker
            }
            return tracker
        }
    }
}
