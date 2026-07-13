package com.peek.browser.ui

import android.app.Activity
import android.app.AlertDialog
import android.content.DialogInterface
import android.content.pm.PackageManager.NameNotFoundException
import android.content.res.Resources
import android.content.res.XmlResourceParser
import android.util.Log
import android.webkit.WebView
import android.widget.Toast
import com.peek.browser.R
import com.peek.browser.util.Util
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserException
import java.io.IOException

/*
 * Class to show a change log dialog
 * (c) 2012 Martin van Zuilekom (http://martin.cubeactive.com)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */
class ChangeLogDialog(context: Activity) {

    private val fActivity: Activity = context

    // Get the current app version
    private fun GetAppVersion(): String? {
        try {
            val _info = fActivity.packageManager.getPackageInfo(fActivity.packageName, 0)
            return _info.versionName
        } catch (e: NameNotFoundException) {
            e.printStackTrace()
            return ""
        }
    }

    // Parse a the release tag and return html code
    private fun ParseReleaseTag(aXml: XmlResourceParser): String {
        var _Result = "<h1>Release: " + aXml.getAttributeValue(null, "version") + "</h1><ul>"
        var eventType = aXml.eventType
        while ((eventType != XmlPullParser.END_TAG) || (aXml.name == "change")) {
            if ((eventType == XmlPullParser.START_TAG) && (aXml.name == "change")) {
                eventType = aXml.next()
                _Result = _Result + "<li>" + aXml.text + "</li>"
            }
            eventType = aXml.next()
        }
        _Result = _Result + "</ul>"
        return _Result
    }

    // CSS style for the html
    private fun GetStyle(): String {
        return ("<style type=\"text/css\">"
                + "h1 { margin-left: 0px; font-size: 12pt; }"
                + "li { margin-left: 0px; font-size: 9pt;}"
                + "ul { padding-left: 30px;}"
                + "</style>")
    }

    // Get the changelog in html code, this will be shown in the dialog's webview
    private fun GetHTMLChangelog(aResourceId: Int, aResource: Resources): String {
        var _Result = "<html><head>" + GetStyle() + "</head><body>"
        val _xml = aResource.getXml(aResourceId)
        try {
            var eventType = _xml.eventType
            while (eventType != XmlPullParser.END_DOCUMENT) {
                if ((eventType == XmlPullParser.START_TAG) && (_xml.name == "release")) {
                    _Result = _Result + ParseReleaseTag(_xml)
                }
                eventType = _xml.next()
            }
        } catch (e: XmlPullParserException) {
            Log.e(TAG, e.message, e)
        } catch (e: IOException) {
            Log.e(TAG, e.message, e)
        } finally {
            _xml.close()
        }
        _Result = _Result + "</body></html>"
        return _Result
    }

    // Call to show the changelog dialog
    fun show() {
        // Get resources
        val _PackageName = fActivity.packageName
        val _Resource: Resources
        try {
            _Resource = fActivity.packageManager.getResourcesForApplication(_PackageName)
        } catch (e: NameNotFoundException) {
            e.printStackTrace()
            return
        }

        // Get dialog title
        var _resID = _Resource.getIdentifier(TITLE_CHANGELOG, "string", _PackageName)
        var _Title = _Resource.getString(_resID)
        _Title = _Title + " v" + GetAppVersion()

        // Get Changelog xml resource id
        _resID = _Resource.getIdentifier(CHANGELOG_XML, "xml", _PackageName)
        // Create html change log
        val _HTML = GetHTMLChangelog(_resID, _Resource)

        // Get button strings
        val _Close = _Resource.getString(R.string.changelog_close)

        // Check for empty changelog
        if (_HTML == "") {
            // Could not load change log, message user and exit void
            Toast.makeText(fActivity, "Could not load change log", Toast.LENGTH_SHORT).show()
            return
        }

        // Create webview and load html
        val _WebView = WebView(fActivity)
        _WebView.loadData(_HTML, "text/html", "utf-8")
        val builder = AlertDialog.Builder(fActivity)
                .setIcon(Util.getAlertIcon(fActivity))
                .setTitle(_Title)
                .setView(_WebView)
                .setPositiveButton(_Close, DialogInterface.OnClickListener { dialogInterface, i ->
                    dialogInterface.dismiss()
                })
        Util.showThemedDialog(builder.create())
    }

    companion object {
        private const val TAG = "ChangeLogDialog"

        private const val TITLE_CHANGELOG = "title_changelog"
        private const val CHANGELOG_XML = "changelog"
    }
}
