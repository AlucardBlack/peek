/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package com.peek.browser.ui

import android.app.AlertDialog
import android.content.ComponentName
import android.content.Context
import android.content.DialogInterface
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceManager
import androidx.preference.SwitchPreferenceCompat
import androidx.annotation.DrawableRes
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.res.ResourcesCompat
import androidx.core.graphics.drawable.DrawableCompat
import androidx.appcompat.widget.Toolbar
import android.view.LayoutInflater
import android.view.MenuItem
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewDatabase
import android.widget.ArrayAdapter
import android.widget.ImageView
import android.widget.ListView
import android.widget.RadioButton
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import com.peek.browser.BuildConfig
import com.peek.browser.Config
import com.peek.browser.Constant
import com.peek.browser.MainApplication
import com.peek.browser.MainController
import com.peek.browser.MainService
import com.peek.browser.R
import com.peek.browser.Settings
import com.peek.browser.util.ActionItem
import com.peek.browser.util.EventBus
import com.peek.browser.util.IconCache
import com.peek.browser.util.Util
import java.util.ArrayList

class SettingsActivity : AppCompatActivity() {

    class IncognitoModeChangedEvent(@JvmField val mIncognito: Boolean, @JvmField val mainController: MainController?)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val mainApplication = applicationContext as MainApplication
        if (mainApplication.mIconCache == null) {
            mainApplication.mIconCache = IconCache(mainApplication)
        }

        setContentView(R.layout.activity_settings)
        setTitle(R.string.title_settings)

        val toolbar = findViewById<Toolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        Util.padForStatusBarInset(toolbar)
        toolbar.setNavigationIcon(R.drawable.ic_arrow_back_white_24dp)
        toolbar.setNavigationOnClickListener {
            finish()
        }
    }

    override fun onStart() {
        super.onStart()
        MainApplication.checkRestoreCurrentTabs(this)
    }

    override fun finish() {
        super.finish()
        overridePendingTransition(R.anim.activity_close_enter, R.anim.activity_close_exit)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {

        if (super.onOptionsItemSelected(item)) {
            return true
        }

        when (item.itemId) {
            android.R.id.home -> {
                finish()
                return true
            }
        }

        return false
    }

    class SettingsFragment : SettingsBaseFragment(), SharedPreferences.OnSharedPreferenceChangeListener {

        private lateinit var mWebViewTextZoomPreference: Preference
        private lateinit var mThemePreference: Preference
        private lateinit var mWebViewBatterySavePreference: Preference
        private lateinit var mUserAgentPreference: ListPreference

        private val mHandler = Handler()

        fun getTintedDrawable(@DrawableRes drawable: Int, color: Int): Drawable {
            var d = ResourcesCompat.getDrawable(resources, drawable, null)!!
            d = DrawableCompat.wrap(d)
            DrawableCompat.setTint(d, color)
            return d
        }

        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            val tintColor = ResourcesCompat.getColor(resources, R.color.color_primary, null)

            // Load the preferences from an XML resource
            setPreferencesFromResource(R.xml.preferences, rootKey)

            mWebViewBatterySavePreference = findPreference<Preference>(Settings.PREFERENCE_WEBVIEW_BATTERY_SAVING_MODE)!!
            mWebViewBatterySavePreference.setOnPreferenceClickListener {
                Util.showThemedDialog(getWebViewBatterySaveDialog())
                true
            }
            mWebViewBatterySavePreference.icon = getTintedDrawable(R.drawable.ic_battery_full_white_36dp, tintColor)
            updateWebViewBatterySaveSummary()

            val domainsPref = findPreference<Preference>("preference_domains")!!
            domainsPref.icon = getTintedDrawable(R.drawable.ic_open_in_browser_white_36dp, tintColor)
            domainsPref.setOnPreferenceClickListener {
                startActivity(Intent(requireActivity(), SettingsDomainsActivity::class.java))
                true
            }

            val incognitoButton = findPreference<Preference>(Settings.PREFERENCE_INCOGNITO_MODE)
            if (incognitoButton != null) {
                incognitoButton.setOnPreferenceChangeListener { preference, newValue ->

                    EventBus.post(IncognitoModeChangedEvent(newValue as Boolean, MainController.get()))

                    if (MainController.get() != null && MainController.get()!!.reloadAllTabs(requireActivity())) {
                        Toast.makeText(requireActivity(), R.string.incognito_mode_changed_reloading_current, Toast.LENGTH_SHORT).show()
                    }

                    true
                }
            }
            incognitoButton!!.icon = getTintedDrawable(R.drawable.ic_person_outline_white_36dp, tintColor)

            mThemePreference = findPreference<Preference>("preference_theme")!!
            mThemePreference.icon = getTintedDrawable(R.drawable.ic_color_lens_white_36dp, tintColor)
            mThemePreference.setOnPreferenceClickListener {
                Util.showThemedDialog(getThemeDialog())
                true
            }
            updateThemeSummary()

            val themeToolbarPreference = findPreference<SwitchPreferenceCompat>(Settings.PREFERENCE_THEME_TOOLBAR)!!
            themeToolbarPreference.isChecked = Settings.get().getThemeToolbar()
            themeToolbarPreference.icon = getTintedDrawable(R.drawable.ic_colorize_white_36dp, tintColor)
            themeToolbarPreference.setOnPreferenceChangeListener { preference, newValue ->
                if (MainController.get() != null && MainController.get()!!.reloadAllTabs(requireActivity())) {
                    Toast.makeText(requireActivity(), R.string.theme_toolbar_reloading_current, Toast.LENGTH_SHORT).show()
                }
                true
            }

            val crashButton = findPreference<Preference>("debug_crash")
            if (crashButton != null) {
                crashButton.setOnPreferenceClickListener {
                    throw RuntimeException("CRASH BUTTON PRESSED!")
                }
            }

            val leftConsumeBubblePreference = findPreference<Preference>(Settings.PREFERENCE_LEFT_CONSUME_BUBBLE)!!
            leftConsumeBubblePreference.setOnPreferenceClickListener {
                val alertDialog = ActionItem.getConfigureBubbleAlert(requireActivity(), object : ActionItem.OnActionItemSelectedListener {
                    override fun onSelected(actionItem: ActionItem) {
                        Settings.get().setConsumeBubble(Constant.BubbleAction.ConsumeLeft, actionItem.mType, actionItem.getLabel(),
                                actionItem.mPackageName, actionItem.mActivityClassName)
                        updateConsumeBubblePreference(leftConsumeBubblePreference, Constant.BubbleAction.ConsumeLeft)
                    }
                })
                Util.showThemedDialog(alertDialog)
                true
            }
            updateConsumeBubblePreference(leftConsumeBubblePreference, Constant.BubbleAction.ConsumeLeft)

            val rightConsumeBubblePreference = findPreference<Preference>(Settings.PREFERENCE_RIGHT_CONSUME_BUBBLE)!!
            rightConsumeBubblePreference.setOnPreferenceClickListener {
                val alertDialog = ActionItem.getConfigureBubbleAlert(requireActivity(), object : ActionItem.OnActionItemSelectedListener {
                    override fun onSelected(actionItem: ActionItem) {
                        Settings.get().setConsumeBubble(Constant.BubbleAction.ConsumeRight, actionItem.mType, actionItem.getLabel(),
                                actionItem.mPackageName, actionItem.mActivityClassName)
                        updateConsumeBubblePreference(rightConsumeBubblePreference, Constant.BubbleAction.ConsumeRight)
                    }
                })
                Util.showThemedDialog(alertDialog)
                true
            }
            updateConsumeBubblePreference(rightConsumeBubblePreference, Constant.BubbleAction.ConsumeRight)

            val clearCachePref = findPreference<Preference>("preference_clear_browser_cache")!!
            clearCachePref.setOnPreferenceClickListener {
                onClearBrowserCachePreferenceClick()
            }
            clearCachePref.icon = getTintedDrawable(R.drawable.ic_delete_white_36dp, tintColor)

            mWebViewTextZoomPreference = findPreference<Preference>(Settings.PREFERENCE_WEBVIEW_TEXT_ZOOM)!!
            mWebViewTextZoomPreference.setOnPreferenceClickListener {
                Util.showThemedDialog(getTextZoomDialog())
                true
            }
            mWebViewTextZoomPreference.icon = getTintedDrawable(R.drawable.ic_pageview_white_36dp, tintColor)
            mWebViewTextZoomPreference.summary = Settings.get().getWebViewTextZoom().toString() + "%"

            mUserAgentPreference = findPreference<ListPreference>(Settings.PREFERENCE_USER_AGENT)!!
            mUserAgentPreference.icon = getTintedDrawable(R.drawable.ic_web_white_36dp, tintColor)

            val otherAppsPreference = findPreference<Preference>("preference_my_other_apps")!!
            otherAppsPreference.setOnPreferenceClickListener {
                val intent = MainApplication.getStoreIntent(requireActivity(), BuildConfig.STORE_MY_OTHER_APPS_URL)
                if (intent != null) {
                    startActivity(intent)
                    true
                } else {
                    false
                }
            }
            otherAppsPreference.icon = getTintedDrawable(R.drawable.ic_shop_two_white_36dp, tintColor)

            val faqPref = findPreference<Preference>("preference_faq")!!
            faqPref.setOnPreferenceClickListener {
                val dialog = FAQDialog(requireActivity())
                dialog.show()
                true
            }
            faqPref.icon = getTintedDrawable(R.drawable.ic_question_answer_white_36dp, tintColor)

            findPreference<Preference>("preference_default_apps")!!.setOnPreferenceClickListener {
                startActivity(Intent(requireActivity(), SettingsDefaultAppsActivity::class.java))
                true
            }

            val morePref = findPreference<Preference>("preference_more")!!
            morePref.setOnPreferenceClickListener {
                startActivity(Intent(requireActivity(), SettingsMoreActivity::class.java))
                true
            }
            morePref.icon = getTintedDrawable(R.drawable.ic_more_horiz_white_36dp, tintColor)

            val helpPref = findPreference<Preference>("preference_help")!!
            helpPref.setOnPreferenceClickListener {
                startActivity(Intent(requireActivity(), SettingsHelpActivity::class.java))
                true
            }
            helpPref.icon = getTintedDrawable(R.drawable.ic_help_white_36dp, tintColor)

            val versionPreference = findPreference<Preference>("preference_version")!!
            try {
                val packageInfo = requireActivity().packageManager.getPackageInfo(requireActivity().packageName, 0)
                versionPreference.title = getString(R.string.preference_version_title) + " " + packageInfo.versionName
            } catch (e: PackageManager.NameNotFoundException) {
            }
            versionPreference.setOnPreferenceClickListener {
                val changelogDialog = ChangeLogDialog(requireActivity())
                changelogDialog.show()
                true
            }
            versionPreference.icon = getTintedDrawable(R.drawable.ic_info_white_36dp, tintColor)
        }

        override fun onResume() {
            super.onResume()

            PreferenceManager.getDefaultSharedPreferences(requireContext()).registerOnSharedPreferenceChangeListener(this)

            if (mUserAgentPreference.entry == null) {
                mUserAgentPreference.setValueIndex(0)
            }
            mUserAgentPreference.summary = mUserAgentPreference.entry

            val defaultAppsPreference = findPreference<Preference>(Settings.PREFERENCE_DEFAULT_APPS)!!
            setPreferenceIcon(defaultAppsPreference, Settings.get().getDefaultBrowserIcon(requireActivity()))

            checkDefaultBrowser()
        }

        override fun onPause() {
            super.onPause()

            PreferenceManager.getDefaultSharedPreferences(requireContext()).unregisterOnSharedPreferenceChangeListener(this)
        }

        override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences, key: String?) {
            key ?: return
            val preference = findPreference<Preference>(key)

            if (preference is ListPreference) {
                preference.summary = preference.entry
                if (preference === mUserAgentPreference) {
                    if (MainController.get() != null && MainController.get()!!.reloadAllTabs(requireActivity())) {
                        Toast.makeText(requireActivity(), R.string.user_agent_changed_reloading_current, Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }

        fun checkDefaultBrowser() {

            val packageManager = requireActivity().packageManager

            val setDefaultBrowserPreference = findPreference<Preference>("preference_set_default_browser")
            // Will be null if onResume() is called after the preference has already been removed.
            if (setDefaultBrowserPreference != null) {
                setDefaultBrowserPreference.setOnPreferenceClickListener {
                    // Via http://stackoverflow.com/a/13239706/328679
                    val packageManager2 = requireActivity().packageManager

                    val dummyComponentName = ComponentName(requireActivity().application,
                            DefaultBrowserResetActivity::class.java)
                    packageManager2.setComponentEnabledSetting(dummyComponentName, PackageManager.COMPONENT_ENABLED_STATE_ENABLED, PackageManager.DONT_KILL_APP)

                    val intent = Intent(Intent.ACTION_VIEW)
                    intent.data = Uri.parse(Config.SET_DEFAULT_BROWSER_URL)
                    intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                    requireActivity().startActivity(intent)

                    packageManager2.setComponentEnabledSetting(dummyComponentName, PackageManager.COMPONENT_ENABLED_STATE_DEFAULT, PackageManager.DONT_KILL_APP)
                    true
                }
                setDefaultBrowserPreference.icon = getTintedDrawable(R.drawable.ic_warning_white_36dp,
                        ResourcesCompat.getColor(resources, android.R.color.holo_orange_light, null))

                val defaultBrowserResolveInfo = Util.getDefaultBrowser(packageManager)
                if (defaultBrowserResolveInfo != null) {
                    val defaultBrowserPackageName = if (defaultBrowserResolveInfo.activityInfo != null) defaultBrowserResolveInfo.activityInfo.packageName else null
                    if (defaultBrowserPackageName != null
                            && (defaultBrowserPackageName == BuildConfig.APPLICATION_ID
                                    || defaultBrowserPackageName == BuildConfig.TAP_PATH_PACKAGE_NAME)) {
                        val category = findPreference<PreferenceCategory>("preference_category_configuration")!!
                        category.removePreference(setDefaultBrowserPreference)
                    }
                }
            }
        }

        fun updateThemeSummary() {
            val darkTheme = Settings.get().darkThemeEnabled
            val color = Settings.get().getColoredProgressIndicator()
            if (darkTheme) {
                if (color) {
                    mThemePreference.setSummary(R.string.preference_theme_dark_color)
                } else {
                    mThemePreference.setSummary(R.string.preference_theme_dark_no_color)
                }
            } else {
                if (color) {
                    mThemePreference.setSummary(R.string.preference_theme_light_color)
                } else {
                    mThemePreference.setSummary(R.string.preference_theme_light_no_color)
                }
            }
        }

        fun getThemeDialog(): AlertDialog {
            val lightColor = getString(R.string.preference_theme_light_color)
            val lightNoColor = getString(R.string.preference_theme_light_no_color)
            val darkColor = getString(R.string.preference_theme_dark_color)
            val darkNoColor = getString(R.string.preference_theme_dark_no_color)

            val items = ArrayList<String>()
            items.add(lightColor)
            items.add(lightNoColor)
            items.add(darkColor)
            items.add(darkNoColor)

            val darkTheme = Settings.get().darkThemeEnabled
            val color = Settings.get().getColoredProgressIndicator()
            val startSelectedIndex = if (darkTheme) (if (color) THEME_DARK_COLOR else 3) else (if (color) THEME_LIGHT_COLOR else THEME_LIGHT_NO_COLOR)

            val adapter = PreferenceThemeAdapter(requireActivity(),
                    R.layout.view_preference_theme_item,
                    startSelectedIndex,
                    items.toTypedArray())

            val listView = ListView(requireActivity())
            listView.adapter = adapter

            val builder = AlertDialog.Builder(requireActivity())
            builder.setView(listView)
            builder.setIcon(Util.getAlertIcon(requireActivity()))
            builder.setPositiveButton(R.string.action_ok, DialogInterface.OnClickListener { dialog, which ->
                if (adapter.mSelectedIndex != startSelectedIndex) {
                    when (adapter.mSelectedIndex) {
                        THEME_LIGHT_COLOR -> {
                            Settings.get().setDarkThemeEnabled(false)
                            Settings.get().setColoredProgressIndicator(true)
                        }
                        THEME_LIGHT_NO_COLOR -> {
                            Settings.get().setDarkThemeEnabled(false)
                            Settings.get().setColoredProgressIndicator(false)
                        }
                        THEME_DARK_COLOR -> {
                            Settings.get().setDarkThemeEnabled(true)
                            Settings.get().setColoredProgressIndicator(true)
                        }
                        THEME_DARK_NO_COLOR -> {
                            Settings.get().setDarkThemeEnabled(true)
                            Settings.get().setColoredProgressIndicator(false)
                        }
                    }

                    updateThemeSummary()

                    if (MainController.get() != null) {
                        EventBus.post(MainService.ReloadMainServiceEvent(requireActivity()))
                    }
                }
            })
            builder.setTitle(R.string.preference_theme_title)

            return builder.create()
        }

        private class PreferenceThemeAdapter(
                val mContext: Context, val mLayoutResourceId: Int, initialSelectedIndex: Int, data: Array<String>
        ) : ArrayAdapter<String>(mContext, mLayoutResourceId, data) {

            var mSelectedIndex: Int = initialSelectedIndex

            override fun getView(position: Int, convertViewIn: View?, parent: ViewGroup): View {
                var convertView = convertViewIn

                if (convertView == null) {
                    val inflater = mContext.getSystemService(Context.LAYOUT_INFLATER_SERVICE) as LayoutInflater
                    convertView = inflater.inflate(mLayoutResourceId, parent, false)
                }

                val label = convertView!!.findViewById<TextView>(R.id.label)
                val icon = convertView.findViewById<ImageView>(R.id.icon)
                val radioButton = convertView.findViewById<RadioButton>(R.id.radio_button)

                when (position) {
                    THEME_LIGHT_COLOR -> {
                        label.text = mContext.getString(R.string.preference_theme_light_color)
                        icon.setImageDrawable(ResourcesCompat.getDrawable(mContext.resources, R.drawable.preference_theme_light_color, null))
                    }
                    THEME_LIGHT_NO_COLOR -> {
                        label.text = mContext.getString(R.string.preference_theme_light_no_color)
                        icon.setImageDrawable(ResourcesCompat.getDrawable(mContext.resources, R.drawable.preference_theme_light_no_color, null))
                    }
                    THEME_DARK_COLOR -> {
                        label.text = mContext.getString(R.string.preference_theme_dark_color)
                        icon.setImageDrawable(ResourcesCompat.getDrawable(mContext.resources, R.drawable.preference_theme_dark_color, null))
                    }
                    THEME_DARK_NO_COLOR -> {
                        label.text = mContext.getString(R.string.preference_theme_dark_no_color)
                        icon.setImageDrawable(ResourcesCompat.getDrawable(mContext.resources, R.drawable.preference_theme_dark_no_color, null))
                    }
                    else -> {
                    }
                }
                convertView.tag = position
                convertView.setOnClickListener {
                    radioButton.isChecked = true
                    mSelectedIndex = position
                    this@PreferenceThemeAdapter.notifyDataSetChanged()
                }
                convertView.setOnTouchListener { v, event ->
                    when (event.action) {
                        MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP -> {
                            radioButton.onTouchEvent(event)
                            true
                        }
                        else -> false
                    }
                }
                radioButton.setOnCheckedChangeListener { buttonView, isChecked ->
                    if (isChecked) {
                        mSelectedIndex = position
                        this@PreferenceThemeAdapter.notifyDataSetChanged()
                    }
                }

                radioButton.isChecked = position == mSelectedIndex
                return convertView
            }
        }

        fun updateWebViewBatterySaveSummary() {
            val position = Settings.get().getWebViewBatterySaveMode()!!.ordinal
            if (position == Settings.WebViewBatterySaveMode.Aggressive.ordinal) {
                mWebViewBatterySavePreference.summary = getString(R.string.preference_webview_battery_save_aggressive_title)
            } else if (position == Settings.WebViewBatterySaveMode.Default.ordinal) {
                mWebViewBatterySavePreference.summary = getString(R.string.preference_webview_battery_save_default_title)
            } else if (position == Settings.WebViewBatterySaveMode.Off.ordinal) {
                mWebViewBatterySavePreference.summary = getString(R.string.preference_webview_battery_save_off_title)
            }
        }

        fun getWebViewBatterySaveDialog(): AlertDialog {
            val items = ArrayList<String>()
            items.add(getString(R.string.preference_webview_battery_save_aggressive_title))
            items.add(getString(R.string.preference_webview_battery_save_default_title))
            items.add(getString(R.string.preference_webview_battery_save_off_title))

            val adapter = PreferenceBatterySaveAdapter(requireActivity(),
                    R.layout.view_preference_webview_battery_save_item,
                    Settings.get().getWebViewBatterySaveMode()!!.ordinal,
                    items.toTypedArray())

            val listView = ListView(requireActivity())
            listView.adapter = adapter

            val builder = AlertDialog.Builder(requireActivity())
            builder.setView(listView)
            builder.setIcon(Util.getAlertIcon(requireActivity()))
            builder.setPositiveButton(R.string.action_ok, DialogInterface.OnClickListener { dialog, which ->
                val mode = Settings.WebViewBatterySaveMode.values()[adapter.mSelectedIndex]
                Settings.get().setWebViewBatterySaveMode(mode)
                updateWebViewBatterySaveSummary()
            })
            builder.setTitle(R.string.preference_webview_battery_save_title)

            return builder.create()
        }

        private class PreferenceBatterySaveAdapter(
                val mContext: Context, val mLayoutResourceId: Int, initialSelectedIndex: Int, data: Array<String>
        ) : ArrayAdapter<String>(mContext, mLayoutResourceId, data) {

            var mSelectedIndex: Int = initialSelectedIndex

            override fun getView(position: Int, convertViewIn: View?, parent: ViewGroup): View {
                var convertView = convertViewIn

                if (convertView == null) {
                    val inflater = mContext.getSystemService(Context.LAYOUT_INFLATER_SERVICE) as LayoutInflater
                    convertView = inflater.inflate(mLayoutResourceId, parent, false)
                }

                val label = convertView!!.findViewById<TextView>(R.id.title)
                val summary = convertView.findViewById<TextView>(R.id.summary)
                val radioButton = convertView.findViewById<RadioButton>(R.id.radio_button)

                if (position == Settings.WebViewBatterySaveMode.Aggressive.ordinal) {
                    label.text = mContext.getString(R.string.preference_webview_battery_save_aggressive_title)
                    summary.text = mContext.getString(R.string.preference_webview_battery_save_aggressive_summary)
                } else if (position == Settings.WebViewBatterySaveMode.Default.ordinal) {
                    label.text = mContext.getString(R.string.preference_webview_battery_save_default_title)
                    summary.text = mContext.getString(R.string.preference_webview_battery_save_default_summary)
                } else if (position == Settings.WebViewBatterySaveMode.Off.ordinal) {
                    label.text = mContext.getString(R.string.preference_webview_battery_save_off_title)
                    summary.text = mContext.getString(R.string.preference_webview_battery_save_off_summary)
                }
                convertView.tag = position
                convertView.setOnClickListener {
                    radioButton.isChecked = true
                    mSelectedIndex = position
                    this@PreferenceBatterySaveAdapter.notifyDataSetChanged()
                }
                convertView.setOnTouchListener { v, event ->
                    when (event.action) {
                        MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP -> {
                            radioButton.onTouchEvent(event)
                            true
                        }
                        else -> false
                    }
                }
                radioButton.setOnCheckedChangeListener { buttonView, isChecked ->
                    if (isChecked) {
                        mSelectedIndex = position
                        this@PreferenceBatterySaveAdapter.notifyDataSetChanged()
                    }
                }

                radioButton.isChecked = position == mSelectedIndex
                return convertView
            }
        }

        fun getTextZoomDialog(): AlertDialog {
            val layout = View.inflate(requireActivity(), R.layout.view_preference_text_zoom, null)

            val initialZoom = Settings.get().getWebViewTextZoom()
            val textView = layout.findViewById<TextView>(R.id.seekbar_title)
            val seekBar = layout.findViewById<SeekBar>(R.id.seekbar_text_zoom)
            textView.text = (initialZoom + Settings.PREFERENCE_WEBVIEW_TEXT_ZOOM_MIN).toString() + "%"
            seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBarIn: SeekBar, progressIn: Int, fromUser: Boolean) {
                    var progress = progressIn
                    if (progress < 0) {
                        progress = 0
                    } else {
                        val stepSize = 5
                        progress = (Math.round(progress.toFloat() / stepSize)) * stepSize
                    }
                    seekBarIn.progress = progress

                    textView.text = (progress + Settings.PREFERENCE_WEBVIEW_TEXT_ZOOM_MIN).toString() + "%"
                }

                override fun onStartTrackingTouch(seekBar: SeekBar) {

                }

                override fun onStopTrackingTouch(seekBar: SeekBar) {

                }
            })
            seekBar.max = Settings.PREFERENCE_WEBVIEW_TEXT_ZOOM_MAX - Settings.PREFERENCE_WEBVIEW_TEXT_ZOOM_MIN
            seekBar.progress = initialZoom - Settings.PREFERENCE_WEBVIEW_TEXT_ZOOM_MIN

            val builder = AlertDialog.Builder(requireActivity())
            builder.setIcon(Util.getAlertIcon(requireActivity()))
            builder.setView(layout)
            builder.setTitle(R.string.preference_webview_text_zoom_title)

            val alertDialog = builder.create()

            alertDialog.setButton(AlertDialog.BUTTON_POSITIVE, getString(android.R.string.ok), DialogInterface.OnClickListener { dialog, which ->
                Settings.get().setWebViewTextZoom(seekBar.progress + Settings.PREFERENCE_WEBVIEW_TEXT_ZOOM_MIN)
                val currentZoom = Settings.get().getWebViewTextZoom()
                mWebViewTextZoomPreference.summary = "$currentZoom%"
                if (currentZoom != initialZoom && MainController.get() != null) {
                    if (MainController.get()!!.reloadAllTabs(requireActivity())) {
                        Toast.makeText(requireActivity(), R.string.preference_webview_text_zoom_reloading_current, Toast.LENGTH_SHORT).show()
                    }
                }
            })

            alertDialog.setButton(AlertDialog.BUTTON_NEGATIVE, getString(R.string.action_use_default), DialogInterface.OnClickListener { dialog, which ->
                Settings.get().setWebViewTextZoom(Settings.PREFERENCE_WEBVIEW_TEXT_ZOOM_DEFAULT)
                val currentZoom = Settings.get().getWebViewTextZoom()
                mWebViewTextZoomPreference.summary = "$currentZoom%"
                if (currentZoom != initialZoom && MainController.get() != null) {
                    if (MainController.get()!!.reloadAllTabs(requireActivity())) {
                        Toast.makeText(requireActivity(), R.string.preference_webview_text_zoom_reloading_current, Toast.LENGTH_SHORT).show()
                    }
                }
            })

            return alertDialog
        }

        private fun onClearBrowserCachePreferenceClick(): Boolean {

            val clearCache = getString(R.string.preference_clear_cache)
            val clearCookies = getString(R.string.preference_clear_cookies)
            val clearFavicons = getString(R.string.preference_clear_favicons)
            val clearFormData = getString(R.string.preference_clear_form_data)
            val clearHistory = getString(R.string.preference_clear_history)
            val clearPasswords = getString(R.string.preference_clear_passwords)

            val items = ArrayList<String>()
            items.add(clearCache)
            items.add(clearCookies)
            items.add(clearFavicons)
            items.add(clearFormData)
            items.add(clearHistory)
            items.add(clearPasswords)

            val listAdapter = ArrayAdapter<String>(requireActivity(), android.R.layout.simple_list_item_multiple_choice, items)

            val listView = ListView(requireActivity())
            listView.choiceMode = ListView.CHOICE_MODE_MULTIPLE
            listView.adapter = listAdapter
            for (i in items.indices) {
                listView.setItemChecked(i, if (items[i] == clearFavicons) false else true)
            }

            val builder = AlertDialog.Builder(requireActivity())
            builder.setView(listView)
            builder.setIcon(Util.getAlertIcon(requireActivity()))
            builder.setPositiveButton(R.string.action_clear_data, DialogInterface.OnClickListener { dialog, which ->

                val webView = WebView(requireActivity())
                val webViewDatabase = WebViewDatabase.getInstance(requireActivity().applicationContext)
                var dataCleared = false
                val count = listView.count
                for (i in 0 until count) {
                    if (listView.isItemChecked(i)) {
                        val item = items[i]
                        if (item == clearCache) {
                            webView.clearCache(true)
                            dataCleared = true
                        } else if (item == clearCookies) {
                            val cookieManager = CookieManager.getInstance()
                            if (cookieManager != null && cookieManager.hasCookies()) {
                                cookieManager.removeAllCookie()
                            }
                            dataCleared = true
                        } else if (item == clearFavicons) {
                            MainApplication.sDatabaseHelper!!.deleteAllFavicons()
                            MainApplication.recreateFaviconCache()
                            dataCleared = true
                        } else if (item == clearFormData) {
                            if (webViewDatabase != null) {
                                webViewDatabase.clearFormData()
                                dataCleared = true
                            }
                        } else if (item == clearHistory) {
                            webView.clearHistory()
                            MainApplication.sDatabaseHelper!!.deleteAllHistoryRecords()
                            Settings.get().saveCurrentTabs(null)
                            dataCleared = true
                        } else if (item == clearPasswords) {
                            if (webViewDatabase != null) {
                                webViewDatabase.clearHttpAuthUsernamePassword()
                                webViewDatabase.clearUsernamePassword()
                                dataCleared = true
                            }
                        }
                    }
                }

                if (dataCleared) {
                    var reloaded = false

                    if (MainController.get() != null) {
                        reloaded = MainController.get()!!.reloadAllTabs(requireActivity())
                    }

                    Toast.makeText(requireActivity(), if (reloaded) R.string.private_data_cleared_reloading_current else R.string.private_data_cleared,
                            Toast.LENGTH_SHORT).show()
                }
            })
            builder.setNegativeButton(R.string.cancel, DialogInterface.OnClickListener { dialog, which ->
                dialog.dismiss()
            })
            builder.setTitle(R.string.preference_clear_browser_cache_title)

            val alertDialog = builder.create()
            Util.showThemedDialog(alertDialog)

            return true
        }

        fun updateConsumeBubblePreference(preference: Preference, action: Constant.BubbleAction) {
            preference.summary = Settings.get().getConsumeBubbleLabel(action)
            setPreferenceIcon(preference, Settings.get().getConsumeBubbleIcon(action, false))
        }

        companion object {
            private const val THEME_LIGHT_COLOR = 0
            private const val THEME_LIGHT_NO_COLOR = 1
            private const val THEME_DARK_COLOR = 2
            private const val THEME_DARK_NO_COLOR = 3
        }
    }
}
