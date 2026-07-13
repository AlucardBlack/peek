/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package com.peek.browser.ui

import android.content.Context
import android.util.AttributeSet
import com.peek.browser.R

class ArticleModeButton @JvmOverloads constructor(
        context: Context, attrs: AttributeSet? = null, defStyle: Int = 0
) : ContentViewButton(context, attrs, defStyle) {

    enum class State {
        Article,
        Web
    }

    private var mState: State? = null

    fun setState(state: State) {
        when (state) {
            State.Article ->
                setImageDrawable(resources.getDrawable(R.drawable.ic_subject_white_24dp))

            State.Web ->
                setImageDrawable(resources.getDrawable(R.drawable.ic_public_white_24dp))
        }

        mState = state
    }

    fun getState(): State? {
        return mState
    }

    fun toggleState() {
        setState(if (mState == State.Article) State.Web else State.Article)
    }
}
