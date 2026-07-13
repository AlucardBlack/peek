/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package com.peek.browser.util

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Base64
import android.util.Log
import android.webkit.MimeTypeMap
import android.webkit.URLUtil
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.peek.browser.BuildConfig
import com.peek.browser.MainController
import com.peek.browser.R
import com.peek.browser.ui.Prompt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.URL
import java.util.regex.Pattern

class DownloadImage(private val mContext: Context, private val mUrlAsString: String) {

    fun download() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q
                && ContextCompat.checkSelfPermission(mContext, Manifest.permission.WRITE_EXTERNAL_STORAGE)
                        != PackageManager.PERMISSION_GRANTED) {
            showErrorPrompt()
            return
        }

        Log.d(TAG, "downloading image: $mUrlAsString")
        Toast.makeText(mContext, R.string.notice_download_started, Toast.LENGTH_LONG).show()

        CoroutineScope(Dispatchers.Main).launch {
            val result = withContext(Dispatchers.IO) { downloadImage(mUrlAsString) }
            if (result != null) {
                showSuccessPrompt(result)
            } else {
                showErrorPrompt()
            }
        }
    }

    private fun showErrorPrompt() {
        val resources = mContext.resources
        val message = resources.getString(R.string.error_saving_image)
        Prompt.show(message, mContext.resources.getString(android.R.string.ok),
                Prompt.LENGTH_LONG, object : Prompt.OnPromptEventListener {
            override fun onActionClick() {
            }

            override fun onClose() {
            }
        })
    }

    private fun showSuccessPrompt(imageUri: Uri) {
        val resources = mContext.resources
        val message = resources.getString(R.string.image_saved)
        Prompt.show(message, mContext.resources.getString(R.string.action_open),
                Prompt.LENGTH_LONG, object : Prompt.OnPromptEventListener {
            override fun onActionClick() {
                val intent = Intent()
                intent.action = Intent.ACTION_VIEW
                intent.setDataAndType(imageUri, "image/*")
                intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION
                mContext.startActivity(intent)
                MainController.get()!!.switchToBubbleView(false)
            }

            override fun onClose() {
            }
        })
    }

    private fun downloadImage(sUrl: String): Uri? {
        try {
            var name: String
            var mimeType = "image/jpeg"
            var buffer: ByteArray
            val output = ByteArrayOutputStream()

            // If the URL is a base64 encoded URL, we need to decode it.
            if (URLUtil.isDataUrl(sUrl)) {
                val parts = sUrl.split(",")
                val encoded = parts[1]

                // Find the extension from the data URI.
                var extension = ""
                if (parts[0].lowercase().contains("image/")) {
                    val pattern = Pattern.compile("image/([a-zA-Z]*)")
                    val matcher = pattern.matcher(parts[0])
                    matcher.find()
                    extension = matcher.group(1) ?: ""
                    mimeType = "image/$extension"
                }
                name = "dataimage$extension"

                if (sUrl.lowercase().contains(";base64")) {
                    buffer = Base64.decode(encoded, Base64.DEFAULT)
                    output.write(buffer, 0, buffer.size)
                    output.close()
                } else {
                    buffer = Uri.decode(encoded).toByteArray()
                    output.write(buffer, 0, buffer.size)
                    output.close()
                }

            } else {
                // For a normal image download we just read the image from the URL.
                val fileExtension = MimeTypeMap.getFileExtensionFromUrl(sUrl)
                name = URLUtil.guessFileName(sUrl, null, fileExtension)
                if (fileExtension != null) {
                    val guessedMimeType = MimeTypeMap.getSingleton().getMimeTypeFromExtension(fileExtension)
                    if (guessedMimeType != null) {
                        mimeType = guessedMimeType
                    }
                }

                val url = URL(sUrl)
                val `is` = url.content as java.io.InputStream
                buffer = ByteArray(8192)
                var bytesRead: Int
                while (`is`.read(buffer).also { bytesRead = it } != -1) {
                    output.write(buffer, 0, bytesRead)
                }
                output.close()
            }

            // Prefix the filename with the date to attempt to prevent overwriting of existing files.
            name = System.currentTimeMillis().toString() + name

            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                saveViaMediaStore(name, mimeType, output)
            } else {
                saveToLegacyDownloadsDir(name, output)
            }
        } catch (e: IOException) {
            CrashTracking.log(e.message ?: "")
            return null
        }
    }

    @Throws(IOException::class)
    private fun saveViaMediaStore(name: String, mimeType: String, output: ByteArrayOutputStream): Uri? {
        val resolver = mContext.contentResolver
        val values = ContentValues()
        values.put(MediaStore.Images.Media.DISPLAY_NAME, name)
        values.put(MediaStore.Images.Media.MIME_TYPE, mimeType)
        values.put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)

        val collection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val itemUri = resolver.insert(collection, values) ?: return null

        val os = resolver.openOutputStream(itemUri) ?: return null
        os.write(output.toByteArray())
        os.flush()
        os.close()

        return itemUri
    }

    @Throws(IOException::class)
    private fun saveToLegacyDownloadsDir(name: String, output: ByteArrayOutputStream): Uri {
        val path = Environment.getExternalStoragePublicDirectory(
                Environment.DIRECTORY_DOWNLOADS)

        val imageFile = File(path, name)
        val fos = FileOutputStream(imageFile)
        fos.write(output.toByteArray())
        fos.flush()
        fos.close()

        MediaScannerConnection.scanFile(mContext, arrayOf(imageFile.absolutePath), null, null)

        return FileProvider.getUriForFile(mContext, BuildConfig.APPLICATION_ID + ".fileprovider", imageFile)
    }

    companion object {
        private const val TAG = "DownloadImage"
    }
}
