/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this file,
 * You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.gecko.util

import android.os.Handler
import android.os.Looper
import java.util.concurrent.SynchronousQueue

// Singleton, so private constructor.
class GeckoBackgroundThread private constructor() : Thread() {
    private val mHandlerQueue = SynchronousQueue<Handler>()

    override fun run() {
        name = LOOPER_NAME
        Looper.prepare()
        try {
            mHandlerQueue.put(Handler())
        } catch (ie: InterruptedException) {
        }

        Looper.loop()
    }

    companion object {
        private const val LOOPER_NAME = "GeckoBackgroundThread"

        // Guarded by 'this'.
        private var sHandler: Handler? = null

        // Get a Handler for a looper thread, or create one if it doesn't yet exist.
        @JvmStatic
        @Synchronized
        fun getHandler(): Handler? {
            if (sHandler == null) {
                val lt = GeckoBackgroundThread()
                ThreadUtils.setBackgroundThread(lt)
                lt.start()
                try {
                    sHandler = lt.mHandlerQueue.take()
                } catch (ie: InterruptedException) {
                }
            }
            return sHandler
        }

        @JvmStatic
        fun post(runnable: Runnable) {
            val handler = getHandler()
                    ?: throw IllegalStateException("No handler! Must have been interrupted. Not posting.")
            handler.post(runnable)
        }
    }
}
