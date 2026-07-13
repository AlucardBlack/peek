/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package com.peek.browser.webrender

import android.content.Context
import android.content.ContextWrapper
import android.content.res.Resources
import com.peek.browser.Constant
import com.peek.browser.ui.ExpandedActivity

class WebRendererContextWrapper(base: Context) : ContextWrapper(base) {

    override fun getTheme(): Resources.Theme {
        if (Constant.ACTIVITY_WEBVIEW_RENDERING && ExpandedActivity.get() != null) {
            return ExpandedActivity.get()!!.theme
        }
        return super.getTheme()
    }

    override fun getSystemService(name: String): Any? {
        if (Constant.ACTIVITY_WEBVIEW_RENDERING && ExpandedActivity.get() != null) {
            return ExpandedActivity.get()!!.getSystemService(name)
        }
        return super.getSystemService(name)
    }

        /*
        @Override
        public void startIntentSender(IntentSender intent,
                                      Intent fillInIntent, int flagsMask, int flagsValues, int extraFlags)
                throws IntentSender.SendIntentException {
            super.startIntentSender(intent, fillInIntent, flagsMask,
                    flagsValues, extraFlags);
        }

        @Override
        public void startIntentSender(IntentSender intent,
                                      Intent fillInIntent, int flagsMask, int flagsValues, int extraFlags,
                                      Bundle options) throws IntentSender.SendIntentException {
            super.startIntentSender(intent, fillInIntent, flagsMask,
                    flagsValues, extraFlags, options);
        }

        @Override
        public void startActivity(Intent intent) {
            Log.e("blerg", "startActivity() " + intent.toString());
            super.startActivity(intent);
        }

        @Override
        public void startActivity(Intent intent, Bundle options) {
            Log.e("blerg", "startActivity(i,o) " + intent.toString());
            super.startActivity(intent, options);
        }*/

    override fun getResources(): Resources {
        return baseContext.resources
    }
}
