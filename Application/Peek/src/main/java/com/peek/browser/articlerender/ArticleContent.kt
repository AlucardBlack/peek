/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package com.peek.browser.articlerender

import android.util.Log
import com.peek.browser.Config
import com.peek.browser.Settings
import de.jetwick.snacktory.HtmlFetcher
import de.jetwick.snacktory.JResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.MalformedURLException
import java.net.URL
import java.text.SimpleDateFormat

class ArticleContent {

    @JvmField
    var mPageHtml: String? = null
    var mTitle: String? = null
    @JvmField
    var mText: String? = null
    @JvmField
    var mUrl: URL? = null

    interface OnFinishedListener {
        fun onFinished(articleContent: ArticleContent?)
    }

    // Cancellation relies on plain coroutine Job cancellation (unlike UiAsyncTask): there's no
    // onCancelled() override here to preserve, so letting the launch abort silently on cancel()
    // matches the original AsyncTask contract of simply not calling onPostExecute/onFinished.
    class BuildContentTask(private val mOnFinishedListener: OnFinishedListener) {

        private var mJob: Job? = null

        internal fun execute(url: String, pageHtml: String) {
            mJob = CoroutineScope(Dispatchers.Main).launch {
                val result = withContext(Dispatchers.IO) {
                    try {
                        Log.d(TAG, "BuildContentTask().doInBackground(): url:$url")
                        HtmlFetcher().extract(url, pageHtml)
                    } catch (ex: Exception) {
                        Log.d(TAG, ex.localizedMessage, ex)
                        null
                    }
                }

                if (result == null) {
                    mOnFinishedListener.onFinished(null)
                    return@launch
                }

                val articleContent = extract(result)

                if (articleContent.mUrl == null || articleContent.mText!!.isEmpty()) {
                    mOnFinishedListener.onFinished(null)
                } else {
                    mOnFinishedListener.onFinished(articleContent)
                }
            }
        }

        fun cancel(mayInterruptIfRunning: Boolean) {
            mJob?.cancel()
        }
    }

    companion object {
        private const val TAG = "ArticleContent"

        private val sDateFormat = SimpleDateFormat("MMM dd, yyyy")

        @JvmStatic
        fun fetchArticleContent(url: String, pageHtml: String, onFinishedListener: OnFinishedListener): BuildContentTask {
            val task = BuildContentTask(onFinishedListener)
            task.execute(url, pageHtml)
            return task
        }

        @JvmStatic
        fun extract(result: JResult): ArticleContent {
            val articleModeContent = ArticleContent()

            var urlAsString = result.getCanonicalUrl()
            if (urlAsString == null || urlAsString.isEmpty()) {
                urlAsString = result.getUrl()
            }
            try {
                articleModeContent.mUrl = URL(urlAsString)
            } catch (e: MalformedURLException) {
                e.printStackTrace()
                return articleModeContent
            }

            articleModeContent.mText = result.getText()
            if (articleModeContent.mText!!.isEmpty()) {
                return articleModeContent
            }

            val bodyHMargin: String
            val titleTopMargin: String
            val titleFontSize: String
            if (Config.sIsTablet) {
                bodyHMargin = "24px"
                titleTopMargin = "32px"
                titleFontSize = "150%"
            } else {
                bodyHMargin = "12px"
                titleTopMargin = "24px"
                titleFontSize = "130%"
            }

            val textColor = String.format("#%06X", 0xFFFFFF and Settings.get().getThemedTextColor())
            val bgColor = String.format("#%06X", 0xFFFFFF and Settings.get().getThemedContentViewColor())
            val linkColor = String.format("#%06X", 0xFFFFFF and Settings.get().getThemedLinkColor())

            var headHtml =
                    "  <head>\n" +
                            "    <meta http-equiv=\"Content-Type\" content=\"text/html; charset=utf-8\">\n" +
                            "    <meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=0, height=device-height\"/>\n" +
                            "    <link href='http://fonts.googleapis.com/css?family=Roboto' rel='stylesheet' type='text/css'>\n" +
                            "    <style type=\"text/css\">\n" +
                            "      body { background-color: " + bgColor + "; color: " + textColor + ";}\n" +
                            "      p, div { font-family: 'Roboto', sans-serif; font-size: 16px; line-height: 160%;}\n" +
                            "      a { text-decoration: none; color: " + linkColor + "}\n" +
                            "      #lbInfo { width:100%; min-height:28px; margin:0 auto; padding-bottom: 20px;}\n" +
                            "      #lbInfoL { float:left; width:70%; }\n" +
                            "      #lbInfoR { float:right; width:30%; }\n" +
                            "    </style>" +
                            "    </style>"

            var bodyHtml = "<body >\n" +
                    "    <div style=\"margin:0px " + bodyHMargin + " 0px " + bodyHMargin + "\">\n"

            val title = result.getTitle()
            if (title != null) {
                headHtml += "<title>$title</title>"
                bodyHtml += "<p style=\"font-size:$titleFontSize;line-height:120%;font-weight:bold;margin:$titleTopMargin 0px 12px 0px\">$title</p>"
            }

            val authorName = result.getAuthorName()
            val publishedDate = result.getDate()

            var leftString = ""
            var rightString = ""

            if (authorName != null) {
                leftString = "<span class=\"nowrap\"><b>$authorName</b>,</span> "
            }
            val articleUrl = articleModeContent.mUrl
            if (articleUrl != null) {
                leftString += "<span class=\"nowrap\"><a href=\"" + articleUrl.protocol +
                        "://" + articleUrl.host + "\">" + articleUrl.host.replace("www.", "") + "</a></span>"
            }

            Log.d("info", "urlHost:" + articleModeContent.mUrl!!.host + ", authorName: " + authorName)

            if (publishedDate != null) {
                rightString = "<span style=\"float:right\">" + sDateFormat.format(publishedDate) + "</span>"
            }

            bodyHtml += "<hr style=\"border: 0;height: 0; border-top: 1px solid rgba(0, 0, 0, 0.1); border-bottom: 1px solid rgba(255, 255, 255, 0.3);\">" +
                    "<div id=\"lbInfo\"><div id=\"lbInfoL\">" + leftString + "</div><div id=\"lbInfoR\">" + rightString + "</div></div>"

            val html = result.getHtml()
            if (html != null) {
                bodyHtml += html
            }

            headHtml += "</head>"
            bodyHtml += " </div>\n" +
                    "    </div>\n" +
                    "    <br><br><br>" +
                    "  </body>\n"

            //mWebView.loadUrl(urlAsString);
            //mWebView.stopLoading();

            val pageHtml = "<!DOCTYPE html>\n" + "<html lang=\"en\">\n" + headHtml + bodyHtml + "</html>"

            articleModeContent.mPageHtml = pageHtml
            articleModeContent.mTitle = title

            return articleModeContent
        }

        // **Broken links
        //
        // [nothing displays]:
        //  * http://www.bostonglobe.com/sports/2014/04/28/the-donald-sterling-profile-not-pretty-picture/jZx4v3EWUFdLYh9c289ODL/story.html

        @JvmStatic
        fun tryForArticleContent(url: URL): Boolean {
            val path = url.path
            if (path == "/" || path == "/m/" || path == "/mobile/") {
                Log.d(TAG, "ignore path for url: $url")
                return false
            }

            // Ignore the media sites
            val host = url.host
            if (host.contains("google.com") || host == "imgur.com" || host == "instagram.com"
                    || host == "reddit.com" || host == "twitter.com" || host == "vine.co" || host == "vimeo.com"
                    || host == "youtube.com") {
                Log.d(TAG, "ignore host for url: $url")
                return false
            }

            return true
        }
    }
}
