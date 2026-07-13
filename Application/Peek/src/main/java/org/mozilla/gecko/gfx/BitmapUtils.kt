/* -*- Mode: Java; c-basic-offset: 4; tab-width: 20; indent-tabs-mode: nil; -*-
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.gecko.gfx

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.net.Uri
import android.text.TextUtils
import android.util.Base64
import android.util.Log
import com.peek.browser.R
import org.mozilla.gecko.util.ThreadUtils
import org.mozilla.gecko.util.UiAsyncTask
import java.io.IOException
import java.io.InputStream
import java.net.MalformedURLException
import java.net.URL

object BitmapUtils {
    private const val LOGTAG = "GeckoBitmapUtils"

    interface BitmapLoader {
        fun onBitmapFound(d: Drawable?)
    }

    @JvmStatic
    fun getDrawable(context: Context, data: String?, loader: BitmapLoader) {
        if (TextUtils.isEmpty(data)) {
            loader.onBitmapFound(null)
            return
        }

        if (data!!.startsWith("data")) {
            val d = BitmapDrawable(getBitmapFromDataURI(data))
            loader.onBitmapFound(d)
            return
        }

        if (data.startsWith("jar:") || data.startsWith("file://")) {
            object : UiAsyncTask<Void, Void, Drawable?>(ThreadUtils.getBackgroundHandler()!!) {
                override fun doInBackground(vararg params: Void): Drawable? {
                    try {
                        /* LB_CHANGE:
                        if (data.startsWith("jar:jar")) {
                            return GeckoJarReader.getBitmapDrawable(context.getResources(), data);
                        }

                        // Don't attempt to validate the JAR signature when loading an add-on icon
                        if (data.startsWith("jar:file")) {
                            return GeckoJarReader.getBitmapDrawable(context.getResources(), Uri.decode(data));
                        }*/

                        val url = URL(data)
                        val `is` = url.content as InputStream
                        try {
                            return Drawable.createFromStream(`is`, "src")
                        } finally {
                            `is`.close()
                        }
                    } catch (e: Exception) {
                        Log.w(LOGTAG, "Unable to set icon", e)
                    }
                    return null
                }

                override fun onPostExecute(drawable: Drawable?) {
                    loader.onBitmapFound(drawable)
                }
            }.execute()
            return
        }

        if (data.startsWith("-moz-icon://")) {
            val imageUri = Uri.parse(data)
            var resource = imageUri.schemeSpecificPart
            resource = resource.substring(resource.lastIndexOf('/') + 1)

            try {
                val d = context.packageManager.getApplicationIcon(resource)
                loader.onBitmapFound(d)
            } catch (ex: Exception) {
            }

            return
        }

        if (data.startsWith("drawable://")) {
            val imageUri = Uri.parse(data)
            val id = getResource(imageUri, R.drawable.fallback_favicon)
            val d = context.resources.getDrawable(id)

            loader.onBitmapFound(d)
            return
        }

        loader.onBitmapFound(null)
    }

    @JvmStatic
    @JvmOverloads
    fun decodeByteArray(bytes: ByteArray, options: BitmapFactory.Options? = null): Bitmap? {
        require(bytes.size > 0) {
            "bytes.length " + bytes.size + " must be a positive number"
        }

        var bitmap: Bitmap?
        try {
            bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
        } catch (e: OutOfMemoryError) {
            Log.e(LOGTAG, "decodeByteArray(bytes.length=" + bytes.size
                    + ", options= " + options + ") OOM!", e)
            return null
        }

        if (bitmap == null) {
            Log.w(LOGTAG, "decodeByteArray() returning null because BitmapFactory returned null")
            return null
        }

        if (bitmap.width <= 0 || bitmap.height <= 0) {
            Log.w(LOGTAG, "decodeByteArray() returning null because BitmapFactory returned "
                    + "a bitmap with dimensions " + bitmap.width
                    + "x" + bitmap.height)
            return null
        }

        return bitmap
    }

    @JvmStatic
    fun decodeStream(inputStream: InputStream): Bitmap? {
        try {
            return BitmapFactory.decodeStream(inputStream)
        } catch (e: OutOfMemoryError) {
            Log.e(LOGTAG, "decodeStream() OOM!", e)
            return null
        }
    }

    @JvmStatic
    fun decodeUrl(uri: Uri): Bitmap? {
        return decodeUrl(uri.toString())
    }

    @JvmStatic
    fun decodeUrl(urlString: String): Bitmap? {
        val url: URL

        try {
            url = URL(urlString)
        } catch (e: MalformedURLException) {
            Log.w(LOGTAG, "decodeUrl: malformed URL $urlString")
            return null
        }

        return decodeUrl(url)
    }

    @JvmStatic
    fun decodeUrl(url: URL): Bitmap? {
        val stream: InputStream?

        try {
            stream = url.openStream()
        } catch (e: IOException) {
            Log.w(LOGTAG, "decodeUrl: IOException downloading $url")
            return null
        }

        if (stream == null) {
            Log.w(LOGTAG, "decodeUrl: stream not found downloading $url")
            return null
        }

        val bitmap = decodeStream(stream)

        try {
            stream.close()
        } catch (e: IOException) {
            Log.w(LOGTAG, "decodeUrl: IOException closing stream $url", e)
        }

        return bitmap
    }

    @JvmStatic
    @JvmOverloads
    fun decodeResource(context: Context, id: Int, options: BitmapFactory.Options? = null): Bitmap? {
        val resources = context.resources
        try {
            return BitmapFactory.decodeResource(resources, id, options)
        } catch (e: OutOfMemoryError) {
            Log.e(LOGTAG, "decodeResource() OOM! Resource id=$id", e)
            return null
        }
    }

    @JvmStatic
    @JvmOverloads
    fun getDominantColor(source: Bitmap?, applyThreshold: Boolean = true): Int {
        if (source == null)
            return Color.argb(255, 255, 255, 255)

        // Keep track of how many times a hue in a given bin appears in the image.
        // Hue values range [0 .. 360), so dividing by 10, we get 36 bins.
        val colorBins = IntArray(36)

        // The bin with the most colors. Initialize to -1 to prevent accidentally
        // thinking the first bin holds the dominant color.
        var maxBin = -1

        // Keep track of sum hue/saturation/value per hue bin, which we'll use to
        // compute an average to for the dominant color.
        val sumHue = FloatArray(36)
        val sumSat = FloatArray(36)
        val sumVal = FloatArray(36)

        for (row in 0 until source.height) {
            for (col in 0 until source.width) {
                val c = source.getPixel(col, row)
                // Ignore pixels with a certain transparency.
                if (Color.alpha(c) < 128)
                    continue

                val hsv = FloatArray(3)
                Color.colorToHSV(c, hsv)

                // If a threshold is applied, ignore arbitrarily chosen values for "white" and "black".
                if (applyThreshold && (hsv[1] <= 0.35f || hsv[2] <= 0.35f))
                    continue

                // We compute the dominant color by putting colors in bins based on their hue.
                val bin = Math.floor((hsv[0] / 10.0f).toDouble()).toInt()

                // Update the sum hue/saturation/value for this bin.
                sumHue[bin] = sumHue[bin] + hsv[0]
                sumSat[bin] = sumSat[bin] + hsv[1]
                sumVal[bin] = sumVal[bin] + hsv[2]

                // Increment the number of colors in this bin.
                colorBins[bin]++

                // Keep track of the bin that holds the most colors.
                if (maxBin < 0 || colorBins[bin] > colorBins[maxBin])
                    maxBin = bin
            }
        }

        // maxBin may never get updated if the image holds only transparent and/or black/white pixels.
        if (maxBin < 0)
            return Color.argb(255, 255, 255, 255)

        // Return a color with the average hue/saturation/value of the bin with the most colors.
        val hsv = FloatArray(3)
        hsv[0] = sumHue[maxBin] / colorBins[maxBin]
        hsv[1] = sumSat[maxBin] / colorBins[maxBin]
        hsv[2] = sumVal[maxBin] / colorBins[maxBin]
        return Color.HSVToColor(hsv)
    }

    /**
     * Decodes a bitmap from a Base64 data URI.
     *
     * @param dataURI a Base64-encoded data URI string
     * @return        the decoded bitmap, or null if the data URI is invalid
     */
    @JvmStatic
    fun getBitmapFromDataURI(dataURI: String): Bitmap? {
        val base64 = dataURI.substring(dataURI.indexOf(',') + 1)
        try {
            val raw = Base64.decode(base64, Base64.DEFAULT)
            return decodeByteArray(raw)
        } catch (e: Exception) {
            Log.e(LOGTAG, "exception decoding bitmap from data URI: $dataURI", e)
        }
        return null
    }

    @JvmStatic
    fun getResource(resourceUrl: Uri, defaultIcon: Int): Int {
        var icon = defaultIcon

        val scheme = resourceUrl.scheme
        if ("drawable" == scheme) {
            var resource = resourceUrl.schemeSpecificPart
            resource = resource.substring(resource.lastIndexOf('/') + 1)

            try {
                return Integer.parseInt(resource)
            } catch (ex: NumberFormatException) {
                // This isn't a resource id, try looking for a string
            }

            try {
                val drawableClass = R.drawable::class.java
                val f = drawableClass.getField(resource)
                icon = f.getInt(null)
            } catch (e1: NoSuchFieldException) {

                // just means the resource doesn't exist for fennec. Check in Android resources
                try {
                    val drawableClass = android.R.drawable::class.java
                    val f = drawableClass.getField(resource)
                    icon = f.getInt(null)
                } catch (e2: NoSuchFieldException) {
                    // This drawable doesn't seem to exist...
                } catch (e3: Exception) {
                    Log.i(LOGTAG, "Exception getting drawable", e3)
                }
            } catch (e4: Exception) {
                Log.i(LOGTAG, "Exception getting drawable", e4)
            }
        }
        return icon
    }
}
