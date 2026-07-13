/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package com.peek.browser.ui

import android.content.Context
import android.graphics.Typeface
import android.util.AttributeSet
import android.widget.TextView

class CondensedTextView @JvmOverloads constructor(
        context: Context, attrs: AttributeSet? = null, defStyle: Int = 0
) : TextView(context, attrs, defStyle) {

    init {
        if (!isInEditMode) {
            var typeface = sCustomTypeface
            if (typeface == null) {
                typeface = Typeface.createFromAsset(context.assets, "RobotoCondensed-Regular.ttf")
                sCustomTypeface = typeface
            }
            setTypeface(typeface)
        }
    }

    companion object {
        private var sCustomTypeface: Typeface? = null
    }
}
