/* -*- Mode: Java; c-basic-offset: 4; tab-width: 4; indent-tabs-mode: nil; -*-
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.gecko.util

import android.os.Handler
import android.os.Looper

/**
 * Executes a background task and publishes the result on the UI thread.
 *
 * The standard [android.os.AsyncTask] only runs onPostExecute on the
 * thread it is constructed on, so this is a convenience class for creating
 * tasks off the UI thread.
 */
abstract class UiAsyncTask<Params, Progress, Result>(private val mBackgroundThreadHandler: Handler) {
    @Volatile
    private var mCancelled = false

    private inner class BackgroundTaskRunnable(private val mParams: Array<out Params>) : Runnable {

        override fun run() {
            val result = doInBackground(*mParams)

            getUiHandler().post {
                if (mCancelled)
                    onCancelled()
                else
                    onPostExecute(result)
            }
        }
    }

    fun execute(vararg params: Params) {
        getUiHandler().post {
            onPreExecute()
            mBackgroundThreadHandler.post(BackgroundTaskRunnable(params))
        }
    }

    @Suppress("UNUSED_PARAMETER")
    fun cancel(mayInterruptIfRunning: Boolean): Boolean {
        mCancelled = true
        return mCancelled
    }

    fun isCancelled(): Boolean {
        return mCancelled
    }

    protected open fun onPreExecute() {}
    protected open fun onPostExecute(result: Result) {}
    protected open fun onCancelled() {}
    protected abstract fun doInBackground(vararg params: Params): Result

    companion object {
        @Volatile
        private var sHandler: Handler? = null

        @Synchronized
        private fun getUiHandler(): Handler {
            var handler = sHandler
            if (handler == null) {
                handler = Handler(Looper.getMainLooper())
                sHandler = handler
            }
            return handler
        }
    }
}
