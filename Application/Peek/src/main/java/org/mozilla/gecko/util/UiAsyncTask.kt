/* -*- Mode: Java; c-basic-offset: 4; tab-width: 4; indent-tabs-mode: nil; -*-
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.gecko.util

import android.os.Handler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.android.asCoroutineDispatcher
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Executes a background task and publishes the result on the UI thread.
 *
 * The standard [android.os.AsyncTask] only runs onPostExecute on the
 * thread it is constructed on, so this is a convenience class for creating
 * tasks off the UI thread. Backed by coroutines: [mBackgroundThreadHandler] is wrapped as a
 * CoroutineDispatcher so doInBackground still runs on the same shared background thread callers
 * already pass in (e.g. GeckoBackgroundThread), while onPostExecute/onCancelled run on Main.
 *
 * Cancellation is deliberately a plain flag rather than coroutine Job cancellation: doInBackground
 * always runs to completion (matching the original Handler-based behavior), and the flag is only
 * consulted afterwards to decide between onPostExecute and onCancelled - callers like
 * LoadFaviconTask rely on onCancelled() actually running to clean up its in-flight-loads map.
 */
abstract class UiAsyncTask<Params, Progress, Result>(mBackgroundThreadHandler: Handler) {

    private val mBackgroundDispatcher = mBackgroundThreadHandler.asCoroutineDispatcher()

    @Volatile
    private var mCancelled = false

    fun execute(vararg params: Params) {
        CoroutineScope(Dispatchers.Main.immediate).launch {
            onPreExecute()
            val result = withContext(mBackgroundDispatcher) {
                doInBackground(*params)
            }
            if (mCancelled) {
                onCancelled()
            } else {
                onPostExecute(result)
            }
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
}
