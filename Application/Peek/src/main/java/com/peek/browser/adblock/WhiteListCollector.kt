/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package com.peek.browser.adblock

import android.content.Context
import com.peek.browser.R
import com.peek.browser.util.CrashTracking
import java.io.File
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.locks.ReentrantReadWriteLock

class WhiteListCollector(val mContext: Context) {

    private val mWhiteList = HashSet<String>()
    private val mLock = ReentrantReadWriteLock()

    init {
        try {
            val dataPath = File(mContext.applicationInfo.dataDir, mContext.getString(R.string.whitelist_localfilename))

            var buffer: ByteArray? = null
            if (dataPath.exists()) {
                buffer = ADBlockUtils.readLocalFile(dataPath)
            }
            if (buffer != null) {
                val array = String(buffer).split(",")
                for (item in array) {
                    if (item == "") {
                        continue
                    }
                    mWhiteList.add(item)
                }
            }
        } catch (exc: Exception) {
            CrashTracking.logHandledException(exc)
        }
    }

    fun isInWhiteList(host: String): Boolean {
        var h = host
        if (h.startsWith("www.")) {
            h = h.substring("www.".length)
        }
        try {
            mLock.readLock().lock()

            if (mWhiteList.contains(h)) {
                return true
            }
        } finally {
            mLock.readLock().unlock()
        }

        return false
    }

    fun addHostToWhiteList(host: String) {
        var h = host
        if (h.startsWith("www.")) {
            h = h.substring("www.".length)
        }
        val dataPath = File(mContext.applicationInfo.dataDir, mContext.getString(R.string.whitelist_localfilename))
        try {
            mLock.writeLock().lock()
            mWhiteList.add(h)

            saveWhiteList(dataPath.absolutePath)
        } finally {
            mLock.writeLock().unlock()
        }
    }

    fun removeHostFromWhiteList(host: String) {
        var h = host
        if (h.startsWith("www.")) {
            h = h.substring("www.".length)
        }
        val dataPath = File(mContext.applicationInfo.dataDir, mContext.getString(R.string.whitelist_localfilename))
        try {
            mLock.writeLock().lock()
            mWhiteList.remove(h)

            saveWhiteList(dataPath.absolutePath)
        } finally {
            mLock.writeLock().unlock()
        }
    }

    private fun saveWhiteList(dataPath: String) {
        try {
            val outputStream = FileOutputStream(dataPath)
            var firstIteration = true
            for (whiteListHost in mWhiteList) {
                if (!firstIteration) {
                    outputStream.write(",".toByteArray(), 0, ",".length)
                }
                outputStream.write(whiteListHost.toByteArray(), 0, whiteListHost.length)
                firstIteration = false
            }
            outputStream.close()
        } catch (e: FileNotFoundException) {
            e.printStackTrace()
        } catch (e: IOException) {
            e.printStackTrace()
        }
    }
}
