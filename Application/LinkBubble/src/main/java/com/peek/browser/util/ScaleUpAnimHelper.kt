/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package com.peek.browser.util

import android.animation.Animator
import android.view.View
import android.view.animation.AnticipateOvershootInterpolator

class ScaleUpAnimHelper(private val mView: View, private val mAlpha: Float) {

    enum class AnimState {
        None,
        Hiding,
        Showing,
    }

    var mAnimState = AnimState.None

    fun show() {
        var duration = 667
        if (mView.visibility != View.VISIBLE) {
            duration = 500
            mView.animate().cancel()
            mView.alpha = 0f
            mView.scaleX = 0.33f
            mView.scaleY = 0.33f
        } else if (mAnimState == AnimState.Hiding) {
            mView.animate().cancel()
        }
        mView.visibility = View.VISIBLE

        mView.animate().alpha(mAlpha).scaleX(1f).scaleY(1f)
                .setDuration(duration.toLong())
                .setInterpolator(AnticipateOvershootInterpolator())
                .setListener(mShowListener)
    }

    fun hide() {
        mView.animate().alpha(0f).scaleX(0.33f).scaleY(0.33f)
                .setDuration(500)
                .setInterpolator(AnticipateOvershootInterpolator())
                .setListener(mHideListener)
    }

    // Empty listener is set so that the mHideListener is not still used, potentially setting the view visibilty as GONE
    private val mShowListener = object : Animator.AnimatorListener {
        override fun onAnimationStart(animation: Animator) {
            mAnimState = AnimState.Showing
        }

        override fun onAnimationEnd(animation: Animator) {
            mAnimState = AnimState.None
        }

        override fun onAnimationCancel(animation: Animator) {
        }

        override fun onAnimationRepeat(animation: Animator) {
        }
    }

    private val mHideListener = object : Animator.AnimatorListener {
        override fun onAnimationStart(animation: Animator) {
            mAnimState = AnimState.Hiding
        }

        override fun onAnimationEnd(animation: Animator) {
            mView.visibility = View.GONE
            mAnimState = AnimState.None
        }

        override fun onAnimationCancel(animation: Animator) {
        }

        override fun onAnimationRepeat(animation: Animator) {
        }
    }
}
