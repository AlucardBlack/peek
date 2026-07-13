/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package com.peek.browser.util

import android.app.Dialog
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.content.res.Configuration
import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PaintFlagsDrawFilter
import android.graphics.Rect
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.PaintDrawable
import android.net.Uri
import android.os.Build
import android.util.DisplayMetrics
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.webkit.WebSettings
import android.webkit.WebView
import android.widget.TextView
import androidx.annotation.DrawableRes
import androidx.core.graphics.drawable.DrawableCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.peek.browser.BuildConfig
import com.peek.browser.Config
import com.peek.browser.Constant
import com.peek.browser.MainApplication
import com.peek.browser.R
import com.peek.browser.Settings
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.MalformedURLException
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.Random
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

object Util {
    @JvmStatic
    fun Assert(condition: Boolean, message: String) {
        if (!condition) {
            throw AssertionError(message)
        }
    }

    // Modern Android (targeting API 35+) draws content edge-to-edge behind the status bar by
    // default, and the old windowOptOutEdgeToEdgeEnforcement manifest escape hatch stops working
    // on newer OS releases. Pad the given view (typically a Toolbar) by the status bar inset so
    // its content isn't drawn underneath - and isn't unreachable to touch - the status bar.
    @JvmStatic
    fun padForStatusBarInset(view: View) {
        val initialPaddingLeft = view.paddingLeft
        val initialPaddingTop = view.paddingTop
        val initialPaddingRight = view.paddingRight
        val initialPaddingBottom = view.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(view) { v, insets ->
            val statusBarInsetTop = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top
            v.setPadding(initialPaddingLeft, initialPaddingTop + statusBarInsetTop,
                    initialPaddingRight, initialPaddingBottom)
            insets
        }
    }

    private val whitelistedBrowsers = arrayOf(
            "com.boatbrowser.free",
            "com.boatbrowser.tablet",
            "com.boatgo.browser",
            "org.mozilla.firefox",
            "org.mozilla.firefox_beta",
            "org.mozilla.fennec_aurora",
            "org.mozilla.fennec",
            "com.android.chrome",
            "com.opera.browser",
            "com.opera.mini.native",
            "com.opera.mini.android",
            "com.opera.browser.beta",
            "com.opera.mini.native.beta",
            "com.chrome.dev",
            "com.chrome.beta",
            "com.ksmobile.cb",
            "com.UCMobile.intl",
            "mobi.mgeek.TunnyBrowser",
            "com.explore.web.browser",
            "explore.web.browser",
            "com.ghostery.android.ghostery",
            "jp.ddo.pigsty.HabitBrowser",
            "org.adblockplus.browser",
            "com.apusapps.browser",
            "com.apusapps.browser.turbo",
            "com.onedepth.search",
            "com.sec.android.app.sbrowser",
            "com.flynx",
            "com.uc.browser.en",
            "mobi.browser.flashfox",
            "acr.browser.barebones",
            "acr.browser.lightning",
            "com.ineedyourservice.RBrowser",
            "com.lastpass.lpandroid",
            "com.peek.browser.playstore",
            "com.peek.browser.playstore.dev",
            "arun.com.chromer",
            "com.jiubang.browser",
            "com.uc.browser.hd",
            "com.ilegendsoft.mercury",
            "com.UCMobile",
            "com.tencent.mtt.intl",
            "com.tencent.mtt",
            "com.cloudmosa.puffinFree",
            "com.mx.browser",
            "net.fast.web.browser",
            "com.wisesharksoftware.browser",
            "org.hola",
            "com.kk.jd.browser2",
            "com.rsbrowser.browser",
            "org.chromium.chrome",
            "com.ineedyourservice.RBrowser",
            "jp.co.fenrir.android.sleipnir",
            "jp.co.fenrir.android.sleipnir_test",
            "tugapower.codeaurora.browser",
            "com.fevdev.nakedbrowser",
            "com.fevdev.nakedbrowserpro",
            "com.yandex.browser",
            "com.yandex.browser.alpha",
            "com.yandex.browser.beta",
            "com.flyperinc.flyperlink",
            "com.wSpeedBrowser4G",
            "com.wSpeedBrowsermini",
            "com.mokee.yubrowser",
            "org.mozilla.fennec",
            "org.gnu.icecat",
            "devian.tubemate.home",
            "com.brave.browser"
    )

    @JvmStatic
    fun clamp(v0: Float, v: Float, v1: Float): Float {
        return max(v0, min(v, v1))
    }

    @JvmStatic
    fun clamp(v0: Int, v: Int, v1: Int): Int {
        return max(v0, min(v, v1))
    }

    @JvmStatic
    fun isDefaultBrowser(currentPackageName: String, packageManager: PackageManager): Boolean {
        val i = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com"))
        val info = packageManager.resolveActivity(i, 0)
        if (info != null) {
            if (info.activityInfo.applicationInfo.packageName == currentPackageName) {
                return true
            }
        }
        return false
    }

    @JvmStatic
    fun getPrettyDate(createdAt: java.util.Date): String {
        return getPrettyDate(createdAt, java.util.Date())
    }

    @JvmStatic
    fun getPrettyDate(olderDate: java.util.Date, newerDate: java.util.Date): String {
        val result: String

        val diffInDays = ((newerDate.time - olderDate.time) / (1000 * 60 * 60 * 24)).toInt()
        if (diffInDays > 365) {
            val formatted = SimpleDateFormat("dd MMM yy", Locale.US)
            result = formatted.format(olderDate)
        } else if (diffInDays > 0) {
            result = if (diffInDays == 1) {
                "1d"
            } else if (diffInDays < 8) {
                "${diffInDays}d"
            } else {
                val formatted = SimpleDateFormat("dd MMM", Locale.US)
                formatted.format(olderDate)
            }
        } else {
            val diffInHours = ((newerDate.time - olderDate.time) / (1000 * 60 * 60)).toInt()
            if (diffInHours > 0) {
                result = if (diffInHours == 1) "1h" else "${diffInHours}h"
            } else {
                val diffInMinutes = ((newerDate.time - olderDate.time) / (1000 * 60)).toInt()
                if (diffInMinutes > 0) {
                    result = if (diffInMinutes == 1) "1m" else "${diffInMinutes}m"
                } else {
                    val diffInSeconds = ((newerDate.time - olderDate.time) / 1000).toInt()
                    result = if (diffInSeconds < 5) "now" else "${diffInSeconds}s"
                }
            }
        }

        return result
    }

    @JvmStatic
    fun getPrettyTimeElapsed(resources: Resources, time: Long, separator: String): String {
        val timeAsSeconds = time.toFloat() / 1000f
        return if (timeAsSeconds < 60) {
            String.format("%.1f", timeAsSeconds) + separator + resources.getString(R.string.time_seconds)
        } else if (timeAsSeconds < 60 * 60) {
            String.format("%.1f", timeAsSeconds / 60f) + separator + resources.getString(R.string.time_minutes)
        } else {
            String.format("%.1f", timeAsSeconds / 60f / 60f) + separator + resources.getString(R.string.time_hours)
        }
    }

    @JvmStatic
    fun downloadJSONAsString(url: String, timeout: Int): String? {
        try {
            val u = URL(url)
            val connection = u.openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.setRequestProperty("Content-length", "0")
            connection.useCaches = false
            connection.allowUserInteraction = false
            connection.connectTimeout = timeout
            connection.readTimeout = timeout
            connection.connect()
            val status = connection.responseCode

            when (status) {
                200, 201 -> {
                    val br = BufferedReader(InputStreamReader(connection.inputStream))
                    val sb = StringBuilder()
                    var line: String?
                    while (br.readLine().also { line = it } != null) {
                        sb.append(line + "\n")
                    }
                    br.close()
                    return sb.toString()
                }
            }
        } catch (ex: MalformedURLException) {
            ex.printStackTrace()
        } catch (ex: IOException) {
            ex.printStackTrace()
        }
        return null
    }

    @JvmStatic
    fun distance(x0: Float, y0: Float, x1: Float, y1: Float): Float {
        val xd = x1 - x0
        val yd = y1 - y0
        return sqrt(xd * xd + yd * yd)
    }

    @JvmStatic
    fun getDefaultFaviconUrl(url: URL): String {
        return url.protocol + "://" + url.host + "/favicon.ico"
    }

    private const val OUTCODE_INSIDE = 0
    private const val OUTCODE_LEFT = 1
    private const val OUTCODE_RIGHT = 2
    private const val OUTCODE_BOTTOM = 4
    private const val OUTCODE_TOP = 8

    class ClipResult {
        @JvmField var x0 = 0
        @JvmField var y0 = 0
        @JvmField var x1 = 0
        @JvmField var y1 = 0
    }

    class Point {
        @JvmField var x = 0
        @JvmField var y = 0
    }

    private fun computeOutCode(x: Float, y: Float, xmin: Float, ymin: Float, xmax: Float, ymax: Float): Int {
        var code = OUTCODE_INSIDE

        if (x < xmin)           // to the left of clip window
            code = code or OUTCODE_LEFT
        else if (x > xmax)      // to the right of clip window
            code = code or OUTCODE_RIGHT
        if (y < ymin)           // below the clip window
            code = code or OUTCODE_BOTTOM
        else if (y > ymax)      // above the clip window
            code = code or OUTCODE_TOP

        return code
    }

    // Naive Java port of Cohen Sutherland clipping algorithm.
    // See: http://en.wikipedia.org/wiki/Cohen%E2%80%93Sutherland_algorithm
    @JvmStatic
    fun clipLineSegmentToRectangle(x0In: Float, y0In: Float, x1In: Float, y1In: Float,
                                    xmin: Float, ymin: Float, xmax: Float, ymax: Float,
                                    clipResult: ClipResult): Boolean {
        var x0 = x0In
        var y0 = y0In
        var x1 = x1In
        var y1 = y1In

        // compute outcodes for P0, P1, and whatever point lies outside the clip rectangle
        var outcode0 = computeOutCode(x0, y0, xmin, ymin, xmax, ymax)
        var outcode1 = computeOutCode(x1, y1, xmin, ymin, xmax, ymax)
        var accept = false

        while (true) {
            if (outcode0 or outcode1 == 0) { // Bitwise OR is 0. Trivially accept and get out of loop
                accept = true
                break
            } else if (outcode0 and outcode1 != 0) { // Bitwise AND is not 0. Trivially reject and get out of loop
                break
            } else {
                // failed both tests, so calculate the line segment to clip
                // from an outside point to an intersection with clip edge
                val x: Float
                val y: Float

                // At least one endpoint is outside the clip rectangle; pick it.
                val outcodeOut = if (outcode0 != 0) outcode0 else outcode1

                // Now find the intersection point;
                // use formulas y = y0 + slope * (x - x0), x = x0 + (1 / slope) * (y - y0)
                if (outcodeOut and OUTCODE_TOP != 0) {           // point is above the clip rectangle
                    x = x0 + (x1 - x0) * (ymax - y0) / (y1 - y0)
                    y = ymax
                } else if (outcodeOut and OUTCODE_BOTTOM != 0) { // point is below the clip rectangle
                    x = x0 + (x1 - x0) * (ymin - y0) / (y1 - y0)
                    y = ymin
                } else if (outcodeOut and OUTCODE_RIGHT != 0) {  // point is to the right of clip rectangle
                    y = y0 + (y1 - y0) * (xmax - x0) / (x1 - x0)
                    x = xmax
                } else {   // point is to the left of clip rectangle
                    Assert(outcodeOut and OUTCODE_LEFT != 0, "outcodeOut:$outcodeOut")
                    y = y0 + (y1 - y0) * (xmin - x0) / (x1 - x0)
                    x = xmin
                }

                // Now we move outside point to intersection point to clip
                // and get ready for next pass.
                if (outcodeOut == outcode0) {
                    x0 = x
                    y0 = y
                    outcode0 = computeOutCode(x0, y0, xmin, ymin, xmax, ymax)
                } else {
                    x1 = x
                    y1 = y
                    outcode1 = computeOutCode(x1, y1, xmin, ymin, xmax, ymax)
                }
            }
        }

        if (accept) {
            // Following functions are left for implementation by user based on
            // their platform (OpenGL/graphics.h etc.)
            clipResult.x0 = (x0 + 0.5f).toInt()
            clipResult.y0 = (y0 + 0.5f).toInt()
            clipResult.x1 = (x1 + 0.5f).toInt()
            clipResult.y1 = (y1 + 0.5f).toInt()
        }

        return accept
    }

    @JvmStatic
    fun closestPointToLineSegment(ax: Float, ay: Float, bx: Float, by: Float, px: Float, py: Float, p: Point) {
        val apX = px - ax
        val apY = py - ay

        val abX = bx - ax
        val abY = by - ay

        val abSq = abX * abX + abY * abY
        val dot = apX * abX + apY * abY
        var t = dot / abSq

        t = clamp(0.0f, t, 1.0f)

        p.x = (0.5f + ax + abX * t).toInt()
        p.y = (0.5f + ay + abY * t).toInt()
    }

    @JvmStatic
    fun isPeekResolveInfo(resolveInfo: ResolveInfo?): Boolean {
        if (resolveInfo != null
                && resolveInfo.activityInfo != null
                && resolveInfo.activityInfo.packageName == BuildConfig.APPLICATION_ID) {
            return true
        }

        return false
    }

    private var sRandom: Random? = null

    @JvmStatic
    fun randInt(min: Int, max: Int): Int {
        var random = sRandom
        if (random == null) {
            random = Random()
            sRandom = random
        }

        // nextInt is normally exclusive of the top value,
        // so add 1 to make it inclusive
        return random.nextInt(max - min + 1) + min
    }

    @JvmStatic
    fun getDefaultBrowser(packageManager: PackageManager): ResolveInfo? {
        val intent = Intent(Intent.ACTION_VIEW)
        intent.data = Uri.parse(Config.SET_DEFAULT_BROWSER_URL)
        return packageManager.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)
    }

    @JvmStatic
    fun replaceViewAtPosition(viewToReplace: View, replaceWith: View) {
        val parent = viewToReplace.parent as ViewGroup
        val index = parent.indexOfChild(viewToReplace)
        parent.removeView(viewToReplace)
        parent.addView(replaceWith, index)
    }

    private var sIconWidth = -1
    private var sIconHeight = -1
    private var sIconTextureWidth = -1
    private var sIconTextureHeight = -1

    private val sOldBounds = Rect()
    private val sCanvas = Canvas()

    init {
        sCanvas.drawFilter = PaintFlagsDrawFilter(Paint.DITHER_FLAG, Paint.FILTER_BITMAP_FLAG)
    }

    private val sColors = intArrayOf(-0x10000, -0xff0100, -0xffff01)
    private var sColorIndex = 0

    private fun initStatics(context: Context) {
        val resources = context.resources

        sIconWidth = resources.getDimension(R.dimen.app_icon_size).toInt()
        sIconHeight = sIconWidth
        sIconTextureWidth = sIconWidth
        sIconTextureHeight = sIconWidth
    }

    /**
     * Returns a bitmap suitable for the all apps view. Used to convert pre-ICS
     * icon bitmaps that are stored in the database (which were 74x74 pixels at hdpi size)
     * to the proper size (48dp)
     */
    fun createIconBitmap(icon: Bitmap, context: Context): Bitmap {
        val textureWidth = sIconTextureWidth
        val textureHeight = sIconTextureHeight
        val sourceWidth = icon.width
        val sourceHeight = icon.height
        return if (sourceWidth > textureWidth && sourceHeight > textureHeight) {
            // Icon is bigger than it should be; clip it (solves the GB->ICS migration case)
            Bitmap.createBitmap(icon,
                    (sourceWidth - textureWidth) / 2,
                    (sourceHeight - textureHeight) / 2,
                    textureWidth, textureHeight)
        } else if (sourceWidth == textureWidth && sourceHeight == textureHeight) {
            // Icon is the right size, no need to change it
            icon
        } else {
            // Icon is too small, render to a larger bitmap
            val resources = context.resources
            createIconBitmap(BitmapDrawable(resources, icon), context)
        }
    }

    /**
     * Returns a bitmap suitable for the all apps view.
     */
    fun createIconBitmap(icon: Drawable, context: Context): Bitmap {
        synchronized(sCanvas) { // we share the statics :-(
            if (sIconWidth == -1) {
                initStatics(context)
            }

            var width = sIconWidth
            var height = sIconHeight

            if (icon is PaintDrawable) {
                icon.setIntrinsicWidth(width)
                icon.setIntrinsicHeight(height)
            } else if (icon is BitmapDrawable) {
                // Ensure the bitmap has a density.
                val bitmap = icon.bitmap
                if (bitmap.density == Bitmap.DENSITY_NONE) {
                    icon.setTargetDensity(context.resources.displayMetrics)
                }
            }
            val sourceWidth = icon.intrinsicWidth
            val sourceHeight = icon.intrinsicHeight
            if (sourceWidth > 0 && sourceHeight > 0) {
                // There are intrinsic sizes.
                if (width < sourceWidth || height < sourceHeight) {
                    // It's too big, scale it down.
                    val ratio = sourceWidth.toFloat() / sourceHeight
                    if (sourceWidth > sourceHeight) {
                        height = (width / ratio).toInt()
                    } else if (sourceHeight > sourceWidth) {
                        width = (height * ratio).toInt()
                    }
                } else if (sourceWidth < width && sourceHeight < height) {
                    // Don't scale up the icon
                    width = sourceWidth
                    height = sourceHeight
                }
            }

            // no intrinsic size --> use default size
            val textureWidth = sIconTextureWidth
            val textureHeight = sIconTextureHeight

            val bitmap = Bitmap.createBitmap(textureWidth, textureHeight,
                    Bitmap.Config.ARGB_8888)
            val canvas = sCanvas
            canvas.setBitmap(bitmap)

            val left = (textureWidth - width) / 2
            val top = (textureHeight - height) / 2

            val debug = false
            if (debug) {
                // draw a big box for the icon for debugging
                canvas.drawColor(sColors[sColorIndex])
                if (++sColorIndex >= sColors.size) sColorIndex = 0
                val debugPaint = Paint()
                debugPaint.color = -0x333400
                canvas.drawRect(left.toFloat(), top.toFloat(), (left + width).toFloat(), (top + height).toFloat(), debugPaint)
            }

            sOldBounds.set(icon.bounds)
            icon.setBounds(left, top, left + width, top + height)
            icon.draw(canvas)
            icon.bounds = sOldBounds
            canvas.setBitmap(null)

            return bitmap
        }
    }

    fun getComponentNameFromResolveInfo(info: ResolveInfo): ComponentName {
        return if (info.activityInfo != null) {
            ComponentName(info.activityInfo.packageName, info.activityInfo.name)
        } else {
            ComponentName(info.serviceInfo.packageName, info.serviceInfo.name)
        }
    }

    @JvmStatic
    fun getDefaultLauncherPackage(packageManager: PackageManager): String? {
        val intent = Intent(Intent.ACTION_MAIN)
        intent.addCategory(Intent.CATEGORY_HOME)
        val resolveInfo = packageManager.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)
        if (resolveInfo != null && resolveInfo.activityInfo != null) {
            if (resolveInfo.activityInfo.packageName != "android") {
                //setDefaultLauncherPreference.setSummary(R.string.not_default_youtube_app);
                return resolveInfo.activityInfo.packageName
            }
        }

        return null
    }

    @JvmStatic
    fun getSendIntent(packageName: String, className: String, urlAsString: String): Intent {
        // TODO: Retrieve the class name below from the app in case Twitter ever change it.
        val intent = Intent(Intent.ACTION_SEND)
        intent.type = "text/plain"
        intent.setClassName(packageName, className)
        if (packageName == Constant.POCKET_PACKAGE_NAME) {
            // Stop pocket spawning when links added
            intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK
        } else {
            intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        intent.putExtra(Intent.EXTRA_TEXT, urlAsString)
        val title = MainApplication.sTitleHashMap?.get(urlAsString)
        if (title != null) {
            intent.putExtra(Intent.EXTRA_SUBJECT, title)
        }
        return intent
    }

    @JvmStatic
    fun setLocale(context: Context, code: String) {
        val locale = Locale(code)
        Locale.setDefault(locale)
        val config = Configuration()
        config.locale = locale
        context.applicationContext.resources.updateConfiguration(config, null)
    }

    /*
     * Manually theme divider and title text with @color/apptheme_color
     */
    @JvmStatic
    fun showThemedDialog(dialog: Dialog) {
        dialog.show()

        val resources = dialog.context.resources
        val color = resources.getColor(if (Settings.get().darkThemeEnabled) R.color.color_primary_bright else R.color.color_primary)

        val dividerId = resources.getIdentifier("android:id/titleDivider", null, null)
        if (dividerId > 0) {
            val divider = dialog.findViewById<View>(dividerId)
            divider?.setBackgroundColor(color)
        }

        val titleTextViewId = resources.getIdentifier("android:id/alertTitle", null, null)
        if (titleTextViewId > 0) {
            val textView = dialog.findViewById<TextView>(titleTextViewId)
            textView?.setTextColor(color)
        }
    }

    private var sDensityDpi: Int? = null

    /*
     * Use lower density icon on AlertDialogs as large icons look silly
     */
    private fun getAlertIconDensityDpi(context: Context): Int {
        var densityDpi = sDensityDpi
        if (densityDpi == null) {
            densityDpi = context.resources.displayMetrics.densityDpi
            sDensityDpi = densityDpi
        }

        return when (densityDpi) {
            DisplayMetrics.DENSITY_LOW, DisplayMetrics.DENSITY_MEDIUM -> DisplayMetrics.DENSITY_LOW
            DisplayMetrics.DENSITY_TV, DisplayMetrics.DENSITY_HIGH -> DisplayMetrics.DENSITY_MEDIUM
            DisplayMetrics.DENSITY_XHIGH -> DisplayMetrics.DENSITY_HIGH
            DisplayMetrics.DENSITY_XXHIGH, DisplayMetrics.DENSITY_XXXHIGH -> DisplayMetrics.DENSITY_XHIGH
            else -> densityDpi
        }
    }

    @JvmStatic
    fun getAlertIcon(context: Context): Drawable? {
        return context.resources.getDrawableForDensity(R.mipmap.ic_launcher, getAlertIconDensityDpi(context))
    }

    @JvmStatic
    fun isValidBrowserPackageName(packageName: String): Boolean {
        if (packageName == BuildConfig.APPLICATION_ID || packageName.contains("com.digitalashes.tappath")) {
            return false
        }
        if (BuildConfig.APPLICATION_ID.contains("com.peek.browser") && packageName.contains("com.peek.browser")) {
            return false
        }

        return whitelistedBrowsers.contains(packageName)
    }

    @JvmStatic
    fun getSystemActionBarHeight(context: Context): Int? {
        // Calculate ActionBar height
        try {
            val tv = TypedValue()
            if (context.theme.resolveAttribute(android.R.attr.actionBarSize, tv, true)) {
                return TypedValue.complexToDimensionPixelSize(tv.data, context.resources.displayMetrics)
            }
        } catch (e: Exception) {
        }

        return null
    }

    @JvmStatic
    fun getSystemStatusBarHeight(context: Context): Int? {
        var result: Int? = null
        try {
            val resources = context.resources
            val resourceId = resources.getIdentifier("status_bar_height", "dimen", "android")
            if (resourceId > 0) {
                result = resources.getDimensionPixelSize(resourceId)
            }
        } catch (e: Exception) {
        }

        return result
    }

    @JvmStatic
    fun getSystemNavigationBarHeight(context: Context): Int? {
        var result: Int? = null
        try {
            val resources = context.resources
            val resourceId = resources.getIdentifier("navigation_bar_height", "dimen", "android")
            if (resourceId > 0) {
                result = resources.getDimensionPixelSize(resourceId)
            }
        } catch (e: Exception) {
        }

        return result
    }

    @JvmStatic
    fun getLauncherAppForApplicationIds(context: Context,
                                         applicationId: String): List<ResolveInfo>? {
        val intent = Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_LAUNCHER)
                .setPackage(applicationId)
        val resolveInfos = context.packageManager.queryIntentActivities(intent, 0)
        if (resolveInfos != null && resolveInfos.size > 0) {
            return resolveInfos
        }
        return null
    }

    @JvmStatic
    fun getLauncherAppForApplicationId(context: Context,
                                        applicationId: String): ResolveInfo? {
        val result = getLauncherAppForApplicationIds(context, applicationId)
        if (result != null && result.isNotEmpty()) {
            return result[0]
        }
        return null
    }

    @JvmStatic
    fun getTintableDrawable(context: Context, @DrawableRes resId: Int): Drawable {
        var d = context.resources.getDrawable(resId)
        d = DrawableCompat.wrap(d)
        return d
    }

    /**
     * From http://stackoverflow.com/a/5261472/328679
     */
    @JvmStatic
    fun getDefaultUserAgentString(context: Context): String {
        if (Build.VERSION.SDK_INT >= 17) {
            return WebSettings.getDefaultUserAgent(context)
        }

        try {
            val constructor = WebSettings::class.java.getDeclaredConstructor(Context::class.java, WebView::class.java)
            constructor.isAccessible = true
            try {
                val settings = constructor.newInstance(context, null)
                return settings.userAgentString
            } finally {
                constructor.isAccessible = false
            }
        } catch (e: Exception) {
            return if (Config.sIsTablet) Constant.USER_AGENT_CHROME_TABLET else Constant.USER_AGENT_CHROME_PHONE
        }
    }

    // Remove http or https from url
    @JvmStatic
    fun getUrlWithoutHttpHttpsWww(context: Context, url: String): String {
        var result = url
        if (result.startsWith(context.getString(R.string.http_prefix))) {
            result = result.substring(context.getString(R.string.http_prefix).length)
        } else if (result.startsWith(context.getString(R.string.https_prefix))) {
            result = result.substring(context.getString(R.string.https_prefix).length)
        }

        if (result.startsWith(context.getString(R.string.www_prefix))) {
            result = result.substring(context.getString(R.string.www_prefix).length)
        }

        if (result.endsWith("/")) {
            result = result.substring(0, result.length - 1)
        }

        return result
    }

    @JvmStatic
    fun getUrlWithPrefix(context: Context, url: String): String {
        if (!url.startsWith(context.getString(R.string.http_prefix)) &&
                !url.startsWith(context.getString(R.string.https_prefix)))
            return context.getString(R.string.http_prefix) + url

        return url
    }

    @JvmStatic
    fun isValidURL(context: Context, url: String): Boolean {
        var result = true

        try {
            URL(url)
        } catch (e: MalformedURLException) {
            result = false
        }

        return result
    }
}
