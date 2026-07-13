/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package com.peek.browser.ui

import android.app.AlertDialog
import android.content.Context
import android.content.pm.ResolveInfo
import android.content.res.Resources
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.PorterDuff
import android.graphics.drawable.Drawable
import android.util.AttributeSet
import android.view.View
import android.view.WindowManager
import com.peek.browser.MainApplication
import com.peek.browser.R
import com.peek.browser.Settings
import com.peek.browser.util.ActionItem
import com.peek.browser.util.Util
import java.util.ArrayList

class OpenInAppButton @JvmOverloads constructor(
        context: Context, attrs: AttributeSet? = null, defStyle: Int = 0
) : ContentViewButton(context, attrs, defStyle), View.OnClickListener {

    private var mOnOpenInAppClickListener: OnOpenInAppClickListener? = null

    private val mAppsForUrl: MutableList<ContentView.AppForUrl> = ArrayList()
    private var mParams = PreviewItemDrawingParams(0f, 0f, 0f, 0)
    private var mAppStackPadding: Int
    private var mAppStackPreviewSize: Int

    private var mIntrinsicIconSize: Int = 0
    private var mBaselineIconScale: Float = 0f
    private var mBaselineIconSize: Int = 0
    private var mAvailableSpaceInPreview: Int = 0
    private var mPreviewXOffset: Int
    private var mPreviewYOffset: Int
    private var mMaxPerspectiveShift: Float = 0f

    interface OnOpenInAppClickListener {
        fun onAppOpened()
    }

    init {
        val resources = context.resources
        mAppStackPreviewSize = resources.getDimensionPixelSize(R.dimen.content_view_button_max_height)
        mAppStackPadding = resources.getDimensionPixelSize(R.dimen.app_stack_padding)
        mPreviewXOffset = resources.getDimensionPixelSize(R.dimen.app_stack_x_offset)
        mPreviewYOffset = resources.getDimensionPixelSize(R.dimen.app_stack_y_offset)

        setOnClickListener(this)
    }

    fun setOnOpenInAppClickListener(listener: OnOpenInAppClickListener) {
        mOnOpenInAppClickListener = listener
    }

    @Suppress("EXPOSED_PARAMETER_TYPE")
    fun configure(appsForUrl: List<ContentView.AppForUrl>): Boolean {
        mAppsForUrl.clear()
        for (appForUrl in appsForUrl) {
            if (Util.isPeekResolveInfo(appForUrl.mResolveInfo) == false) {
                mAppsForUrl.add(appForUrl)
            }
        }

        val appsForUrlSize = mAppsForUrl.size
        if (appsForUrlSize == 1) {
            val appForUrl = appsForUrl[0]
            val d = appForUrl.getIcon(context)
            if (d != null) {
                setImageDrawable(d)
                visibility = VISIBLE
                tag = appForUrl
                return true
            }
        } else if (appsForUrlSize > 1) {
            visibility = VISIBLE
            setImageDrawable(null)
            return true
        }

        visibility = GONE
        return false
    }

    private fun computePreviewDrawingParams(drawableSize: Int) {
        if (mIntrinsicIconSize != drawableSize) {
            mIntrinsicIconSize = drawableSize

            computePreviewDrawingParams()
        }
    }

    fun computePreviewDrawingParams() {
        val previewSize = mAppStackPreviewSize
        val previewPadding = mAppStackPadding

        mAvailableSpaceInPreview = (previewSize - 2 * previewPadding)
        // cos(45) = 0.707  + ~= 0.1) = 0.8f
        val adjustedAvailableSpace = ((mAvailableSpaceInPreview / 2) * (1 + 0.8f)).toInt()

        val unscaledHeight = (mIntrinsicIconSize * (1 + PERSPECTIVE_SHIFT_FACTOR)).toInt()
        mBaselineIconScale = (1.0f * adjustedAvailableSpace / unscaledHeight)

        mBaselineIconSize = (mIntrinsicIconSize * mBaselineIconScale).toInt()
        mMaxPerspectiveShift = mBaselineIconSize * PERSPECTIVE_SHIFT_FACTOR
    }

    private fun computePreviewDrawingParams(d: Drawable) {
        computePreviewDrawingParams(d.intrinsicWidth)
    }

    class PreviewItemDrawingParams(var transX: Float, var transY: Float, var scale: Float, var overlayAlpha: Int) {
        var drawable: Drawable? = null
    }

    override fun dispatchDraw(canvas: Canvas) {
        super.dispatchDraw(canvas)

        if (mAppsForUrl.size <= 1) {
            return
        }

        val context = context
        var d = mAppsForUrl[0].getIcon(context)
        if (d != null) {
            computePreviewDrawingParams(d)
        }

        if (mIsTouched) {
            canvas.drawColor(sTouchedColor)
        }

        val nItemsInPreview = Math.min(mAppsForUrl.size, NUM_ITEMS_IN_PREVIEW)
        for (i in nItemsInPreview - 1 downTo 0) {
            d = mAppsForUrl[i].getIcon(context)
            mParams = computePreviewItemDrawingParams(i, mParams)
            mParams.drawable = d
            drawPreviewItem(canvas, mParams)
        }
    }

    private fun computePreviewItemDrawingParams(indexIn: Int,
                                                 params: PreviewItemDrawingParams?): PreviewItemDrawingParams {
        val index = NUM_ITEMS_IN_PREVIEW - indexIn - 1
        val r = (index * 1.0f) / (NUM_ITEMS_IN_PREVIEW - 1)
        val scale = (1 - PERSPECTIVE_SCALE_FACTOR * (1 - r))

        val offset = (1 - r) * mMaxPerspectiveShift
        val scaledSize = scale * mBaselineIconSize
        val scaleOffsetCorrection = (1 - scale) * mBaselineIconSize

        // We want to imagine our coordinates from the bottom left, growing up and to the
        // right. This is natural for the x-axis, but for the y-axis, we have to invert things.

        //float cellScale = preferences.getWorkspaceCellScale();
        val previewXOffset = mPreviewXOffset.toFloat()
        val previewYOffset = mPreviewYOffset.toFloat()

        val transY = mAvailableSpaceInPreview - (offset + scaledSize + scaleOffsetCorrection) + previewYOffset
        val transX = offset + scaleOffsetCorrection + previewXOffset
        val totalScale = mBaselineIconScale * scale
        val overlayAlpha = (80 * (1 - r)).toInt()

        return if (params == null) {
            PreviewItemDrawingParams(transX, transY, totalScale, overlayAlpha)
        } else {
            params.transX = transX
            params.transY = transY
            params.scale = totalScale
            params.overlayAlpha = overlayAlpha
            params
        }
    }

    private fun drawPreviewItem(canvas: Canvas, params: PreviewItemDrawingParams) {
        canvas.save()
        canvas.translate(params.transX, params.transY)
        canvas.scale(params.scale, params.scale)
        val d = params.drawable

        if (d != null) {
            d.setBounds(0, 0, mIntrinsicIconSize, mIntrinsicIconSize)
            d.isFilterBitmap = true
            d.setColorFilter(Color.argb(params.overlayAlpha, 0, 0, 0), PorterDuff.Mode.SRC_ATOP)
            d.draw(canvas)
            d.clearColorFilter()
            d.isFilterBitmap = false
        }
        canvas.restore()
    }

    override fun onClick(v: View) {
        if (v.tag is ContentView.AppForUrl) {
            val appForUrl = v.tag as ContentView.AppForUrl
            if (MainApplication.loadIntent(context, appForUrl.mResolveInfo!!.activityInfo.packageName,
                            appForUrl.mResolveInfo!!.activityInfo.name, appForUrl.mUrl.toString(), -1, true)) {
                if (mOnOpenInAppClickListener != null) {
                    mOnOpenInAppClickListener!!.onAppOpened()
                }
            }
        } else {
            if (mAppsForUrl.size > 1) {
                val resolveInfos = ArrayList<ResolveInfo>()
                for (item in mAppsForUrl) {
                    resolveInfos.add(item.mResolveInfo!!)
                }

                if (0 != resolveInfos.size) {
                    val dialog = ActionItem.getActionItemPickerAlert(context, resolveInfos, R.string.pick_default_app,
                            object : ActionItem.OnActionItemDefaultSelectedListener {
                                override fun onSelected(actionItem: ActionItem, always: Boolean) {
                                    val appForUrl = getAppForUrl(actionItem.mPackageName, actionItem.mActivityClassName)
                                    if (appForUrl != null) {
                                        if (always) {
                                            Settings.get().setDefaultApp(appForUrl.mUrl.toString(), appForUrl.mResolveInfo!!)
                                        }
                                        if (MainApplication.loadIntent(context, appForUrl.mResolveInfo!!.activityInfo.packageName,
                                                        appForUrl.mResolveInfo!!.activityInfo.name, appForUrl.mUrl.toString(), -1, true)) {
                                            if (mOnOpenInAppClickListener != null) {
                                                mOnOpenInAppClickListener!!.onAppOpened()
                                            }
                                        }
                                    }
                                }
                            })
                    dialog.window!!.setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY)
                    Util.showThemedDialog(dialog)
                }
            }
        }
    }

    private fun getAppForUrl(packageName: String, className: String): ContentView.AppForUrl? {
        for (appForUrl in mAppsForUrl) {
            if (appForUrl.mResolveInfo!!.activityInfo.packageName == packageName
                    && appForUrl.mResolveInfo!!.activityInfo.name == className) {
                return appForUrl
            }
        }

        return null
    }

    companion object {
        private const val TAG = "OpenInAppButton"

        private const val NUM_ITEMS_IN_PREVIEW = 2

        // The amount of vertical spread between items in the stack [0...1]
        private const val PERSPECTIVE_SHIFT_FACTOR = 0.24f

        // The degree to which the item in the back of the stack is scaled [0...1]
        // (0 means it's not scaled at all, 1 means it's scaled to nothing)
        private const val PERSPECTIVE_SCALE_FACTOR = 0.35f
    }
}
