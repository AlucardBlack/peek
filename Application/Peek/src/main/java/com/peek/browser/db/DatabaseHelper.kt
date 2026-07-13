/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package com.peek.browser.db

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import com.peek.browser.Settings
import com.peek.browser.util.CrashTracking
import java.io.ByteArrayOutputStream

// Same public API as the raw-SQLiteOpenHelper version this replaced, backed by Room
// (PeekDatabase/HistoryDao/FaviconDao) instead of hand-written cursor/ContentValues code.
class DatabaseHelper(context: Context) {

    private val mDatabase: PeekDatabase = PeekDatabase.build(context)
    private val mHistoryDao: HistoryDao = mDatabase.historyDao()
    private val mFaviconDao: FaviconDao = mDatabase.faviconDao()

    fun addHistoryRecord(historyRecord: HistoryRecord) {
        if (Settings.get().isIncognitoMode) {
            return
        }

        Log.d(TAG, "addHistoryRecord() - " + historyRecord.toString())

        val existingId = getRecentHistoryRecordId(historyRecord.getUrl())
        // If there is a history record from the last 12 hours, just update that item
        if (existingId > -1) {
            historyRecord.setId(existingId)
            updateHistoryRecord(historyRecord)
            return
        }

        try {
            mHistoryDao.insert(historyRecord.toEntity())
            CrashTracking.log("DatabaseHelper.addHistoryRecord() success")
        } catch (ex: IllegalStateException) {
            CrashTracking.log("DatabaseHelper.addHistoryRecord() IllegalStateException")
        }
    }

    fun updateHistoryRecord(historyRecord: HistoryRecord) {
        try {
            mHistoryDao.update(historyRecord.toEntity())
            CrashTracking.log("DatabaseHelper.updateHistoryRecord() success, id:${historyRecord.getId()}")
        } catch (ex: IllegalStateException) {
            CrashTracking.log("DatabaseHelper.addHistoryRecord() IllegalStateException")
        }
    }

    fun deleteHistoryRecord(historyRecord: HistoryRecord): Boolean {
        var result = false

        try {
            mHistoryDao.deleteById(historyRecord.getId())
            result = true
            Log.d(TAG, "deleted historyRecord:$historyRecord")
            CrashTracking.log("DatabaseHelper.deleteHistoryRecord() success, id:${historyRecord.getId()}")
        } catch (ex: IllegalStateException) {
            CrashTracking.log("DatabaseHelper.deleteHistoryRecord() IllegalStateException")
        }

        return result
    }

    fun deleteAllHistoryRecords() {
        mHistoryDao.deleteAll()
    }

    fun getRecentHistoryRecordId(url: String?): Int {
        if (url == null) {
            return -1
        }

        try {
            val row = mHistoryDao.getMostRecentIdAndTime(url) ?: return -1

            // If there is a history entry for this URL from the last 12 hours...
            val timeDelta = System.currentTimeMillis() - row.time
            if (timeDelta < 12 * 60 * 60 * 1000) {
                return row.id
            }
        } catch (ex: IllegalStateException) {
            CrashTracking.log("DatabaseHelper.getRecentHistoryRecordId() IllegalStateException")
        }

        return -1
    }

    fun getAllHistoryRecords(): List<HistoryRecord> {
        return try {
            mHistoryDao.getAll().map { it.toHistoryRecord() }
        } catch (exc: IllegalStateException) {
            CrashTracking.log("DatabaseHelper.getAllHistoryRecords() IllegalStateException")
            emptyList()
        }
    }

    fun getRecentNHistoryRecords(countToGet: Int): List<HistoryRecord> {
        return try {
            mHistoryDao.getRecent(countToGet).map { it.toHistoryRecord() }
        } catch (exc: IllegalStateException) {
            CrashTracking.log("DatabaseHelper.getRecentNHistoryRecords() IllegalStateException")
            emptyList()
        }
    }

    fun deleteAllFavicons() {
        mFaviconDao.deleteAll()
    }

    /**
     * Get the favicon from the database, if any, associated with the given favicon URL. (That is,
     * the URL of the actual favicon image, not the URL of the page with which the favicon is associated.)
     * @param faviconUrl The URL of the favicon to fetch from the database.
     * @return The decoded Bitmap from the database, if any. null if none is stored.
     */
    fun getFavicon(faviconUrl: String): Bitmap? {
        var result: Bitmap? = null

        try {
            val row = mFaviconDao.getFetchRowByUrl(faviconUrl)
            if (row != null) {
                val timeDelta = System.currentTimeMillis() - row.time
                if (timeDelta < FAVICON_EXPIRE_TIME) {
                    result = BitmapFactory.decodeByteArray(row.data, 0, row.data!!.size)
                    Log.d(TAG, "getFavicon() - fetched favicon for $faviconUrl")
                    CrashTracking.log("DatabaseHelper.getFavicon() success, id:${row.id}")
                } else {
                    deleteFavicon(row.id)
                }
            }
        } catch (ex: IllegalStateException) {    // #302
            CrashTracking.log("DatabaseHelper.getFavicon() IllegalStateException")
        }

        return result
    }

    fun faviconExists(faviconUrl: String, favicon: Bitmap?): Boolean {
        var result = false

        try {
            val row = mFaviconDao.getExistsRowByUrl(faviconUrl)
            if (row != null) {
                val timeDelta = System.currentTimeMillis() - row.time
                if (favicon != null && favicon.height > row.imageSize) {
                    deleteFavicon(row.id)
                } else if (timeDelta >= FAVICON_EXPIRE_TIME) {
                    deleteFavicon(row.id)
                } else {
                    result = true
                }
            }
        } catch (ex: IllegalStateException) {
        }

        return result
    }

    private fun deleteFavicon(id: Long) {
        try {
            mFaviconDao.deleteById(id)
            CrashTracking.log("DatabaseHelper.deleteFavicon() success, id:$id")
        } catch (ex: IllegalStateException) {
            CrashTracking.log("DatabaseHelper.deleteFavicon(): IllegalStateException")
        }

        Log.d(TAG, "deleteFavicon() - id:$id")
    }

    fun addFaviconForUrl(faviconUrl: String?, favicon: Bitmap?, pageUri: String?) {
        if (Settings.get().isIncognitoMode || faviconUrl == null || favicon == null) {
            return
        }

        var data: ByteArray? = null
        val stream = ByteArrayOutputStream()
        if (favicon.compress(Bitmap.CompressFormat.PNG, 100, stream)) {
            data = stream.toByteArray()
        } else {
            Log.w(TAG, "Favicon compression failed.")
        }

        try {
            mFaviconDao.insert(FaviconEntity(
                    url = faviconUrl,
                    pageUrl = pageUri,
                    imageSize = favicon.height,  // assume square
                    data = data,
                    time = System.currentTimeMillis()
            ))
            CrashTracking.log("DatabaseHelper.addFaviconForUrl() success")
        } catch (ex: IllegalStateException) {
            CrashTracking.log("DatabaseHelper.addFaviconForUrl(): IllegalStateException")
        }

        Log.d(TAG, "addFaviconForUrl() - $faviconUrl")
    }

    private fun HistoryRecord.toEntity(): HistoryRecordEntity {
        return HistoryRecordEntity(
                id = getId(),
                title = getTitle(),
                url = getUrl(),
                host = getHost(),
                time = getTime()
        )
    }

    private fun HistoryRecordEntity.toHistoryRecord(): HistoryRecord {
        val record = HistoryRecord()
        record.setId(id)
        record.setTitle(title)
        record.setUrl(url)
        record.setHost(host)
        record.setTime(time)
        return record
    }

    companion object {
        private const val TAG = "PeekDB"

        private const val FAVICON_EXPIRE_TIME = 7 * 24 * 60 * 60 * 1000
    }
}
