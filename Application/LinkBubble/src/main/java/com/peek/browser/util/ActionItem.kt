/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package com.peek.browser.util

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.DialogInterface
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.content.res.Resources
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.ImageView
import android.widget.ListView
import android.widget.TextView
import com.peek.browser.BuildConfig
import com.peek.browser.Constant
import com.peek.browser.R
import com.peek.browser.Settings
import se.emilsjolander.stickylistheaders.StickyListHeadersAdapter
import se.emilsjolander.stickylistheaders.StickyListHeadersListView

class ActionItem(
        @JvmField val mType: Constant.ActionType,
        resources: Resources,
        private val mLabel: String,
        val mIcon: Drawable,
        @JvmField val mPackageName: String,
        @JvmField val mActivityClassName: String
) {
    private val mCategory: String = resources.getString(if (mType == Constant.ActionType.View) R.string.consume_category_view else R.string.consume_category_share)

    fun getLabel(): String {
        return mLabel
    }

    fun getCategory(): String {
        return mCategory
    }

    interface OnActionItemSelectedListener {
        fun onSelected(actionItem: ActionItem)
    }

    interface OnActionItemDefaultSelectedListener {
        fun onSelected(actionItem: ActionItem, always: Boolean)
    }

    private class ActionItemAdapter(
            private val mContext: Context,
            private val mLayoutResourceId: Int,
            private val mData: Array<ActionItem>
    ) : ArrayAdapter<ActionItem>(mContext, mLayoutResourceId, mData), StickyListHeadersAdapter {

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            var view = convertView

            if (view == null) {
                val inflater = mContext.getSystemService(Context.LAYOUT_INFLATER_SERVICE) as LayoutInflater
                view = inflater.inflate(mLayoutResourceId, parent, false)
            }

            val actionItem = mData[position]

            val label = view.findViewById<TextView>(R.id.label)
            label.text = actionItem.getLabel()

            val icon = view.findViewById<ImageView>(R.id.icon)
            icon.setImageDrawable(actionItem.mIcon)

            view.tag = actionItem

            return view
        }

        override fun getHeaderView(position: Int, convertView: View?, parent: ViewGroup): View {
            val inflater = (mContext as Activity).layoutInflater
            val view = inflater.inflate(R.layout.view_section_header, parent, false)
            val actionItem = mData[position]
            val headerLabel = view.findViewById<TextView>(R.id.section_text)
            headerLabel.text = actionItem.getCategory()
            return view
        }

        override fun getHeaderId(position: Int): Long {
            val actionItem = mData[position]
            return if (actionItem.mType == Constant.ActionType.View) 0 else 1
        }
    }

    companion object {

        private fun getActionItems(context: Context, viewItems: Boolean, sendItems: Boolean, sharePicker: Boolean): ArrayList<ActionItem> {
            val actionItems = ArrayList<ActionItem>()
            val packageManager = context.packageManager
            val resources = context.resources

            if (viewItems) {
                val packages = packageManager.getInstalledApplications(PackageManager.GET_META_DATA)
                for (packageInfo in packages) {

                    // Filter out system apps.
                    val eachIntent = packageManager.getLaunchIntentForPackage(packageInfo.packageName) ?: continue

                    // Ignore this app itself from this list
                    if (Util.isValidBrowserPackageName(packageInfo.packageName)) {
                        actionItems.add(ActionItem(Constant.ActionType.View,
                                resources,
                                packageInfo.loadLabel(packageManager).toString(),
                                packageInfo.loadIcon(packageManager),
                                packageInfo.packageName,
                                packageInfo.name))
                    }
                }
            }

            if (sendItems) {
                // Get list of handler apps that can send
                val intent = Intent(Intent.ACTION_SEND)
                intent.type = "text/plain"
                val resolveInfos = packageManager.queryIntentActivities(intent, 0)
                for (resolveInfo in resolveInfos) {
                    if (resolveInfo.activityInfo.packageName != BuildConfig.APPLICATION_ID) {
                        actionItems.add(ActionItem(Constant.ActionType.Share,
                                resources,
                                resolveInfo.loadLabel(packageManager).toString(),
                                resolveInfo.loadIcon(packageManager),
                                resolveInfo.activityInfo.packageName,
                                resolveInfo.activityInfo.name))
                    }
                }
            }

            if (sharePicker) {
                val intent = Intent(Intent.ACTION_SEND)
                intent.type = "text/plain"
                actionItems.add(ActionItem(Constant.ActionType.Share,
                        resources,
                        context.getString(R.string.share_picker_label),
                        context.resources.getDrawable(R.drawable.ic_share_grey600_24dp),
                        BuildConfig.APPLICATION_ID,
                        Constant.SHARE_PICKER_NAME))
            }

            actionItems.sortWith(Comparator { lhs, rhs ->
                val categoryComparison = lhs.getCategory().compareTo(rhs.getCategory())
                if (categoryComparison == 0) {
                    lhs.getLabel().compareTo(rhs.getLabel())
                } else {
                    categoryComparison
                }
            })

            return actionItems
        }

        @JvmStatic
        fun getDefaultBrowserAlert(context: Context, onActionItemSelectedListener: OnActionItemSelectedListener?): AlertDialog {
            val actionItems = getActionItems(context, true, false, false)

            val listView = ListView(context)

            val alertDialog = AlertDialog.Builder(context).create()
            alertDialog.setIcon(Util.getAlertIcon(context))
            alertDialog.setTitle(R.string.preference_default_browser)
            alertDialog.setView(listView)

            val adapter = ActionItemAdapter(context, R.layout.action_picker_item, actionItems.toTypedArray())
            listView.adapter = adapter
            listView.onItemClickListener = AdapterView.OnItemClickListener { _, view, _, _ ->
                val tag = view.tag
                if (tag is ActionItem) {
                    onActionItemSelectedListener?.onSelected(tag)
                    alertDialog.dismiss()
                }
            }

            return alertDialog
        }

        @JvmStatic
        fun getActionItemPickerAlert(context: Context, resolveInfos: List<ResolveInfo?>,
                                      titleString: Int, onActionItemDefaultSelectedListener: OnActionItemDefaultSelectedListener?): AlertDialog {
            val actionItems = ArrayList<ActionItem>()
            val resources = context.resources
            val packageManager = context.packageManager

            val backgroundColorResourceId = if (Settings.get().darkThemeEnabled) R.color.color_list_background_dark else R.color.color_list_background_light
            val selectedBackgroundColorResourceId = if (Settings.get().darkThemeEnabled) R.color.color_list_selected_background_dark
            else R.color.color_list_selected_background_light


            for (resolveInfo in resolveInfos) {
                if (null == resolveInfo) {
                    continue
                }
                actionItems.add(ActionItem(Constant.ActionType.View,
                        resources,
                        resolveInfo.loadLabel(packageManager).toString(),
                        resolveInfo.loadIcon(packageManager),
                        resolveInfo.activityInfo.packageName,
                        resolveInfo.activityInfo.name))
            }

            actionItems.sortWith(Comparator { lhs, rhs ->
                val categoryComparison = lhs.getCategory().compareTo(rhs.getCategory())
                if (categoryComparison == 0) {
                    lhs.getLabel().compareTo(rhs.getLabel())
                } else {
                    categoryComparison
                }
            })

            class ActionItemListView(context: Context) : ListView(context) {

                var mDefaultSet = false
                var mLastItemClickTime: Long = -1

                override fun draw(canvas: Canvas) {
                    if (!mDefaultSet) {
                        val tag = tag
                        if (tag is Int) {
                            val selectedIndex = tag
                            if (childCount > selectedIndex) {
                                val child = getChildAt(selectedIndex)
                                if (child != null) {
                                    child.setBackgroundResource(selectedBackgroundColorResourceId)
                                    mDefaultSet = true
                                }
                            }
                        }
                    }
                    super.draw(canvas)
                }
            }

            val listView = ActionItemListView(context)

            for (i in actionItems.indices) {
                val actionItem = actionItems[i]
                if (actionItem.mPackageName == context.packageName) {
                    continue
                }
                // Set the first item that isn't this app itself as the current selection
                listView.tag = i
                break
            }

            val alertDialog = AlertDialog.Builder(context).create()
            alertDialog.setIcon(Util.getAlertIcon(context))
            alertDialog.setTitle(titleString)
            alertDialog.setView(listView)

            val adapter = ActionItemAdapter(context, R.layout.action_picker_item, actionItems.toTypedArray())
            listView.adapter = adapter
            listView.onItemClickListener = AdapterView.OnItemClickListener { _, view, position, _ ->
                val currentTime = System.currentTimeMillis()
                val clickDelta = currentTime - listView.mLastItemClickTime
                // Check for a double-tap to emulate the behavior of the AOSP default app picker
                var handled = false
                if (clickDelta < 350) {
                    val selected = listView.tag as Int
                    if (selected == position) {
                        val actionItem = actionItems[position]
                        onActionItemDefaultSelectedListener?.onSelected(actionItem, false)
                        alertDialog.dismiss()
                        handled = true
                    }
                }

                if (!handled) {
                    listView.mLastItemClickTime = currentTime

                    val viewChildCount = listView.childCount
                    for (i in 0 until viewChildCount) {
                        val child = listView.getChildAt(i)
                        child.setBackgroundResource(backgroundColorResourceId)
                    }
                    view.setBackgroundResource(selectedBackgroundColorResourceId)
                    listView.tag = position
                }
            }

            alertDialog.setButton(AlertDialog.BUTTON_POSITIVE, resources.getString(R.string.activity_resolver_use_once), DialogInterface.OnClickListener { _, _ ->
                try {
                    val selectedItem = listView.tag as Int
                    val actionItem = actionItems[selectedItem]
                    onActionItemDefaultSelectedListener?.onSelected(actionItem, false)
                } catch (npe: NullPointerException) {
                    // XXX: ResolveInfos returning null in M preview releases.
                    // See if we can remove this try/catch when M in final, but for now handle the crash.
                    // Implemented in: 89b785a911f734e6ce6b0ecd1b7cb0ff75e88c25
                    CrashTracking.logHandledException(npe)
                }
            })

            alertDialog.setButton(AlertDialog.BUTTON_NEGATIVE, resources.getString(R.string.activity_resolver_use_always), DialogInterface.OnClickListener { _, _ ->
                try {
                    val selectedItem = listView.tag as Int
                    val actionItem = actionItems[selectedItem]
                    onActionItemDefaultSelectedListener?.onSelected(actionItem, true)
                } catch (npe: NullPointerException) {
                    // XXX: ResolveInfos returning null in M preview releases.
                    // See if we can remove this try/catch when M in final, but for now handle the crash.
                    // Implemented in: 89b785a911f734e6ce6b0ecd1b7cb0ff75e88c25
                    CrashTracking.logHandledException(npe)
                }
            })

            return alertDialog
        }

        @JvmStatic
        fun getConfigureBubbleAlert(context: Context, onActionItemSelectedListener: OnActionItemSelectedListener?): AlertDialog {

            val actionItems = getActionItems(context, true, true, true)

            val listView = StickyListHeadersListView(context)

            val alertDialog = AlertDialog.Builder(context).create()
            alertDialog.setIcon(Util.getAlertIcon(context))
            alertDialog.setTitle(R.string.preference_configure_bubble_title)
            alertDialog.setView(listView)

            val adapter = ActionItemAdapter(context, R.layout.action_picker_item, actionItems.toTypedArray())
            listView.adapter = adapter
            listView.setOnItemClickListener(AdapterView.OnItemClickListener { _, view, _, _ ->
                val tag = view.tag
                if (tag is ActionItem) {
                    onActionItemSelectedListener?.onSelected(tag)
                    alertDialog.dismiss()
                }
            })

            alertDialog.setButton(AlertDialog.BUTTON_POSITIVE, context.resources.getString(R.string.action_use_default), DialogInterface.OnClickListener { _, _ ->
            })

            alertDialog.setOnCancelListener(DialogInterface.OnCancelListener {
            })

            return alertDialog
        }

        @JvmStatic
        fun getShareAlert(context: Context, showSharePicker: Boolean, onActionItemSelectedListener: OnActionItemSelectedListener?): AlertDialog {

            // Build the list of send applications
            val builder = AlertDialog.Builder(context)
            builder.setIcon(Util.getAlertIcon(context))
            builder.setTitle(R.string.share_via)
            builder.setIcon(R.mipmap.ic_launcher)

            val alertDialog = builder.create()
            alertDialog.window?.setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY)

            val actionItems = getActionItems(context, false, true, showSharePicker)
            val adapter = ActionItemAdapter(context, R.layout.action_picker_item, actionItems.toTypedArray())

            val listView = ListView(context)
            listView.adapter = adapter
            listView.onItemClickListener = AdapterView.OnItemClickListener { _, view, _, _ ->
                val tag = view.tag
                if (tag is ActionItem) {
                    onActionItemSelectedListener?.onSelected(tag)
                    alertDialog.dismiss()
                }
            }
            alertDialog.setView(listView)
            return alertDialog
        }
    }
}
