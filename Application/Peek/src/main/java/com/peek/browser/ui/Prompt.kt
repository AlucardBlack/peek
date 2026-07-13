/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package com.peek.browser.ui

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.content.Context
import android.graphics.PixelFormat
import android.os.Handler
import android.text.TextUtils
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.ViewPropertyAnimator
import android.view.WindowManager
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.widget.TextView
import com.peek.browser.R
import com.peek.browser.util.CrashTracking
import com.peek.browser.util.SwipeDismissTouchListener
import com.peek.browser.util.Util

class Prompt private constructor(context: Context) {

    interface OnPromptEventListener {
        fun onActionClick()
        fun onClose()
    }

    private val mRootView: View
    private val mBarView: View
    private val mMessageView: TextView
    private val mPromptButtonTextView: TextView
    private val mBarAnimator: ViewPropertyAnimator
    private val mHideHandler = Handler()
    private var mListener: OnPromptEventListener? = null

    private val mWindowManager: WindowManager
    private val mLayoutParams: WindowManager.LayoutParams
    val mContext: Context = context

    var mSnackbarHeight: Int = 0

    init {
        mWindowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager

        mSnackbarHeight = context.resources.getDimensionPixelSize(R.dimen.snackbar_height)

        val li = context.getSystemService(Context.LAYOUT_INFLATER_SERVICE) as LayoutInflater
        mRootView = li.inflate(R.layout.view_prompt, null)

        mLayoutParams = WindowManager.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                context.resources.getDimensionPixelSize(R.dimen.snackbar_height),
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                PixelFormat.TRANSLUCENT)
        mLayoutParams.gravity = Gravity.CENTER_HORIZONTAL or Gravity.BOTTOM
        mLayoutParams.setTitle("Peek: Prompt")

        mBarView = mRootView.findViewById(R.id.prompt)
        mBarAnimator = mBarView.animate()

        mMessageView = mBarView.findViewById(R.id.prompt_message)
        mPromptButtonTextView = mBarView.findViewById(R.id.prompt_button_text_view)

        mPromptButtonTextView.setOnClickListener {
            if (mListener != null) {
                mListener!!.onActionClick()
            }
            hidePrompt(false)
        }

        hidePrompt(true)
    }

    private fun showPrompt(text: CharSequence, buttonText: CharSequence,
                            duration: Int, forceSingleLine: Boolean, listener: OnPromptEventListener?) {
        mMessageView.text = text
        if (forceSingleLine) {
            mMessageView.setSingleLine(true)
            mMessageView.ellipsize = TextUtils.TruncateAt.END
        } else {
            mMessageView.setSingleLine(false)
            mMessageView.ellipsize = null
        }
        mListener = listener

        mPromptButtonTextView.text = buttonText

        mHideHandler.removeCallbacks(mHideRunnable)
        mHideHandler.postDelayed(mHideRunnable, duration.toLong())

        mBarView.visibility = View.VISIBLE
        mBarAnimator.cancel()
        mBarAnimator.y(0f)
                .setDuration(mBarView.resources.getInteger(android.R.integer.config_shortAnimTime).toLong())
                .setInterpolator(DecelerateInterpolator())
                .setListener(null)

        mWindowManager.addView(mRootView, mLayoutParams)
        sIsShowing = true

        mBarView.setOnTouchListener(SwipeDismissTouchListener(mBarView,
                null,
                object : SwipeDismissTouchListener.DismissCallbacks {
                    override fun canDismiss(token: Any?): Boolean {
                        // If the user taps, start the delay over for a long length period
                        mHideHandler.removeCallbacks(mHideRunnable)
                        mHideHandler.postDelayed(mHideRunnable, LENGTH_LONG.toLong())
                        return true
                    }

                    override fun onDismiss(view: View, token: Any?) {
                        hidePrompt(true)
                    }

                    override fun onReturn() {
                        mHideHandler.removeCallbacks(mHideRunnable)
                        mHideHandler.postDelayed(mHideRunnable, 1500)
                    }
                }))
    }

    private fun hidePrompt(immediate: Boolean) {
        mHideHandler.removeCallbacks(mHideRunnable)
        mBarAnimator.cancel()
        if (immediate) {
            mBarView.visibility = View.GONE
            mBarView.y = mSnackbarHeight.toFloat()
            if (sIsShowing) {
                mWindowManager.removeViewImmediate(mRootView)
                sIsShowing = false
            }
            if (mListener != null) {
                mListener!!.onClose()
                mListener = null
            }
        } else {
            mBarAnimator.y(mSnackbarHeight.toFloat())
                    .setDuration(mBarView.resources.getInteger(android.R.integer.config_shortAnimTime).toLong())
                    .setInterpolator(AccelerateInterpolator())
                    .setListener(object : AnimatorListenerAdapter() {
                        override fun onAnimationEnd(animation: Animator) {
                            mBarView.visibility = View.GONE
                            if (sIsShowing) {
                                mWindowManager.removeViewImmediate(mRootView)
                                sIsShowing = false
                            }
                            if (mListener != null) {
                                mListener!!.onClose()
                                mListener = null
                            }
                        }
                    })
        }
    }

    private val mHideRunnable = Runnable {
        hidePrompt(false)
    }

    companion object {
        const val LENGTH_SHORT = 3000
        const val LENGTH_LONG = 6000

        private var sPrompt: Prompt? = null
        private var sIsShowing: Boolean = false

        @JvmStatic
        fun initModule(context: Context) {
            Util.Assert(sPrompt == null, "non-null instance")
            sPrompt = Prompt(context)
        }

        @JvmStatic
        fun deinitModule() {
            Util.Assert(sPrompt != null, "null instance")
            sPrompt = null
        }

        @JvmStatic
        fun isShowing(): Boolean {
            return sIsShowing
        }

        @JvmStatic
        fun show(text: CharSequence, buttonText: CharSequence, duration: Int, listener: OnPromptEventListener?) {
            show(text, buttonText, duration, false, listener)
        }

        @JvmStatic
        fun show(text: CharSequence, buttonText: CharSequence, duration: Int, forceSingleLine: Boolean, listener: OnPromptEventListener?) {
            Util.Assert(sPrompt != null, "null instance")
            if (sPrompt != null && android.provider.Settings.canDrawOverlays(sPrompt!!.mContext)) {
                sPrompt!!.hidePrompt(true)
                CrashTracking.log("Prompt.show() text:$text")
                sPrompt!!.showPrompt(text, buttonText, duration, forceSingleLine, listener)
            }
        }
    }
}
