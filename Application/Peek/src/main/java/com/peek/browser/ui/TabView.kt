/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package com.peek.browser.ui

import android.animation.ObjectAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.util.AttributeSet
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import com.peek.browser.Constant
import com.peek.browser.MainController
import com.peek.browser.R
import com.peek.browser.Settings
import com.peek.browser.util.ScaleUpAnimHelper
import com.peek.browser.util.Util
import org.mozilla.gecko.favicons.Favicons
import java.net.MalformedURLException
import java.net.URL

open class TabView @JvmOverloads constructor(
        context: Context, attrs: AttributeSet? = null, defStyle: Int = 0
) : BubbleView(context, attrs, defStyle) {

    private var mContentView: ContentView? = null
    private lateinit var mBackIndicatorView: ImageView
    private lateinit var mBackIndicatorAnimHelper: ScaleUpAnimHelper
    private var mPerformEmptyClick = false
    private var mOriginalParamsTopMargin: Int = 0
    private var mOriginalParams: FrameLayout.LayoutParams? = null
    private var mOriginalLocationY: Float = 0f
    private var mOriginalBottomMargin: Int = 0

    @JvmField
    var mWasRestored: Boolean = false
    @JvmField
    var mIsClosing: Boolean = false

    @Throws(MalformedURLException::class)
    fun configure(url: String, urlLoadStartTime: Long, hasShownAppPicker: Boolean, performEmptyClick: Boolean) {
        super.configure(url)

        mPerformEmptyClick = performEmptyClick
        mBackIndicatorView = findViewById(R.id.back_indicator)
        if (Settings.get().darkThemeEnabled) {
            mBackIndicatorView.setBackgroundResource(R.drawable.badge_plate_dark)
            mBackIndicatorView.setImageResource(R.drawable.ic_action_arrow_left_white)
        } else {
            mBackIndicatorView.setBackgroundResource(R.drawable.badge_plate)
            mBackIndicatorView.setImageResource(R.drawable.ic_action_arrow_left)
        }
        mBackIndicatorAnimHelper = ScaleUpAnimHelper(mBackIndicatorView, 1.0f)
        mBackIndicatorAnimHelper.hide()

        mContentView = inflate(context, R.layout.view_content, null) as ContentView
        mContentView!!.configure(mUrl.toString(), this, urlLoadStartTime, hasShownAppPicker, object : ContentView.EventHandler {

            override fun onPageLoading(url: URL) {
                var setDefaultFavicon = true

                val previousUrl = mUrl
                mUrl = url

                showProgressBar(0)

                if (previousUrl != null && previousUrl.host == mUrl!!.host && mFaviconLoadId == Favicons.LOADED) {
                    setDefaultFavicon = false
                } else {
                    loadFavicon()
                    if (mFaviconLoadId == Favicons.LOADED || mFaviconLoadId == Favicons.NOT_LOADING) {
                        setDefaultFavicon = false
                    }
                }

                if (setDefaultFavicon) {
                    setDefaultFavicon()
                }
            }

            override fun onProgressChanged(progress: Int) {
                showProgressBar(progress)
            }

            override fun onPageLoaded(withError: Boolean) {
                this@TabView.onPageLoaded(withError)
            }

            override fun onReceivedIcon(favicon: Bitmap?): Boolean {
                return this@TabView.onReceivedIcon(favicon, false)
            }

            override fun setDefaultFavicon() {
                this@TabView.onReceivedIcon(null, true)
            }

            override fun onCanGoBackChanged(canGoBack: Boolean) {
                if (canGoBack) {
                    mBackIndicatorAnimHelper.show()
                } else {
                    mBackIndicatorAnimHelper.hide()
                }
            }

            override fun hasHighQualityFavicon(): Boolean {
                val tag = mFavicon.tag as String?
                val drawable = mFavicon.drawable
                if (tag != null && drawable != null && drawable is BitmapDrawable) {
                    val bitmap = drawable.bitmap
                    if (bitmap != null && bitmap.width >= Constant.DESIRED_FAVICON_SIZE) {
                        return true
                    }
                }
                return false
            }

            override fun onThemeColor(color: Int?) {
                this@TabView.onThemeColor(color)
            }
        })

        setOnClickListener {
            // TODO: How does this code path actually get hit?
            // GW: Let me know if you hit this code path.
            //Util.Assert(false);
            //MainController mainController = MainController.get();
            //mainController.switchState(mainController.STATE_AnimateToBubbleView);
        }

        setOnApplyFaviconListener(object : OnApplyFaviconListener {
            override fun applyFavicon(faviconURL: String?): Boolean {
                val currentUrl = mContentView!!.getUrl()
                if (currentUrl != null) {
                    val currentFaviconUrl = Util.getDefaultFaviconUrl(currentUrl)
                    if (faviconURL != null && faviconURL == currentFaviconUrl) {
                        return true
                    }
                    //Log.d("blerg", "Ignoring favicon " + faviconURL + " in favor of " + currentFaviconUrl);
                }

                return false
            }
        })
    }

    fun destroy() {
        // Will be null
        if (mContentView != null) {
            mContentView!!.destroy()
        }
    }

    fun toolbarHeight(): Int {
        if (null != mContentView) {
            return mContentView!!.toolbarHeight()
        }

        return 0
    }

    fun adjustBubblesPanel(adjustOn: Float, heightSizeTopMargin: Boolean, animDuration: Int): Boolean {
        if (null == mOriginalParams) {
            mOriginalParams = mContentView!!.layoutParams as? FrameLayout.LayoutParams
            if (null == mOriginalParams) {
                return false
            }
            mOriginalBottomMargin = mOriginalParams!!.bottomMargin
        }
        if (heightSizeTopMargin) {
            val currentParams = mContentView!!.layoutParams as? FrameLayout.LayoutParams
            if (null == currentParams) {
                return false
            }
            val locationYToMove = 0 - currentParams.height - currentParams.topMargin - mContentView!!.toolbarHeight()
            currentParams.bottomMargin = mOriginalBottomMargin + locationYToMove.toInt()
            mContentView!!.layoutParams = currentParams
        }

        ObjectAnimator
                .ofFloat(mContentView, "translationY", adjustOn)
                .setDuration(animDuration.toLong())
                .start()

        return true
    }

    // Empty listener is set so that the mHideListener is not still used, potentially setting the view visibilty as GONE
    /*private Animator.AnimatorListener mShowListener = new Animator.AnimatorListener() {
        @Override
        public void onAnimationStart(Animator animation) {
             mContentView.setVisibility(View.VISIBLE);
        }

        @Override
        public void onAnimationEnd(Animator animation) {
            mContentView.setVisibility(View.GONE);
        }

        @Override
        public void onAnimationCancel(Animator animation) {

        }

        @Override
        public void onAnimationRepeat(Animator animation) {

        }
    };*/

    override fun onPageLoaded(withError: Boolean) {
        super.onPageLoaded(withError)
        if (MainController.get() != null) {
            MainController.get()!!.onPageLoaded(this, withError)
        }

        if (mUrl.toString() == context.getString(R.string.empty_bubble_page)) {
            if (mPerformEmptyClick) {
                performClick()
            } else {
                mPerformEmptyClick = true
            }
        }
    }

    fun getContentView(): ContentView? {
        return mContentView
    }

    fun getTotalTrackedLoadTime(): Long {
        return mContentView!!.getTotalTrackedLoadTime()
    }

    fun updateIncognitoMode(incognito: Boolean) {
        mContentView!!.updateIncognitoMode(incognito)
    }

    override fun setProgressColor(color: Int) {
        super.setProgressColor(color)
        mContentView!!.setFaviconColor(color)
    }
}
