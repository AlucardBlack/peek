/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package com.peek.browser.ui

import android.content.Context
import android.util.AttributeSet
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.TextView
import com.peek.browser.Config
import com.peek.browser.Constant
import com.peek.browser.MainController
import com.peek.browser.R
import com.peek.browser.Settings
import com.peek.browser.util.ScaleUpAnimHelper

class BadgeView @JvmOverloads constructor(
        context: Context, attrs: AttributeSet? = null, defStyle: Int = 0
) : TextView(context, attrs, defStyle) {

    var mCount: Int = 0
    val mAnimHelper: ScaleUpAnimHelper

    init {
        if (isInEditMode) {
            setTextColor(R.color.color_text_light)
            background = resources.getDrawable(R.drawable.badge_plate)
        }

        background = resources.getDrawable(if (Settings.get().darkThemeEnabled) R.drawable.badge_plate_dark else R.drawable.badge_plate)
        setTextColor(Settings.get().getThemedTextColor())

        mCount = 0
        mAnimHelper = ScaleUpAnimHelper(this, Constant.BUBBLE_MODE_ALPHA)
    }

    fun show() {
        mAnimHelper.show()

        val activeDraggable = MainController.get()!!.getBubbleDraggable()
        val lp = layoutParams as FrameLayout.LayoutParams
        val x = activeDraggable.draggableHelper.getXPos()
        if (x > Config.mScreenCenterX) {
            lp.gravity = Gravity.TOP or Gravity.LEFT
        } else {
            lp.gravity = Gravity.TOP or Gravity.RIGHT
        }
    }

    fun hide() {
        mAnimHelper.hide()
    }

    fun setCount(count: Int) {
        mCount = count
        text = count.toString()
    }
}
