/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package com.peek.browser.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import androidx.palette.graphics.Palette
import android.util.AttributeSet
import android.util.Log
import android.widget.FrameLayout
import com.peek.browser.Constant
import com.peek.browser.MainApplication
import com.peek.browser.R
import com.peek.browser.Settings
import com.peek.browser.util.Util
import org.mozilla.gecko.favicons.Favicons
import org.mozilla.gecko.favicons.LoadFaviconTask
import org.mozilla.gecko.favicons.OnFaviconLoadedListener
import org.mozilla.gecko.widget.FaviconView
import java.net.MalformedURLException
import java.net.URL

open class BubbleView @JvmOverloads constructor(
        context: Context, attrs: AttributeSet? = null, defStyle: Int = 0
) : FrameLayout(context, attrs, defStyle) {

    interface OnApplyFaviconListener {
        fun applyFavicon(faviconURL: String?): Boolean
    }

    protected lateinit var mFavicon: FaviconView
    protected var mFaviconLoadId: Int = 0
    private lateinit var mProgressIndicator: ProgressIndicatorView
    protected var mUrl: URL? = null
    private var mImitator: BubbleView? = null
    private var mOnApplyFaviconListener: OnApplyFaviconListener? = null

    open fun configure() {
        mFavicon = findViewById(R.id.favicon)
        mFavicon.setOnPaletteChangeListener(mOnPaletteChangeListener)
        mFavicon.mFavicons = MainApplication.sFavicons
        mProgressIndicator = findViewById(R.id.progressIndicator)
        showProgressBar(0)
    }

    @Throws(MalformedURLException::class)
    open fun configure(url: String) {
        mUrl = URL(url)

        configure()
    }

    fun setOnApplyFaviconListener(onApplyFaviconListener: OnApplyFaviconListener) {
        mOnApplyFaviconListener = onApplyFaviconListener
    }

    open fun getUrl(): URL? {
        return mUrl
    }

    fun getFavicon(): Drawable? {
        return mFavicon.drawable
    }

    private fun setFavicon(bitmap: Bitmap, faviconUrl: String) {
        mFavicon.updateImage(bitmap, faviconUrl, mThemeColor == null)
        mFavicon.tag = faviconUrl
    }

    private fun setFallbackFavicon() {
        mFavicon.showDefaultFavicon()
        mFavicon.tag = null
    }

    fun setImitator(bubbleView: BubbleView?) {
        mImitator = bubbleView
        if (mImitator != null) {
            val tag = mFavicon.tag as String?
            val drawable = mFavicon.drawable
            if (tag != null && drawable != null) {
                mImitator!!.setFavicon((drawable as BitmapDrawable).bitmap, tag)
            } else {
                mImitator!!.setFallbackFavicon()
            }
            mImitator!!.mProgressIndicator.setProgress(mProgressIndicator.getProgress(), mUrl!!)
        }
    }

    fun setFaviconLoadId(faviconLoadId: Int) {
        mFaviconLoadId = faviconLoadId
    }

    fun getFaviconLoadId(): Int {
        return mFaviconLoadId
    }

    protected open fun loadFavicon() {
        mThemeColor = null
        maybeCancelFaviconLoad()

        //int flags = (tab.isPrivate() || tab.getErrorType() != Tab.ErrorType.NONE) ? 0 : LoadFaviconTask.FLAG_PERSIST;
        var flags = if (Settings.get().isIncognitoMode) 0 else LoadFaviconTask.FLAG_PERSIST
        flags = flags or Favicons.FLAG_OFFLINE_NO_CACHE
        val faviconUrl = Util.getDefaultFaviconUrl(mUrl!!)
        val faviconLoadIdBefore = mFaviconLoadId
        val id = MainApplication.sFavicons!!.getFaviconForSize(mUrl.toString(), faviconUrl, Int.MAX_VALUE, flags, mOnFaviconLoadedListener)

        // If the favicon is cached, mOnFaviconLoadedListener.onFaviconLoaded() will be called before this check is reached,
        // and this call will have already set mFaviconLoadId. Thus only act on the id return value if the value was not already changed
        if (faviconLoadIdBefore == mFaviconLoadId) {
            setFaviconLoadId(id)
            if (id != Favicons.LOADED) {
                setFallbackFavicon()
                if (mImitator != null) {
                    mImitator!!.setFallbackFavicon()
                }
                if (DEBUG) {
                    Log.d(TAG, "[favicon] loadFavicon: setImageBitmap() FALLBACK for $faviconUrl")
                }
            }
        }
    }

    var mOnFaviconLoadedListener: OnFaviconLoadedListener = object : OnFaviconLoadedListener {
        override fun onFaviconLoaded(url: String?, faviconURL: String?, favicon: Bitmap?) {
            if (favicon != null) {
                if (mOnApplyFaviconListener != null && mOnApplyFaviconListener!!.applyFavicon(faviconURL) == false) {
                    return
                }
                // Note: don't upsize favicon because Favicons.getFaviconForSize() already does this
                setFavicon(favicon, faviconURL!!)
                setFaviconLoadId(Favicons.LOADED)
                if (mImitator != null) {
                    mImitator!!.setFavicon(favicon, faviconURL)
                    mImitator!!.setFaviconLoadId(Favicons.LOADED)
                }
                if (DEBUG) {
                    Log.d(TAG, "[favicon] mOnFaviconLoadedListener: setImageBitmap() size:" + favicon.width + " for " + faviconURL)
                }
            }
        }
    }

    private fun maybeCancelFaviconLoad() {
        val faviconLoadId = getFaviconLoadId()

        if (Favicons.NOT_LOADING == faviconLoadId) {
            return
        }

        // Cancel load task and reset favicon load state if it wasn't already
        // in NOT_LOADING state.
        MainApplication.sFavicons!!.cancelFaviconLoad(faviconLoadId)
        setFaviconLoadId(Favicons.NOT_LOADING)
    }

    private class FaviconTransformation {
        fun transform(source: Bitmap): Bitmap {
            var w = source.width
            var h = source.height

            val reqW = Math.min(Constant.DESIRED_FAVICON_SIZE, w * 2)
            val reqH = Math.min(Constant.DESIRED_FAVICON_SIZE, h * 2)

            if (w != reqW || h != reqH) {
                w = reqW
                h = reqH

                var result = source
                try {
                    result = Bitmap.createScaledBitmap(source, w, h, true)
                } catch (e: OutOfMemoryError) {

                }

                return result
            }

            return source
        }
    }

    private var mFaviconTransformation = FaviconTransformation()

    protected open fun onPageLoaded(withError: Boolean) {
        showProgressBar(100)
    }

    protected open fun onReceivedIcon(faviconIn: Bitmap?, forceSet: Boolean): Boolean {
        var favicon = faviconIn
        var appliedFavicon = false
        if (favicon == null) {
            // Don't update if an image already exists. Optimization as the fallback favicon is already set via loadFavicon()
            if (mFavicon.drawable == null || forceSet) {
                mFaviconLoadId = Favicons.NOT_LOADING
                setFallbackFavicon()
                if (mImitator != null) {
                    mImitator!!.mFaviconLoadId = Favicons.NOT_LOADING
                    mImitator!!.setFallbackFavicon()
                }
                if (DEBUG) {
                    Log.d(TAG, "[favicon] onReceivedIcon: setImageBitmap() FALLBACK on host " + mUrl!!.host)
                }
            }
        } else {
            val faviconUrl = Util.getDefaultFaviconUrl(mUrl!!)
            val faviconTag = if (mFavicon.tag is String) mFavicon.tag as String else null

            // We will ignore this if we are already using a larger icon which was retrieved as a TouchIcon specified
            // in the page URL. Technically this is probably incorrect behaviour for a browser,
            // but the larger icons look better, so I'm going with it.
            var applyFavicon = true
            if (faviconTag != null && faviconTag == faviconUrl) {
                val currentFavicon = mFavicon.drawable
                if (currentFavicon != null && currentFavicon is BitmapDrawable) {
                    if (currentFavicon.bitmap.width > favicon.width) {
                        applyFavicon = false
                    }
                }
            }

            if (applyFavicon || forceSet) {
                if (MainApplication.sDatabaseHelper!!.faviconExists(faviconUrl, favicon) == false) {
                    MainApplication.sDatabaseHelper!!.addFaviconForUrl(faviconUrl, favicon, mUrl.toString())
                }

                favicon = mFaviconTransformation.transform(favicon)

                mFaviconLoadId = Favicons.LOADED
                setFavicon(favicon, faviconUrl)
                if (mImitator != null) {
                    mImitator!!.mFaviconLoadId = Favicons.LOADED
                    mImitator!!.setFavicon(favicon, faviconUrl)
                }
                if (DEBUG) {
                    Log.d(TAG, "[favicon] onReceivedIcon: setImageBitmap() size:" + favicon.width + " on host " + mUrl!!.host)
                }
                appliedFavicon = true
            }
        }

        mFavicon.visibility = VISIBLE
        return appliedFavicon
    }

    var mThemeColor: Int? = null
    open fun onThemeColor(color: Int?) {
        mThemeColor = color
        mProgressIndicator.setColor(color!!)

        if (mImitator != null) {
            mImitator!!.onThemeColor(color)
        }
    }

    fun onProgressChanged(progress: Int) {
        showProgressBar(progress)
    }

    /*
    Handler mHandler = new Handler();
    float mTempProgress = 0.f;
    Runnable mProgressRunnable = new Runnable() {
        @Override
        public void run() {
            mProgressIndicator.setProgress((int) mTempProgress);
            mTempProgress += .3f;
            if (mTempProgress >= 100) {
                mTempProgress -= 100;
            }
            mHandler.postDelayed(mProgressRunnable, 33);
        }
    };*/

    fun showProgressBar(progress: Int) {
        mProgressIndicator.setProgress(progress, mUrl!!)
        if (mImitator != null) {
            mImitator!!.mProgressIndicator.setProgress(progress, mUrl!!)
        }
    }

    val mOnPaletteChangeListener: FaviconView.OnPaletteChangeListener = object : FaviconView.OnPaletteChangeListener {
        override fun onPaletteChange(palette: Palette) {
            if (mThemeColor != null) {
                return
            }
            if (palette == null) {
                setProgressColor(Settings.get().getThemedDefaultProgressColor())
                return
            }
            if (palette.darkVibrantSwatch != null) {
                setProgressColor(palette.darkVibrantSwatch!!.rgb)
            } else {
                if (palette.darkMutedSwatch != null) {
                    setProgressColor(palette.darkMutedSwatch!!.rgb)
                } else {
                    if (palette.mutedSwatch != null) {
                        setProgressColor(palette.mutedSwatch!!.rgb)
                    } else {
                        if (palette.vibrantSwatch != null) {
                            setProgressColor(palette.vibrantSwatch!!.rgb)
                        } else {
                            setProgressColor(Settings.get().getThemedDefaultProgressColor())
                        }
                    }
                }
            }
        }
    }

    open fun setProgressColor(color: Int) {
        mProgressIndicator.setColor(color)
    }

    companion object {
        private const val TAG = "BubbleView"
        private const val DEBUG = false
    }
}
