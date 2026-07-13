/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package com.peek.browser.db

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import com.peek.browser.Settings
import com.peek.browser.util.CrashTracking
import java.io.ByteArrayOutputStream

class DatabaseHelper(context: Context) : SQLiteOpenHelper(context, DATABASE_NAME, null, DATABASE_VERSION) {

    override fun onCreate(db: SQLiteDatabase) {
        val CREATE_LINK_HISTORY_TABLE = "CREATE TABLE " + TABLE_LINK_HISTORY + " ( " +
                KEY_ID + " INTEGER PRIMARY KEY AUTOINCREMENT, " +
                KEY_TITLE + " TEXT, " +
                KEY_URL + " TEXT, " +
                KEY_HOST + " TEXT, " +
                KEY_TIME + " INTEGER" + " )"

        db.execSQL(CREATE_LINK_HISTORY_TABLE)

        val CREATE_FAVICON_CACHE_TABLE = "CREATE TABLE " + TABLE_FAVICON_CACHE + " ( " +
                KEY_ID + " INTEGER PRIMARY KEY AUTOINCREMENT, " +
                KEY_URL + " TEXT, " +
                KEY_PAGE_URL + " TEXT, " +
                KEY_IMAGE_SIZE + " INTEGER, " +
                KEY_DATA + " BLOB, " +
                KEY_TIME + " INTEGER" + " )"

        db.execSQL(CREATE_FAVICON_CACHE_TABLE)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // Drop older tables if they existed. Both need dropping - onCreate() below recreates
        // both, and leaving favicons behind would make its CREATE TABLE fail on the next upgrade.
        db.execSQL("DROP TABLE IF EXISTS $TABLE_LINK_HISTORY")
        db.execSQL("DROP TABLE IF EXISTS $TABLE_FAVICON_CACHE")

        // create fresh tables
        onCreate(db)
    }

    private fun getContentValues(historyRecord: HistoryRecord): ContentValues {
        val values = ContentValues()
        values.put(KEY_TITLE, historyRecord.getTitle())
        values.put(KEY_URL, historyRecord.getUrl())
        values.put(KEY_HOST, historyRecord.getHost())
        values.put(KEY_TIME, historyRecord.getTime())
        return values
    }

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

        val db = writableDatabase
        try {
            db.insert(TABLE_LINK_HISTORY, null, getContentValues(historyRecord))
            CrashTracking.log("DatabaseHelper.addHistoryRecord() success")
        } catch (ex: IllegalStateException) {
            CrashTracking.log("DatabaseHelper.addHistoryRecord() IllegalStateException")
        }
        db.close()
    }

    fun updateHistoryRecord(historyRecord: HistoryRecord) {
        val db = writableDatabase
        try {
            val values = getContentValues(historyRecord)
            val id = historyRecord.getId().toString()
            db.update(TABLE_LINK_HISTORY, values,
                    "$KEY_ID = ?", arrayOf(id))
            CrashTracking.log("DatabaseHelper.updateHistoryRecord() success, id:$id")
        } catch (ex: IllegalStateException) {
            CrashTracking.log("DatabaseHelper.addHistoryRecord() IllegalStateException")
        }
        db.close()
    }

    fun deleteHistoryRecord(historyRecord: HistoryRecord): Boolean {

        var result = false

        val db = writableDatabase
        try {
            val id = historyRecord.getId().toString()
            db.delete(TABLE_LINK_HISTORY, "$KEY_ID = ?", arrayOf(id))
            result = true
            Log.d(TAG, "deleted historyRecord:$historyRecord")
            CrashTracking.log("DatabaseHelper.deleteHistoryRecord() success, id:$id")
        } catch (ex: IllegalStateException) {
            CrashTracking.log("DatabaseHelper.deleteHistoryRecord() IllegalStateException")
        }
        db.close()

        return result
    }

    fun deleteAllHistoryRecords() {
        val db = writableDatabase
        db.delete(TABLE_LINK_HISTORY, null, null)
        db.close()
    }

    fun getRecentHistoryRecordId(url: String?): Int {
        var result = -1
        val db = readableDatabase

        try {
            val cursor = db.query(TABLE_LINK_HISTORY, // a. table
                    LINK_HISTORY_COLUMNS, // b. column names
                    " $KEY_URL = ?", // c. selections
                    arrayOf(url.toString()), // d. selections args
                    null, // e. group by
                    null, // f. having
                    " $KEY_TIME DESC", // g. order by
                    null) // h. limit
            if (cursor != null && cursor.count > 0) {
                cursor.moveToFirst()

                // If there is a history entry for this URL from the last 12 hours...
                val id = Integer.parseInt(cursor.getString(0))
                val time = cursor.getLong(1)
                val timeDelta = System.currentTimeMillis() - time
                if (timeDelta < 12 * 60 * 60 * 1000) {
                    result = id
                }
            }
        } catch (ex: IllegalStateException) {
            CrashTracking.log("DatabaseHelper.getRecentHistoryRecordId() IllegalStateException")
        }

        db.close()
        return result
    }

    fun getAllHistoryRecords(): List<HistoryRecord> {
        val records = ArrayList<HistoryRecord>()

        val db = writableDatabase
        try {
            val query = "SELECT * FROM $TABLE_LINK_HISTORY ORDER BY $KEY_TIME DESC;"

            val cursor = db.rawQuery(query, null)

            if (cursor.moveToFirst()) {
                do {
                    val historyRecord = HistoryRecord()
                    historyRecord.setId(Integer.parseInt(cursor.getString(0)))
                    historyRecord.setTitle(cursor.getString(1))
                    historyRecord.setUrl(cursor.getString(2))
                    historyRecord.setHost(cursor.getString(3))
                    historyRecord.setTime(cursor.getLong(4))

                    records.add(historyRecord)
                } while (cursor.moveToNext())
            }

            cursor.close()
        } catch (exc: IllegalStateException) {
            CrashTracking.log("DatabaseHelper.getAllHistoryRecords() IllegalStateException")
        } finally {
            db.close()
        }

        return records
    }

    fun getRecentNHistoryRecords(countToGet: Int): List<HistoryRecord> {
        val records = ArrayList<HistoryRecord>()

        val db = writableDatabase
        try {
            val query = "SELECT * FROM $TABLE_LINK_HISTORY ORDER BY $KEY_TIME DESC LIMIT $countToGet;"

            val cursor = db.rawQuery(query, null)

            if (cursor.moveToFirst()) {
                do {
                    val historyRecord = HistoryRecord()
                    historyRecord.setId(Integer.parseInt(cursor.getString(0)))
                    historyRecord.setTitle(cursor.getString(1))
                    historyRecord.setUrl(cursor.getString(2))
                    historyRecord.setHost(cursor.getString(3))
                    historyRecord.setTime(cursor.getLong(4))

                    records.add(historyRecord)
                } while (cursor.moveToNext())
            }

            cursor.close()
        } catch (exc: IllegalStateException) {
            CrashTracking.log("DatabaseHelper.getRecentNHistoryRecords() IllegalStateException")
        } finally {
            db.close()
        }

        return records
    }

    fun deleteAllFavicons() {
        val db = writableDatabase
        db.delete(TABLE_FAVICON_CACHE, null, null)
        db.close()
    }

    /**
     * Get the favicon from the database, if any, associated with the given favicon URL. (That is,
     * the URL of the actual favicon image, not the URL of the page with which the favicon is associated.)
     * @param faviconUrl The URL of the favicon to fetch from the database.
     * @return The decoded Bitmap from the database, if any. null if none is stored.
     */
    /*
    public FaviconRecord getFaviconRecord(String faviconUrl) {

        SQLiteDatabase db = getReadableDatabase();

        Cursor cursor = db.query(TABLE_FAVICON_CACHE, // a. table
                FAVICON_COLUMNS, // b. column names
                " " + KEY_URL + " = ?", // c. selections
                new String[] { faviconUrl }, // d. selections args
                null, // e. group by
                null, // f. having
                null, // g. order by
                null); // h. limit

        if (cursor != null) {
            cursor.moveToFirst();
        }

        FaviconRecord faviconRecord = new FaviconRecord();
        faviconRecord.setId(Integer.parseInt(cursor.getString(0)));
        faviconRecord.setUrl(cursor.getString(1));
        faviconRecord.setPageUrl(cursor.getString(2));

        byte[] byteArray = cursor.getBlob(3);
        Bitmap bitmap = BitmapFactory.decodeByteArray(byteArray, 0, byteArray.length);
        faviconRecord.setFavicon(bitmap);
        cursor.closeZZZ();  // TODO, also put this inside the if check above

        return faviconRecord;
    }*/
    fun getFavicon(faviconUrl: String): Bitmap? {
        var result: Bitmap? = null
        val db = readableDatabase

        try {
            val cursor = db.query(TABLE_FAVICON_CACHE, // a. table
                    FAVICON_FETCH_COLUMNS, // b. column names
                    " $KEY_URL = ?", // c. selections
                    arrayOf(faviconUrl), // d. selections args
                    null, // e. group by
                    null, // f. having
                    null, // g. order by
                    null) // h. limit

            if (cursor != null) {
                var idToDelete: Long = -1
                if (cursor.count > 0) {
                    cursor.moveToFirst()

                    val id = cursor.getLong(0)
                    val createTime = cursor.getLong(1)
                    val timeDelta = System.currentTimeMillis() - createTime
                    if (timeDelta < FAVICON_EXPIRE_TIME) {
                        val byteArray = cursor.getBlob(2)
                        result = BitmapFactory.decodeByteArray(byteArray, 0, byteArray.size)
                        Log.d(TAG, "getFavicon() - fetched favicon for $faviconUrl")
                        CrashTracking.log("DatabaseHelper.getFavicon() success, id:$id")
                    } else {
                        idToDelete = id
                    }
                }
                cursor.close()

                if (idToDelete > -1) {
                    deleteFavicon(idToDelete)
                }
            }
        } catch (ex: IllegalStateException) {    // #302
            CrashTracking.log("DatabaseHelper.getFavicon() IllegalStateException")
        }

        db.close()
        return result
    }

    fun faviconExists(faviconUrl: String, favicon: Bitmap?): Boolean {
        var result = false
        val db = readableDatabase

        val cursor = db.query(TABLE_FAVICON_CACHE, // a. table
                FAVICON_EXISTS_COLUMNS, // b. column names
                " $KEY_URL = ?", // c. selections
                arrayOf(faviconUrl), // d. selections args
                null, // e. group by
                null, // f. having
                null, // g. order by
                null) // h. limit

        if (cursor != null) {
            var idToDelete: Long = -1
            try {
                if (cursor.count > 0) {
                    cursor.moveToFirst()

                    val id = cursor.getLong(0)
                    val imageSize = cursor.getInt(1).toLong()
                    val createTime = cursor.getLong(2)
                    val timeDelta = System.currentTimeMillis() - createTime
                    if (favicon != null && favicon.height > imageSize) {
                        idToDelete = id
                    } else if (timeDelta >= FAVICON_EXPIRE_TIME) {
                        idToDelete = id
                    } else {
                        result = true
                    }
                }
                cursor.close()

                if (idToDelete > -1) {
                    deleteFavicon(idToDelete)
                }
            } catch (ex: IllegalStateException) {
            }
        }

        db.close()
        return result
    }

    private fun deleteFavicon(id: Long) {
        val db = writableDatabase
        try {
            val idAsString = id.toString()
            db.delete(TABLE_FAVICON_CACHE, "$KEY_ID = ?", arrayOf(idAsString))
            CrashTracking.log("DatabaseHelper.deleteFavicon() success, id:$idAsString")
        } catch (ex: IllegalStateException) {
            CrashTracking.log("DatabaseHelper.deleteFavicon(): IllegalStateException")
        }

        db.close()

        Log.d(TAG, "deleteFavicon() - id:$id")
    }

    fun addFaviconForUrl(faviconUrl: String?, favicon: Bitmap?, pageUri: String?) {
        if (Settings.get().isIncognitoMode || faviconUrl == null || favicon == null) {
            return
        }

        val values = ContentValues()
        values.put(KEY_URL, faviconUrl)
        values.put(KEY_PAGE_URL, pageUri)

        var data: ByteArray? = null
        val stream = ByteArrayOutputStream()
        if (favicon.compress(Bitmap.CompressFormat.PNG, 100, stream)) {
            data = stream.toByteArray()
        } else {
            Log.w(TAG, "Favicon compression failed.")
        }
        values.put(KEY_DATA, data)
        values.put(KEY_IMAGE_SIZE, favicon.height)  // assume square
        values.put(KEY_TIME, System.currentTimeMillis())

        val db = writableDatabase
        try {
            db.insert(TABLE_FAVICON_CACHE, null, values)
            CrashTracking.log("DatabaseHelper.addFaviconForUrl() success")
        } catch (ex: IllegalStateException) {
            CrashTracking.log("DatabaseHelper.addFaviconForUrl(): IllegalStateException")
        }
        db.close()

        Log.d(TAG, "addFaviconForUrl() - $faviconUrl")
    }

    companion object {
        private const val TAG = "PeekDB"

        private const val DATABASE_VERSION = 1
        private const val DATABASE_NAME = "PeekDB"

        private const val TABLE_LINK_HISTORY = "linkHistory"
        private const val TABLE_FAVICON_CACHE = "favicons"

        // Link History Table Columns names
        private const val KEY_ID = "id"
        private const val KEY_TITLE = "title"
        private const val KEY_URL = "url"
        private const val KEY_HOST = "host"
        private const val KEY_TIME = "time"
        private const val KEY_PAGE_URL = "pageUrl"
        private const val KEY_DATA = "data"
        private const val KEY_IMAGE_SIZE = "imageSize"

        private val LINK_HISTORY_COLUMNS = arrayOf(KEY_ID, KEY_TIME)
        private val FAVICON_COLUMNS = arrayOf(KEY_ID, KEY_URL, KEY_PAGE_URL, KEY_DATA, KEY_TIME)
        private val FAVICON_EXISTS_COLUMNS = arrayOf(KEY_ID, KEY_IMAGE_SIZE, KEY_TIME)
        private val FAVICON_FETCH_COLUMNS = arrayOf(KEY_ID, KEY_TIME, KEY_DATA)

        private const val FAVICON_EXPIRE_TIME = 7 * 24 * 60 * 60 * 1000
    }
}
