/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package com.peek.browser.ui

import android.content.Context
import android.util.AttributeSet
import android.widget.ImageView
import com.peek.browser.R
import com.peek.browser.Settings

class BubblePlateView @JvmOverloads constructor(
        context: Context, attrs: AttributeSet? = null, defStyle: Int = 0
) : ImageView(context, attrs, defStyle) {

    init {
        if (isInEditMode) {
            setImageDrawable(resources.getDrawable(R.drawable.bubble_plate_light))
        } else {
            setImageDrawable(resources.getDrawable(if (Settings.get().darkThemeEnabled) R.drawable.bubble_plate_dark else R.drawable.bubble_plate_light))
        }
    }
}
