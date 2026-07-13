/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package com.peek.browser.util

import android.util.Log
import com.peek.browser.BuildConfig

object CrashTracking {
    private const val TAG = "CrashTracking"

    @JvmStatic
    fun logHandledException(throwable: Throwable) {
        if (BuildConfig.DEBUG) {
            Log.w(TAG, throwable)
        }
    }

    @JvmStatic
    fun setInt(key: String, value: Int) {
    }

    @JvmStatic
    fun setDouble(key: String, value: Double) {
    }

    @JvmStatic
    fun setFloat(key: String, value: Float) {
    }

    @JvmStatic
    fun setString(key: String, string: String) {
    }

    @JvmStatic
    fun setBool(key: String, value: Boolean) {
    }

    @JvmStatic
    fun log(message: String) {
        if (BuildConfig.DEBUG) {
            Log.d(TAG, message)
        }
    }
}
