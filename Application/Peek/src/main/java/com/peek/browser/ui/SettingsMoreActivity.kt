/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package com.peek.browser.ui

import android.app.AlertDialog
import android.content.Context
import android.content.DialogInterface
import android.os.Build
import android.os.Bundle
import androidx.preference.CheckBoxPreference
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import android.view.MenuItem
import android.widget.Toast
import com.peek.browser.BuildConfig
import com.peek.browser.MainApplication
import com.peek.browser.MainController
import com.peek.browser.R
import com.peek.browser.Settings
import com.peek.browser.util.AppPickerList
import com.peek.browser.util.EventBus
import com.peek.browser.util.Util
import java.util.ArrayList
import java.util.Comparator
import java.util.Locale

/*
 * This class exists solely because Android's PreferenceScreen implementation doesn't do anything
 * when the Up button is touched, and we need to go back in that case given our use of the Up button.
 */
class SettingsMoreActivity : AppCompatActivity() {

    class AdBlockTurnOnEvent

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContentView(R.layout.activity_settings_more)

        val toolbar = findViewById<Toolbar>(R.id.toolbar)
        toolbar.setTitle(R.string.preference_more_title)
        setSupportActionBar(toolbar)
        Util.padForStatusBarInset(toolbar)
        toolbar.setNavigationIcon(R.drawable.ic_arrow_back_white_24dp)
        toolbar.setNavigationOnClickListener {
            finish()
        }
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            android.R.id.home -> {
                finish()
                return true
            }
        }

        return super.onOptionsItemSelected(item)
    }

    override fun finish() {
        super.finish()
        overridePendingTransition(R.anim.activity_close_enter, R.anim.activity_close_exit)
    }

    class SettingsMoreFragment : SettingsBaseFragment() {

        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            setPreferencesFromResource(R.xml.preferences_more, rootKey)

            val articleModeWearPreference = findPreference<CheckBoxPreference>(Settings.KEY_ARTICLE_MODE_ON_WEAR_PREFERENCE)!!
            articleModeWearPreference.setOnPreferenceChangeListener { preference, newValue ->
                if (MainController.get() != null && MainController.get()!!.reloadAllTabs(requireActivity())) {
                    Toast.makeText(requireActivity(), R.string.article_mode_changed_reloading_current, Toast.LENGTH_SHORT).show()
                }
                true
            }

            val articleModePreference = findPreference<CheckBoxPreference>(Settings.KEY_ARTICLE_MODE_PREFERENCE)!!
            articleModePreference.setOnPreferenceChangeListener { preference, newValue ->
                if (MainController.get() != null && MainController.get()!!.reloadAllTabs(requireActivity())) {
                    Toast.makeText(requireActivity(), R.string.article_mode_changed_reloading_current, Toast.LENGTH_SHORT).show()
                }
                true
            }

            val adBlockPreference = findPreference<CheckBoxPreference>(Settings.PREFERENCE_ADBLOCK_MODE)!!
            adBlockPreference.setOnPreferenceChangeListener { preference, newValue ->
                if (newValue as Boolean) {
                    EventBus.post(AdBlockTurnOnEvent())
                }

                true
            }

            val interceptLinksFromPreference = findPreference<androidx.preference.Preference>(Settings.PREFERENCE_IGNORE_LINKS_FROM)!!
            if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.KITKAT) {
                interceptLinksFromPreference.setOnPreferenceClickListener {
                    getDontInterceptLinksFromDialog(requireActivity()).show()
                    true
                }
            } else {
                interceptLinksFromPreference.setSummary(R.string.preference_intercept_links_from_disabled_for_L)
                interceptLinksFromPreference.isEnabled = false
            }
        }

        fun getDontInterceptLinksFromDialog(context: Context): AlertDialog {
            val browserPackageNames = Settings.get().getBrowserPackageNames()

            val layout = AppPickerList.createView(context,
                    (context.applicationContext as MainApplication).mIconCache!!,
                    AppPickerList.SelectionType.MultipleSelection, object : AppPickerList.Initializer {
                        override fun setChecked(packageName: String, activityName: String): Boolean {
                            return if (Settings.get().ignoreLinkFromPackageName(packageName)) false else true
                        }

                        override fun addToList(packageName: String): Boolean {
                            if (packageName == BuildConfig.APPLICATION_ID) {
                                return false
                            }

                            for (browserPackageName in browserPackageNames!!) {
                                if (browserPackageName == packageName) {
                                    return false
                                }
                            }

                            return true
                        }
                    })

            val builder = AlertDialog.Builder(context)
            builder.setView(layout)
            builder.setIcon(Util.getAlertIcon(requireActivity()))
            builder.setPositiveButton(android.R.string.ok, DialogInterface.OnClickListener { dialog, which ->

                val ignorePackageNames = ArrayList<String>()

                val results = AppPickerList.getUnselected(layout)
                if (results != null) {
                    for (result in results) {
                        ignorePackageNames.add(result.mPackageName)
                    }
                }

                Settings.get().setIgnoreLinksFromPackageNames(ignorePackageNames)
            })
            builder.setTitle(R.string.preference_intercept_links_from_title)

            return builder.create()
        }
    }

    class AppInfo(val mActivityName: String, val mPackageName: String, val mDisplayName: String) {
        val mSortName: String = mDisplayName.lowercase(Locale.getDefault())
    }

    class AppInfoComparator : Comparator<AppInfo> {
        override fun compare(lhs: AppInfo, rhs: AppInfo): Int {
            return lhs.mSortName.compareTo(rhs.mSortName)
        }
    }
}
