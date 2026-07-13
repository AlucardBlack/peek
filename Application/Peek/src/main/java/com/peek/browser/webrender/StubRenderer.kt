/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package com.peek.browser.webrender

import android.content.Context
import android.view.View
import com.peek.browser.util.Util
import java.net.URL

class StubRenderer(context: Context, controller: Controller, webRendererPlaceholder: View, tag: String) : WebRenderer(context, controller, webRendererPlaceholder) {

    val mView: View = View(context)

    init {
        mView.layoutParams = webRendererPlaceholder.layoutParams
        Util.replaceViewAtPosition(webRendererPlaceholder, mView)
        mView.setBackgroundColor(-0x560000)
    }

    override fun destroy() {

    }

    override fun getView(): View? {
        return null
    }

    override fun updateIncognitoMode(incognito: Boolean) {

    }

    override fun loadUrl(url: URL, mode: Mode) {

    }

    override fun reload() {

    }

    override fun stopLoading() {

    }

    override fun hidePopups() {

    }

    override fun resetPageInspector() {

    }

    override fun resumeOnSetActive() {

    }

    override fun pauseOnSetInactive() {

    }

    override fun getUserAgentString(context: Context): String? {
        return null
    }

    override fun setUserAgentString(userAgentString: String) {
    }
}
