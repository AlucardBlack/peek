/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package com.peek.browser.ui

import android.content.Context
import android.content.DialogInterface
import android.os.Bundle
import android.os.Handler
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.snackbar.Snackbar
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.appcompat.widget.Toolbar
import android.text.TextUtils
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import com.peek.browser.R
import com.peek.browser.Settings
import com.peek.browser.util.Util
import java.net.MalformedURLException
import java.net.URL
import java.util.ArrayList
import java.util.Collections

class SettingsDomainsActivity : AppCompatActivity() {

    lateinit var adapter: Adapter
    lateinit var recyclerView: RecyclerView
    lateinit var addButton: FloatingActionButton
    lateinit var rootView: View
    lateinit var linearLayoutManager: LinearLayoutManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContentView(R.layout.activity_settings_domains)

        recyclerView = findViewById(R.id.recycler_view)
        addButton = findViewById(R.id.fab)
        rootView = findViewById(R.id.root_view)

        adapter = Adapter(this)

        recyclerView.adapter = adapter

        linearLayoutManager = LinearLayoutManager(this)
        recyclerView.layoutManager = linearLayoutManager

        val toolbar = findViewById<Toolbar>(R.id.toolbar)
        toolbar.setTitle(R.string.preference_domains_title)
        setSupportActionBar(toolbar)
        Util.padForStatusBarInset(toolbar)
        toolbar.setNavigationIcon(R.drawable.ic_arrow_back_white_24dp)
        toolbar.setNavigationOnClickListener {
            finish()
        }

        addButton.setOnClickListener {
            showAddRedirectDialog()
        }
    }

    fun showAddRedirectDialog() {
        val layout = LayoutInflater.from(this).inflate(R.layout.view_add_domain, null)
        val editText = layout.findViewById<EditText>(R.id.edit_text)
        AlertDialog.Builder(this)
                .setTitle(R.string.preference_add_domain_title)
                .setView(layout)
                .setPositiveButton(R.string.action_add,
                        DialogInterface.OnClickListener { dialog, which ->
                            var host = editText.text.toString()
                            var added = false
                            if (!TextUtils.isEmpty(host)) {
                                host = host.replace("\"", "")
                                val protocolIndex = host.indexOf("://")
                                if (protocolIndex > -1) {
                                    host = host.substring(protocolIndex + "://".length)
                                }
                                if (host.contains(".") && !host.contains(" ")) {
                                    val slashIndex = host.indexOf("/")
                                    if (slashIndex > -1) {
                                        host = host.substring(0, slashIndex)
                                    }
                                    try {
                                        val url = URL("http", host, "/")
                                        adapter.addDomain(url.host)
                                        Settings.get().addFallbackRedirectHost(url.host)
                                        added = true
                                    } catch (e: MalformedURLException) {
                                        e.printStackTrace()
                                    }
                                }
                            }
                            if (!added) {
                                showSnackbar(String.format(getString(R.string.add_domain_error),
                                        editText.text.toString()))
                            }
                        })
                .create()
                .show()

        mHandler.postDelayed({
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.showSoftInput(editText, InputMethodManager.SHOW_IMPLICIT)
        }, 300)
    }

    private val mHandler = Handler()

    private var currentSnackbar: Snackbar? = null
    fun showSnackbar(message: String) {
        showSnackbar(message, null, null)
    }

    fun showSnackbar(message: String, action: String?, onActionClickListener: View.OnClickListener?) {
        if (currentSnackbar != null) {
            currentSnackbar!!.dismiss()
            currentSnackbar = null
        }

        currentSnackbar =
                Snackbar.make(rootView,
                        message,
                        if (action != null) Snackbar.LENGTH_LONG else Snackbar.LENGTH_SHORT)
                        .setAction(action, onActionClickListener)
        currentSnackbar!!.show()
    }

    abstract class BaseItem(val context: Context, val title: String) {
        var width: Int
        var height: Int

        init {
            width = ViewGroup.LayoutParams.MATCH_PARENT
            height = context.resources.getDimensionPixelSize(R.dimen.settings_item_height)
        }

        open class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {

            fun setTag(tag: Any?) {
                itemView.tag = tag
            }

            fun bind(baseItem: BaseItem) {
                setTag(baseItem)

                var lp = itemView.layoutParams
                if (lp == null) {
                    lp = ViewGroup.LayoutParams(baseItem.width, baseItem.height)
                } else {
                    lp.width = baseItem.width
                    lp.height = baseItem.height
                }
                if (baseItem.height == ViewGroup.LayoutParams.WRAP_CONTENT) {
                    // wrap content based on text container rather than root layout to ensure
                    // selectableItemBackground extends correctly to visible bounds.
                    val textContainer = itemView.findViewById<View>(R.id.settings_text_container)
                    if (textContainer != null) {
                        val vPadding = baseItem.context.resources.getDimensionPixelSize(R.dimen.default_margin_half)
                        textContainer.setPadding(itemView.paddingLeft, vPadding, itemView.paddingRight, vPadding)
                    }
                }
                itemView.layoutParams = lp
            }
        }
    }

    class HeadingItem(context: Context, title: String) : BaseItem(context, title) {
        init {
            height = context.resources.getDimensionPixelSize(R.dimen.settings_group_title_item_height)
        }

        class ViewHolder(itemView: View) : BaseItem.ViewHolder(itemView) {

            var titleView: TextView = itemView.findViewById(R.id.settings_title)

            fun bind(headingItem: HeadingItem) {
                super.bind(headingItem)
                titleView.text = headingItem.title
            }
        }
    }

    class DomainItem(context: Context, title: String) : BaseItem(context, title) {
        init {
            height = context.resources.getDimensionPixelSize(R.dimen.settings_domain_item_height)
        }

        class ViewHolder(itemView: View) : BaseItem.ViewHolder(itemView) {

            var titleView: TextView
            var divider: View
            var removeIcon: ImageView

            init {
                titleView = itemView.findViewById(R.id.settings_title)
                divider = itemView.findViewById(R.id.settings_divider)
                removeIcon = itemView.findViewById(R.id.remove_icon)

                itemView.findViewById<View>(R.id.settings_summary).visibility = View.GONE
                itemView.findViewById<View>(R.id.app_icon).visibility = View.GONE
            }

            fun bind(domainItem: DomainItem, showDivider: Boolean, onRemoveClickListener: View.OnClickListener) {
                super.bind(domainItem)
                titleView.text = domainItem.title
                divider.visibility = if (showDivider) View.VISIBLE else View.INVISIBLE
                removeIcon.setOnClickListener(onRemoveClickListener)
            }
        }
    }

    inner class Adapter(val context: Context) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

        val items = ArrayList<BaseItem>()

        init {
            items.add(HeadingItem(context, getString(R.string.preference_redirects_title)))

            val redirectHosts = Settings.get().getFallbackRedirectHosts()
            if (redirectHosts.size > 0) {
                val strings = ArrayList(redirectHosts)
                Collections.sort(strings)
                for (string in strings) {
                    items.add(DomainItem(context, string))
                }
            }
        }

        fun addDomain(host: String) {
            val item = DomainItem(context, host)
            addItem(item)
        }

        fun addItem(item: BaseItem) {
            items.add(item)
            notifyItemInserted(items.indexOf(item))
        }

        fun removeItem(baseItem: BaseItem) {
            val index = items.indexOf(baseItem)
            items.removeAt(index)
            notifyItemRemoved(index)
        }

        override fun getItemViewType(position: Int): Int {
            val baseItem = items[position]
            if (baseItem is HeadingItem) {
                return VIEW_TYPE_HEADING
            }
            return VIEW_TYPE_DOMAIN
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            when (viewType) {
                VIEW_TYPE_HEADING ->
                    return HeadingItem.ViewHolder(LayoutInflater.from(context)
                            .inflate(R.layout.view_settings_group_title, null))

                VIEW_TYPE_DOMAIN ->
                    return DomainItem.ViewHolder(LayoutInflater.from(context)
                            .inflate(R.layout.view_settings_item, null))
            }
            throw IllegalArgumentException("Unknown viewType: $viewType")
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            if (holder is DomainItem.ViewHolder) {
                val item = items[position] as DomainItem
                holder.bind(item,
                        !(items[position - 1] is HeadingItem),
                        View.OnClickListener {
                            removeItem(item)
                            Settings.get().removeFallbackRedirectHost(item.title)
                            showSnackbar("Removed " + item.title + ".",
                                    getString(R.string.action_undo),
                                    View.OnClickListener {
                                        addItem(item)
                                        Settings.get().addFallbackRedirectHost(item.title)
                                    })
                        })

            } else if (holder is HeadingItem.ViewHolder) {
                holder.bind(items[position] as HeadingItem)
                holder.itemView.setOnClickListener(null)
            }
        }

        override fun getItemCount(): Int {
            return items.size
        }
    }

    companion object {
        const val VIEW_TYPE_HEADING = 0
        const val VIEW_TYPE_DOMAIN = 1
    }
}
