/* -*- Mode: Java; c-basic-offset: 4; tab-width: 20; indent-tabs-mode: nil; -*-
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this file,
 * You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.gecko.util

import android.os.Handler
import android.os.MessageQueue
import android.util.Log

object ThreadUtils {
    private const val LOGTAG = "ThreadUtils"

    private var sUiThread: Thread? = null
    private var sBackgroundThread: Thread? = null

    private var sUiHandler: Handler? = null

    // Referenced directly from GeckoAppShell in highly performance-sensitive code (The extra
    // function call of the getter was harming performance. (Bug 897123))
    // Once Bug 709230 is resolved we should reconsider this as ProGuard should be able to optimise
    // this out at compile time.
    @JvmField
    var sGeckoHandler: Handler? = null
    @JvmField
    var sGeckoQueue: MessageQueue? = null
    @JvmField
    var sGeckoThread: Thread? = null

    // Delayed Runnable that resets the Gecko thread priority.
    private val sPriorityResetRunnable = Runnable {
        resetGeckoPriority()
    }

    private var sIsGeckoPriorityReduced = false

    class UiThreadBlockedException : RuntimeException {
        constructor() : super()
        constructor(msg: String?) : super(msg)
        constructor(msg: String?, e: Throwable?) : super(msg, e)
        constructor(e: Throwable?) : super(e)
    }

    @JvmStatic
    fun dumpAllStackTraces() {
        Log.w(LOGTAG, "Dumping ALL the threads!")
        val allStacks = Thread.getAllStackTraces()
        for (t in allStacks.keys) {
            Log.w(LOGTAG, t.toString())
            for (ste in allStacks[t]!!) {
                Log.w(LOGTAG, ste.toString())
            }
            Log.w(LOGTAG, "----")
        }
    }

    @JvmStatic
    fun setUiThread(thread: Thread, handler: Handler) {
        sUiThread = thread
        sUiHandler = handler
    }

    @JvmStatic
    fun setBackgroundThread(thread: Thread) {
        sBackgroundThread = thread
    }

    @JvmStatic
    fun getUiThread(): Thread? {
        return sUiThread
    }

    @JvmStatic
    fun getUiHandler(): Handler? {
        return sUiHandler
    }

    @JvmStatic
    fun postToUiThread(runnable: Runnable) {
        sUiHandler!!.post(runnable)
    }

    @JvmStatic
    fun getBackgroundThread(): Thread? {
        return sBackgroundThread
    }

    @JvmStatic
    fun getBackgroundHandler(): Handler? {
        return GeckoBackgroundThread.getHandler()
    }

    @JvmStatic
    fun postToBackgroundThread(runnable: Runnable) {
        GeckoBackgroundThread.post(runnable)
    }

    @JvmStatic
    fun assertOnUiThread() {
        assertOnThread(getUiThread())
    }

    @JvmStatic
    fun assertOnGeckoThread() {
        assertOnThread(sGeckoThread)
    }

    @JvmStatic
    fun assertOnBackgroundThread() {
        assertOnThread(getBackgroundThread())
    }

    @JvmStatic
    fun assertOnThread(expectedThread: Thread?) {
        val currentThread = Thread.currentThread()
        val currentThreadId = currentThread.id
        val expectedThreadId = expectedThread!!.id

        if (currentThreadId != expectedThreadId) {
            throw IllegalThreadStateException("Expected thread " + expectedThreadId + " (\""
                    + expectedThread.name
                    + "\"), but running on thread " + currentThreadId
                    + " (\"" + currentThread.name + ")")
        }
    }

    @JvmStatic
    fun isOnUiThread(): Boolean {
        return isOnThread(getUiThread())
    }

    @JvmStatic
    fun isOnBackgroundThread(): Boolean {
        return sBackgroundThread != null && isOnThread(sBackgroundThread)
    }

    @JvmStatic
    fun isOnThread(thread: Thread?): Boolean {
        return Thread.currentThread().id == thread!!.id
    }

    /**
     * Reduces the priority of the Gecko thread, allowing other operations
     * (such as those related to the UI and database) to take precedence.
     *
     * Note that there are no guards in place to prevent multiple calls
     * to this method from conflicting with each other.
     *
     * @param timeout Timeout in ms after which the priority will be reset
     */
    @JvmStatic
    fun reduceGeckoPriority(timeout: Long) {
        if (!sIsGeckoPriorityReduced) {
            sIsGeckoPriorityReduced = true
            sGeckoThread!!.priority = Thread.MIN_PRIORITY
            getUiHandler()!!.postDelayed(sPriorityResetRunnable, timeout)
        }
    }

    /**
     * Resets the priority of a thread whose priority has been reduced
     * by reduceGeckoPriority.
     */
    @JvmStatic
    fun resetGeckoPriority() {
        if (sIsGeckoPriorityReduced) {
            sIsGeckoPriorityReduced = false
            sGeckoThread!!.priority = Thread.NORM_PRIORITY
            getUiHandler()!!.removeCallbacks(sPriorityResetRunnable)
        }
    }
}
