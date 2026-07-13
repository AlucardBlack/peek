/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package com.peek.browser.util

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.CheckedTextView
import android.widget.ImageView
import android.widget.ListView
import com.peek.browser.R
import java.util.Collections
import java.util.Comparator
import java.util.Locale

class AppPickerList {

    class AppInfo(val mActivityName: String, @JvmField val mPackageName: String, val mDisplayName: String) {
        val mSortName: String = mDisplayName.lowercase(Locale.getDefault())
        val mIntent: Intent = Intent(Intent.ACTION_MAIN).apply {
            setPackage(mPackageName)
            component = ComponentName(mPackageName, mActivityName)
        }
        var mChecked: Boolean = false
    }

    class AppInfoComparator : Comparator<AppInfo> {
        override fun compare(lhs: AppInfo, rhs: AppInfo): Int {
            return lhs.mSortName.compareTo(rhs.mSortName)
        }
    }

    class AppPickerListInfo {
        var mAllApps: ArrayList<AppInfo> = ArrayList()
        var mSingleCheckedTextView: CheckedTextView? = null
    }

    enum class SelectionType {
        SingleSelection,
        MultipleSelection,
    }

    interface Initializer {
        fun setChecked(packageName: String, activityName: String): Boolean
        fun addToList(packageName: String): Boolean
    }

    companion object {
        @JvmStatic
        fun createView(context: Context, iconCache: IconCache, selectionType: SelectionType, initializer: Initializer): View {

            val pm = context.packageManager
            val itemLayout = if (selectionType == SelectionType.SingleSelection) R.layout.app_picker_list_item_single else R.layout.app_picker_list_item_multiple

            val listView = View.inflate(context, R.layout.app_picker_list, null) as ListView

            val mainIntent = Intent(Intent.ACTION_MAIN, null)
            mainIntent.addCategory(Intent.CATEGORY_LAUNCHER)
            val allResolveInfo = pm.queryIntentActivities(mainIntent, 0)

            val appPickerListInfo = AppPickerListInfo()

            for (info in allResolveInfo) {
                if (info.activityInfo != null && info.activityInfo.packageName != null) {
                    if (initializer.addToList(info.activityInfo.packageName)) {
                        // This is the G+ "Photos" Activity. Ignore it.
                        if (info.activityInfo.name != "com.google.android.apps.plus.phone.ConversationListActivity") {
                            appPickerListInfo.mAllApps.add(AppInfo(info.activityInfo.name, info.activityInfo.packageName, info.loadLabel(pm).toString()))
                        }
                    }
                }
            }
            Collections.sort(appPickerListInfo.mAllApps, AppInfoComparator())

            val listAdapter = object : ArrayAdapter<String>(context, itemLayout) {
                override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                    var view = convertView
                    if (view == null) {
                        val inflater = context.getSystemService(Context.LAYOUT_INFLATER_SERVICE) as LayoutInflater
                        view = inflater.inflate(itemLayout, parent, false)
                    }

                    val appInfo = appPickerListInfo.mAllApps[position]
                    val icon = iconCache.getIcon(appInfo.mIntent)
                    if (icon != null) {
                        (view.findViewById<View>(R.id.image_view) as ImageView).setImageBitmap(icon)
                    }

                    val checkedTextView = view.findViewById<CheckedTextView>(R.id.checked_text_view)
                    checkedTextView.text = appInfo.mDisplayName
                    checkedTextView.isChecked = appInfo.mChecked

                    view.tag = appInfo

                    return view
                }
            }

            for (appInfo in appPickerListInfo.mAllApps) {
                listAdapter.add(appInfo.mDisplayName)
            }

            listView.adapter = listAdapter
            for (app in appPickerListInfo.mAllApps) {
                if (initializer.setChecked(app.mPackageName, app.mActivityName)) {
                    app.mChecked = true
                }
            }

            listView.onItemClickListener = AdapterView.OnItemClickListener { _, view, _, _ ->
                val appInfo = view.tag as AppInfo
                appInfo.mChecked = !appInfo.mChecked
                val checkedTextView = view.findViewById<CheckedTextView>(R.id.checked_text_view)

                if (selectionType == SelectionType.SingleSelection) {
                    for (app in appPickerListInfo.mAllApps) {
                        if (app !== appInfo && app.mChecked) {
                            app.mChecked = false
                        }
                    }
                    appPickerListInfo.mSingleCheckedTextView?.isChecked = false
                }

                appPickerListInfo.mSingleCheckedTextView = checkedTextView
                appPickerListInfo.mSingleCheckedTextView?.isChecked = appInfo.mChecked
            }

            listView.tag = appPickerListInfo
            return listView
        }

        @JvmStatic
        fun getSelected(view: View): ArrayList<AppInfo>? {
            val result = ArrayList<AppInfo>()

            val appPickerListInfo = view.tag as AppPickerListInfo
            for (appInfo in appPickerListInfo.mAllApps) {
                if (appInfo.mChecked) {
                    result.add(appInfo)
                }
            }

            appPickerListInfo.mSingleCheckedTextView = null

            return if (result.size > 0) result else null
        }

        @JvmStatic
        fun getUnselected(view: View): ArrayList<AppInfo>? {
            val result = ArrayList<AppInfo>()

            val appPickerListInfo = view.tag as AppPickerListInfo
            for (appInfo in appPickerListInfo.mAllApps) {
                if (!appInfo.mChecked) {
                    result.add(appInfo)
                }
            }

            appPickerListInfo.mSingleCheckedTextView = null

            return if (result.size > 0) result else null
        }
    }
}
