/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package com.peek.browser.adblock

import android.content.Context
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.MalformedURLException
import java.net.URL
import java.util.Calendar

object ADBlockUtils {

    const val MILLISECONDS_IN_A_DAY = 86400 * 1000L
    const val BUFFER_TO_READ = 16384 // 16Kb

    private const val ETAGS_PREFS_NAME = "EtagsPrefsFile"
    private const val ETAG_NAME = "Etag"
    private const val TIME_NAME = "Time"

    @JvmStatic
    fun saveETagInfo(context: Context, prepend: String, etagObject: EtagObject) {
        val sharedPref = context.getSharedPreferences(ETAGS_PREFS_NAME, 0)
        val editor = sharedPref.edit()

        editor.putString(prepend + ETAG_NAME, etagObject.mEtag)
        editor.putLong(prepend + TIME_NAME, etagObject.mMilliSeconds)

        editor.apply()
    }

    @JvmStatic
    fun getETagInfo(context: Context, prepend: String): EtagObject {
        val sharedPref = context.getSharedPreferences(ETAGS_PREFS_NAME, 0)

        val etagObject = EtagObject()

        etagObject.mEtag = sharedPref.getString(prepend + ETAG_NAME, "") ?: ""
        etagObject.mMilliSeconds = sharedPref.getLong(prepend + TIME_NAME, 0)

        return etagObject
    }

    @JvmStatic
    fun getDataVerNumber(url: String): String {
        val split = url.split("/")
        if (split.size > 2) {
            return split[split.size - 2]
        }

        return ""
    }

    @JvmStatic
    fun removeOldVersionFiles(context: Context, fileName: String) {
        val dataDirPath = File(context.applicationInfo.dataDir)
        val fileList = dataDirPath.listFiles() ?: return

        for (file in fileList) {
            if (file.absoluteFile.toString().endsWith(fileName)) {
                file.delete()
            }
        }
    }

    @JvmStatic
    fun readLocalFile(path: File): ByteArray? {
        var buffer: ByteArray? = null

        var inputStream: FileInputStream? = null
        try {
            if (!path.exists()) {
                return null
            }
            inputStream = FileInputStream(path.absolutePath)
            val size = inputStream.available()
            buffer = ByteArray(size)
            var n: Int
            var bytesOffset = 0
            val tempBuffer = ByteArray(BUFFER_TO_READ)
            while (inputStream.read(tempBuffer).also { n = it } != -1) {
                System.arraycopy(tempBuffer, 0, buffer, bytesOffset, n)
                bytesOffset += n
            }
            inputStream.close()
        } catch (e: IOException) {
            e.printStackTrace()
        }

        return buffer
    }

    @JvmStatic
    fun readData(context: Context, fileName: String, urlString: String, eTagPrepend: String, verNumber: String,
                 downloadOnly: Boolean): ByteArray? {
        val dataPath = File(context.applicationInfo.dataDir, verNumber + fileName)
        val oldFileSize = dataPath.length()
        val previousEtag = getETagInfo(context, eTagPrepend)
        val milliSeconds = Calendar.getInstance().timeInMillis
        if (0L == oldFileSize || (milliSeconds - previousEtag.mMilliSeconds >= MILLISECONDS_IN_A_DAY)) {
            downloadDatFile(context, oldFileSize, previousEtag, milliSeconds, fileName, urlString, eTagPrepend, verNumber)
        }

        if (downloadOnly) {
            return null
        }

        return readLocalFile(dataPath)
    }

    @JvmStatic
    fun downloadDatFile(context: Context, oldFileSize: Long, previousEtag: EtagObject, currentMilliSeconds: Long,
                         fileName: String, urlString: String, eTagPrepend: String, verNumber: String) {
        var inputStream: InputStream? = null
        var connection: HttpURLConnection? = null

        try {
            val url = URL(urlString)
            connection = url.openConnection() as HttpURLConnection
            var etag = connection.getHeaderField("ETag")
            val length = connection.contentLength
            if (etag == null) {
                etag = ""
            }
            var downloadFile = true
            if (oldFileSize == length.toLong() && etag == previousEtag.mEtag) {
                downloadFile = false
            }
            previousEtag.mEtag = etag
            previousEtag.mMilliSeconds = currentMilliSeconds
            saveETagInfo(context, eTagPrepend, previousEtag)
            if (!downloadFile) {
                return
            }
            removeOldVersionFiles(context, fileName)

            connection.connect()

            if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                return
            }

            val path = File(context.applicationInfo.dataDir, verNumber + fileName)
            val outputStream = FileOutputStream(path)
            inputStream = connection.inputStream
            val buffer = ByteArray(BUFFER_TO_READ)
            var n: Int
            var totalReadSize = 0
            try {
                while (inputStream.read(buffer).also { n = it } != -1) {
                    outputStream.write(buffer, 0, n)
                    totalReadSize += n
                }
            } catch (exc: IllegalStateException) {
                // Sometimes it gives us that exception, found that we should do that way to avoid it:
                // Each HttpURLConnection instance is used to make a single request but the
                // underlying network connection to the HTTP server may be transparently shared by other instance.
                // But we do that way, so just wrapped it for now and we will redownload the file on next request
            }
            outputStream.close()
            // Was `if (length != totalReadSize)` - length comes from the Content-Length header,
            // which is frequently -1 (unknown/chunked transfer) or reflects a compressed size
            // HttpURLConnection has already transparently decompressed by the time totalReadSize
            // is counted, so an exact-match check silently deleted every successful download
            // (confirmed on-device: easylist.txt/easyprivacy.txt both downloaded correctly -
            // 2.1MB/1.5MB of real content - length was -1, and the file was deleted right after).
            // A totalReadSize of 0 is the only case actually worth discarding as a failed fetch.
            if (totalReadSize == 0) {
                removeOldVersionFiles(context, fileName)
            }
        } catch (e: MalformedURLException) {
            e.printStackTrace()
        } catch (e: IOException) {
            e.printStackTrace()
        } finally {
            try {
                inputStream?.close()
            } catch (e: IOException) {
                e.printStackTrace()
            }

            connection?.disconnect()
        }
    }
}
