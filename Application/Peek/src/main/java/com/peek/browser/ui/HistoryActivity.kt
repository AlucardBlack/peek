/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package com.peek.browser.ui

import android.app.AlertDialog
import android.content.Context
import android.content.DialogInterface
import android.content.Intent
import android.content.res.Resources
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import android.view.Gravity
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.AbsListView
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.BaseAdapter
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import com.peek.browser.Config
import com.peek.browser.Constant
import com.peek.browser.MainApplication
import com.peek.browser.MainController
import com.peek.browser.R
import com.peek.browser.Settings
import com.peek.browser.db.DatabaseHelper
import com.peek.browser.db.HistoryRecord
import com.peek.browser.util.ActionItem
import com.peek.browser.util.Analytics
import com.peek.browser.util.EventBus
import com.peek.browser.util.Util
import org.mozilla.gecko.favicons.Favicons
import org.mozilla.gecko.favicons.LoadFaviconTask
import org.mozilla.gecko.favicons.OnFaviconLoadedListener
import org.mozilla.gecko.widget.FaviconView
import java.util.ArrayList
import java.util.Collections
import java.util.Date

class HistoryActivity : AppCompatActivity(), AdapterView.OnItemClickListener, AdapterView.OnItemLongClickListener {

    private lateinit var mMessageView: TextView
    private lateinit var mListView: ListView
    private var mHistoryAdapter: HistoryAdapter? = null
    private var mHistoryRecords: MutableList<HistoryRecord>? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        sInstanceCount++
        if (sInstanceCount == 1) {
            sFavicons = Favicons(FAVICON_CACHE_SIZE)
        }

        setContentView(R.layout.activity_history)

        val toolbar = findViewById<Toolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        Util.padForStatusBarInset(toolbar)
        toolbar.setNavigationIcon(R.drawable.ic_arrow_back_white_24dp)
        toolbar.setNavigationOnClickListener {
            finish()
        }

        mMessageView = findViewById(R.id.message_view)
        mListView = findViewById(R.id.listview)
    }

    override fun onDestroy() {
        sInstanceCount--
        if (sInstanceCount == 0) {
            sFavicons = null
        }

        super.onDestroy()
    }

    override fun onStart() {
        super.onStart()

        mHistoryRecords = MainApplication.sDatabaseHelper!!.getAllHistoryRecords().toMutableList()
        setupListView()

        EventBus.subscribe(this, HistoryRecord.ChangedEvent::class.java, ::onHistoryRecordChangedEvent)

        MainApplication.checkRestoreCurrentTabs(this)
    }

    override fun onStop() {
        super.onStop()

        EventBus.unsubscribeAll(this)
    }

    private fun setupListView() {
        if (mHistoryRecords == null || mHistoryRecords!!.size == 0) {
            showNoHistoryView()
            return
        }

        mMessageView.visibility = View.GONE
        mHistoryAdapter = HistoryAdapter(this)

        mListView.adapter = mHistoryAdapter
        mListView.onItemClickListener = this
        mListView.onItemLongClickListener = this

        val swipeDismissTouchListener =
                SwipeDismissListViewTouchListener(
                        mListView,
                        object : SwipeDismissListViewTouchListener.DismissCallbacks {
                            override fun canDismiss(position: Int): Boolean {
                                if (mHistoryRecords != null && position < mHistoryRecords!!.size) {
                                    return true
                                }
                                return false
                            }

                            override fun onDismiss(listView: ListView, reverseSortedPositions: IntArray) {
                                val databaseHelper = MainApplication.sDatabaseHelper

                                for (position in reverseSortedPositions) {
                                    val item = listView.getItemAtPosition(position)
                                    if (item is HistoryRecord) {
                                        if (databaseHelper!!.deleteHistoryRecord(item)) {
                                            mHistoryRecords!!.remove(item)
                                        }
                                    }
                                }

                                if (mHistoryRecords!!.size == 0) {
                                    showNoHistoryView()
                                } else {
                                    if (mHistoryAdapter != null) {
                                        mHistoryAdapter!!.notifyDataSetChanged()
                                    }
                                }
                            }
                        })
        mListView.onItemClickListener = this
        mListView.setOnScrollListener(swipeDismissTouchListener.makeScrollListener())
        mListView.setOnTouchListener { view, motionEvent ->
            swipeDismissTouchListener.onTouch(view, motionEvent)
        }

        mListView.setItemsCanFocus(true)
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        super.onCreateOptionsMenu(menu)

        val inflater = menuInflater
        inflater.inflate(R.menu.history_activity, menu)

        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            android.R.id.home -> {
                finish()
                return true
            }

            R.id.action_clear_history -> {
                if (mHistoryAdapter == null) {
                    Toast.makeText(this, R.string.history_already_empty, Toast.LENGTH_SHORT).show()
                    return true
                }

                val alertDialog = AlertDialog.Builder(this).create()
                alertDialog.setTitle(R.string.erase_all_history_title)
                alertDialog.setMessage(getString(R.string.erase_all_history_message))
                alertDialog.setCancelable(true)
                alertDialog.setButton(AlertDialog.BUTTON_POSITIVE, getString(R.string.yes), DialogInterface.OnClickListener { dialog, which ->
                    MainApplication.sDatabaseHelper!!.deleteAllHistoryRecords()
                    mHistoryRecords = null
                    if (mHistoryAdapter != null) {
                        mHistoryAdapter!!.notifyDataSetChanged()
                    }
                })
                alertDialog.setButton(AlertDialog.BUTTON_NEGATIVE, getString(R.string.cancel), DialogInterface.OnClickListener { dialog, which ->
                    dialog.dismiss()
                })
                Util.showThemedDialog(alertDialog)
                return true
            }
        }

        return super.onOptionsItemSelected(item)
    }

    override fun onItemClick(parent: AdapterView<*>, view: View, position: Int, id: Long) {
        if (view.tag is HistoryItem) {
            val historyItem = view.tag as HistoryItem
            MainApplication.openLink(this, historyItem.mHistoryRecord!!.getUrl()!!, Analytics.OPENED_URL_FROM_HISTORY)
        }
    }

    override fun onItemLongClick(parent: AdapterView<*>, view: View, position: Int, id: Long): Boolean {
        if (view.tag is HistoryItem) {
            val historyItem = view.tag as HistoryItem
            val resources = resources

            val longClickSelections = ArrayList<String>()

            val shareLabel = resources.getString(R.string.action_share)
            longClickSelections.add(shareLabel)

            val defaultBrowserLabel = Settings.get().getDefaultBrowserLabel()

            val leftConsumeBubbleLabel = Settings.get().getConsumeBubbleLabel(Constant.BubbleAction.ConsumeLeft)
            if (leftConsumeBubbleLabel != null) {
                if (defaultBrowserLabel == null || defaultBrowserLabel == leftConsumeBubbleLabel == false) {
                    longClickSelections.add(leftConsumeBubbleLabel)
                }
            }

            val rightConsumeBubbleLabel = Settings.get().getConsumeBubbleLabel(Constant.BubbleAction.ConsumeRight)
            if (rightConsumeBubbleLabel != null) {
                if (defaultBrowserLabel == null || defaultBrowserLabel == rightConsumeBubbleLabel == false) {
                    longClickSelections.add(rightConsumeBubbleLabel)
                }
            }

            val copyLinkLabel = resources.getString(R.string.action_copy_to_clipboard)
            longClickSelections.add(copyLinkLabel)

            Collections.sort(longClickSelections)

            val openInNewBubbleLabel = resources.getString(R.string.action_open_in_new_bubble)
            longClickSelections.add(0, openInNewBubbleLabel)

            val openInBrowserLabel = if (defaultBrowserLabel != null)
                String.format(resources.getString(R.string.action_open_in_browser), defaultBrowserLabel)
            else null
            if (openInBrowserLabel != null) {
                longClickSelections.add(1, openInBrowserLabel)
            }

            val longPressAlertDialog = AlertDialog.Builder(this).create()

            val listView = ListView(this)
            listView.adapter = ArrayAdapter<String>(this, android.R.layout.simple_list_item_1,
                    longClickSelections.toTypedArray())
            listView.onItemClickListener = AdapterView.OnItemClickListener { parentView, itemView, itemPosition, itemId ->
                val string = longClickSelections[itemPosition]
                val urlAsString = historyItem.mHistoryRecord!!.getUrl()!!
                if (string == openInNewBubbleLabel) {
                    if (MainController.get() != null) {
                        MainController.get()!!.openUrl(urlAsString, System.currentTimeMillis(), false, Analytics.OPENED_URL_FROM_HISTORY)
                    } else {
                        MainApplication.openLink(applicationContext, urlAsString, Analytics.OPENED_URL_FROM_HISTORY)
                    }
                } else if (openInBrowserLabel != null && string == openInBrowserLabel) {
                    val intent = Intent(Intent.ACTION_VIEW)
                    intent.data = Uri.parse(urlAsString)
                    intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                    MainApplication.openInBrowser(this@HistoryActivity, intent, true)
                } else if (string == shareLabel) {
                    val alertDialog = ActionItem.getShareAlert(this@HistoryActivity, false, object : ActionItem.OnActionItemSelectedListener {
                        override fun onSelected(actionItem: ActionItem) {
                            val intent = Intent(Intent.ACTION_SEND)
                            intent.type = "text/plain"
                            intent.setClassName(actionItem.mPackageName, actionItem.mActivityClassName)
                            intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                            intent.putExtra(Intent.EXTRA_TEXT, historyItem.mHistoryRecord!!.getUrl())
                            startActivity(intent)
                        }
                    })
                    Util.showThemedDialog(alertDialog)
                } else if (leftConsumeBubbleLabel != null && string == leftConsumeBubbleLabel) {
                    MainApplication.handleBubbleAction(this@HistoryActivity, Constant.BubbleAction.ConsumeLeft, urlAsString, -1L)
                } else if (rightConsumeBubbleLabel != null && string == rightConsumeBubbleLabel) {
                    MainApplication.handleBubbleAction(this@HistoryActivity, Constant.BubbleAction.ConsumeRight, urlAsString, -1L)
                } else if (string == copyLinkLabel) {
                    MainApplication.copyLinkToClipboard(this@HistoryActivity, urlAsString, R.string.link_copied_to_clipboard)
                }

                longPressAlertDialog.dismiss()
            }

            longPressAlertDialog.setView(listView)
            Util.showThemedDialog(longPressAlertDialog)

            return true
        }

        return false
    }

    fun showNoHistoryView() {
        mMessageView.visibility = View.VISIBLE
        mMessageView.setText(R.string.empty)
    }

    private inner class HistoryAdapter(context: Context) : BaseAdapter() {

        val mInflater: LayoutInflater = context.getSystemService(Context.LAYOUT_INFLATER_SERVICE) as LayoutInflater

        override fun getCount(): Int {
            return if (mHistoryRecords != null) mHistoryRecords!!.size + 1 else 0
        }

        override fun getItem(position: Int): Any {
            return if (mHistoryRecords != null) mHistoryRecords!![position] else position
        }

        override fun getItemId(position: Int): Long {
            return position.toLong()
        }

        override fun getViewTypeCount(): Int {
            return 2
        }

        override fun getItemViewType(position: Int): Int {
            return if (position == mHistoryRecords!!.size) 1 else 0
        }

        override fun getView(position: Int, convertViewIn: View?, parent: ViewGroup): View {
            var convertView = convertViewIn

            if (position == mHistoryRecords!!.size) {
                val noMoreView: TextView
                if (convertView == null || convertView !is TextView) {
                    noMoreView = TextView(this@HistoryActivity)
                    noMoreView.gravity = Gravity.CENTER
                    noMoreView.text = "○"
                    noMoreView.layoutParams = AbsListView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Config.dpToPx(40f))
                } else {
                    noMoreView = convertView
                }
                return noMoreView
            }

            val historyItem: HistoryItem
            val historyRecord = mHistoryRecords!![position]

            if (convertView == null) {
                convertView = mInflater.inflate(R.layout.history_item, parent, false)
                historyItem = HistoryItem()
                historyItem.mTitleTextView = convertView.findViewById(R.id.page_title)
                historyItem.mUrlTextView = convertView.findViewById(R.id.page_url)
                historyItem.mTimeTextView = convertView.findViewById(R.id.page_date)
                historyItem.mFaviconImageView = convertView.findViewById(R.id.favicon)
                historyItem.mFaviconImageView!!.mFavicons = sFavicons
            } else {
                historyItem = convertView.tag as HistoryItem
            }

            historyItem.mHistoryRecord = historyRecord
            historyItem.mDate.time = historyRecord.getTime()
            historyItem.mFaviconSet = false

            historyItem.mTitleTextView!!.text = historyRecord.getTitle()
            historyItem.mUrlTextView!!.text = historyRecord.getHost()
            historyItem.mTimeTextView!!.text = Util.getPrettyDate(historyItem.mDate)

            val flags = if (Settings.get().isIncognitoMode) 0 else LoadFaviconTask.FLAG_PERSIST
            val host = historyRecord.getHost()
            val faviconUrl = "http://$host/favicon.ico"

            historyItem.mFaviconUrl = faviconUrl
            historyItem.mFaviconImageView!!.clearImage()
            sFavicons!!.getFaviconForSize(host!!, faviconUrl, Int.MAX_VALUE, flags, historyItem.mOnFaviconLoadedListener)
            if (historyItem.mFaviconSet == false) {
                historyItem.mFaviconImageView!!.showDefaultFavicon()
            }

            convertView.tag = historyItem

            return convertView
        }
    }

    private class HistoryItem {
        var mTitleTextView: TextView? = null
        var mUrlTextView: TextView? = null
        var mTimeTextView: TextView? = null
        var mFaviconImageView: FaviconView? = null
        var mHistoryRecord: HistoryRecord? = null
        val mDate = Date()
        var mFaviconUrl: String? = null
        var mFaviconSet: Boolean = false
        val mOnFaviconLoadedListener: OnFaviconLoadedListener = object : OnFaviconLoadedListener {
            override fun onFaviconLoaded(url: String?, faviconURL: String?, favicon: Bitmap?) {
                // Ensure the favicon passed in matches the one we want. This can be false as HistoryAdapter recycles
                // Views and favicons are loaded in different orders to that which they are requested.
                if (mFaviconUrl != faviconURL) {
                    return
                }
                if (favicon != null) {
                    mFaviconSet = true
                    mFaviconImageView!!.updateImage(favicon, faviconURL, true)
                }
            }
        }
    }

    fun onHistoryRecordChangedEvent(event: HistoryRecord.ChangedEvent) {
        var setupList = false
        if (mHistoryRecords == null) {
            mHistoryRecords = ArrayList()
            setupList = true
        }

        val historyRecord = event.mHistoryRecord
        // find out if the item exists on the list already. This will be true if a HistoryRecord for a URL was updated
        for (existing in mHistoryRecords!!) {
            if (existing.getId() == historyRecord!!.getId()) {
                mHistoryRecords!!.remove(existing)
                break
            }
        }

        // Add it at the top of the list. This assumes the item had it's date updated to 'now',
        // which is the current behaviour.
        mHistoryRecords!!.add(0, historyRecord!!)

        if (setupList) {
            setupListView()
        } else {
            mMessageView.visibility = View.GONE
            if (mHistoryAdapter != null) {
                mHistoryAdapter!!.notifyDataSetChanged()
            }
        }
    }

    companion object {
        private var sInstanceCount = 0
        private var sFavicons: Favicons? = null
        private const val FAVICON_CACHE_SIZE = 4 * 1024 * 1024
    }
}
