/*
 *  Copyright 2011 Peter Karich
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package de.jetwick.snacktory

import org.jsoup.nodes.Element
import java.io.UnsupportedEncodingException
import java.net.CookieHandler
import java.net.CookieManager
import java.net.CookiePolicy
import java.net.URLDecoder
import java.net.URLEncoder
import java.security.SecureRandom
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.text.SimpleDateFormat
import javax.net.ssl.KeyManager
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/**
 *
 * @author Peter Karich
 */
object SHelper {

    const val UTF8 = "UTF-8"
    private val SPACE = Regex(" ")

    @JvmStatic
    fun replaceSpaces(urlIn: String): String {
        var url = urlIn
        if (url.isNotEmpty()) {
            url = url.trim()
            if (url.contains(" ")) {
                url = SPACE.replace(url, "%20")
            }
        }
        return url
    }

    @JvmStatic
    fun count(str: String, substring: String): Int {
        var c = 0
        val index1 = str.indexOf(substring)
        if (index1 >= 0) {
            c++
            c += count(str.substring(index1 + substring.length), substring)
        }
        return c
    }

    /**
     * remove more than two spaces or newlines
     */
    @JvmStatic
    fun innerTrim(str: String): String {
        if (str.isEmpty())
            return ""

        val sb = StringBuilder()
        var previousSpace = false
        for (i in str.indices) {
            val c = str[i]
            if (c == ' ' || c.code == 9 || c == '\n') {
                previousSpace = true
                continue
            }

            if (previousSpace)
                sb.append(' ')

            previousSpace = false
            sb.append(c)
        }
        return sb.toString().trim()
    }

    /**
     * Starts reading the encoding from the first valid character until an
     * invalid encoding character occurs.
     */
    @JvmStatic
    fun encodingCleanup(str: String): String {
        val sb = StringBuilder()
        var startedWithCorrectString = false
        for (i in str.indices) {
            val c = str[i]
            if (Character.isDigit(c) || Character.isLetter(c) || c == '-' || c == '_') {
                startedWithCorrectString = true
                sb.append(c)
                continue
            }

            if (startedWithCorrectString)
                break
        }
        return sb.toString().trim()
    }

    /**
     * @return the longest substring as str1.substring(result[0], result[1]);
     */
    @JvmStatic
    fun getLongestSubstring(str1: String, str2: String): String {
        val res = longestSubstring(str1, str2)
        if (res == null || res[0] >= res[1])
            return ""

        return str1.substring(res[0], res[1])
    }

    @JvmStatic
    fun longestSubstring(str1: String?, str2: String?): IntArray? {
        if (str1 == null || str1.isEmpty() || str2 == null || str2.isEmpty())
            return null

        // dynamic programming => save already identical length into array
        // to understand this algo simply print identical length in every entry of the array
        // i+1, j+1 then reuses information from i,j
        // java initializes them already with 0
        val num = Array(str1.length) { IntArray(str2.length) }
        var maxlen = 0
        var lastSubstrBegin = 0
        var endIndex = 0
        for (i in str1.indices) {
            for (j in str2.indices) {
                if (str1[i] == str2[j]) {
                    if (i == 0 || j == 0)
                        num[i][j] = 1
                    else
                        num[i][j] = 1 + num[i - 1][j - 1]

                    if (num[i][j] > maxlen) {
                        maxlen = num[i][j]
                        // generate substring from str1 => i
                        lastSubstrBegin = i - num[i][j] + 1
                        endIndex = i + 1
                    }
                }
            }
        }
        return intArrayOf(lastSubstrBegin, endIndex)
    }

    @JvmStatic
    fun getDefaultFavicon(url: String): String {
        return useDomainOfFirstArg4Second(url, "/favicon.ico")
    }

    /**
     * @param urlForDomain extract the domain from this url
     * @param path this url does not have a domain
     * @return
     */
    @JvmStatic
    fun useDomainOfFirstArg4Second(urlForDomainIn: String, pathIn: String): String {
        var urlForDomain = urlForDomainIn
        var path = pathIn
        if (path.startsWith("http"))
            return path

        if ("favicon.ico" == path)
            path = "/favicon.ico"

        if (path.startsWith("//")) {
            // wikipedia special case, see tests
            if (urlForDomain.startsWith("https:"))
                return "https:$path"

            return "http:$path"
        } else if (path.startsWith("/"))
            return "http://" + extractHost(urlForDomain) + path
        else if (path.startsWith("../")) {
            val slashIndex = urlForDomain.lastIndexOf("/")
            if (slashIndex > 0 && slashIndex + 1 < urlForDomain.length)
                urlForDomain = urlForDomain.substring(0, slashIndex + 1)

            return urlForDomain + path
        }
        return path
    }

    @JvmStatic
    fun extractHost(url: String): String {
        return extractDomain(url, false)
    }

    @JvmStatic
    fun extractDomain(urlIn: String, aggressive: Boolean): String {
        var url = urlIn
        if (url.startsWith("http://"))
            url = url.substring("http://".length)
        else if (url.startsWith("https://"))
            url = url.substring("https://".length)

        if (aggressive) {
            if (url.startsWith("www."))
                url = url.substring("www.".length)

            // strip mobile from start
            if (url.startsWith("m."))
                url = url.substring("m.".length)
        }

        val slashIndex = url.indexOf("/")
        if (slashIndex > 0)
            url = url.substring(0, slashIndex)

        return url
    }

    @JvmStatic
    fun isVideoLink(urlIn: String): Boolean {
        val url = extractDomain(urlIn, true)
        return url.startsWith("youtube.com") || url.startsWith("video.yahoo.com")
                || url.startsWith("vimeo.com") || url.startsWith("blip.tv")
    }

    @JvmStatic
    fun isVideo(url: String): Boolean {
        return url.endsWith(".mpeg") || url.endsWith(".mpg") || url.endsWith(".avi") || url.endsWith(".mov")
                || url.endsWith(".mpg4") || url.endsWith(".mp4") || url.endsWith(".flv") || url.endsWith(".wmv")
    }

    @JvmStatic
    fun isAudio(url: String): Boolean {
        return url.endsWith(".mp3") || url.endsWith(".ogg") || url.endsWith(".m3u") || url.endsWith(".wav")
    }

    @JvmStatic
    fun isDoc(url: String): Boolean {
        return url.endsWith(".pdf") || url.endsWith(".ppt") || url.endsWith(".doc")
                || url.endsWith(".swf") || url.endsWith(".rtf") || url.endsWith(".xls")
    }

    @JvmStatic
    fun isPackage(url: String): Boolean {
        return url.endsWith(".gz") || url.endsWith(".tgz") || url.endsWith(".zip")
                || url.endsWith(".rar") || url.endsWith(".deb") || url.endsWith(".rpm") || url.endsWith(".7z")
    }

    @JvmStatic
    fun isApp(url: String): Boolean {
        return url.endsWith(".exe") || url.endsWith(".bin") || url.endsWith(".bat") || url.endsWith(".dmg")
    }

    @JvmStatic
    fun isImage(url: String): Boolean {
        return url.endsWith(".png") || url.endsWith(".jpeg") || url.endsWith(".gif")
                || url.endsWith(".jpg") || url.endsWith(".bmp") || url.endsWith(".ico") || url.endsWith(".eps")
    }

    /**
     * @see
     * http://blogs.sun.com/CoreJavaTechTips/entry/cookie_handling_in_java_se
     */
    @JvmStatic
    fun enableCookieMgmt() {
        val manager = CookieManager()
        manager.setCookiePolicy(CookiePolicy.ACCEPT_ALL)
        CookieHandler.setDefault(manager)
    }

    /**
     * @see
     * http://stackoverflow.com/questions/2529682/setting-user-agent-of-a-java-urlconnection
     */
    @JvmStatic
    fun enableUserAgentOverwrite() {
        System.setProperty("http.agent", "")
    }

    @JvmStatic
    fun getUrlFromUglyGoogleRedirect(urlIn: String): String? {
        var url = urlIn
        if (url.startsWith("http://www.google.com/url?")) {
            url = url.substring("http://www.google.com/url?".length)
            val arr = urlDecode(url).split("&")
            for (str in arr) {
                if (str.startsWith("q="))
                    return str.substring("q=".length)
            }
        }

        return null
    }

    @JvmStatic
    fun getUrlFromUglyFacebookRedirect(urlIn: String): String? {
        var url = urlIn
        if (url.startsWith("http://www.facebook.com/l.php?u=")) {
            url = url.substring("http://www.facebook.com/l.php?u=".length)
            return urlDecode(url)
        }

        return null
    }

    @JvmStatic
    fun urlEncode(str: String): String {
        try {
            return URLEncoder.encode(str, UTF8)
        } catch (ex: UnsupportedEncodingException) {
            return str
        }
    }

    @JvmStatic
    fun urlDecode(str: String): String {
        try {
            return URLDecoder.decode(str, UTF8)
        } catch (ex: UnsupportedEncodingException) {
            return str
        }
    }

    /**
     * Popular sites uses the #! to indicate the importance of the following
     * chars. Ugly but true. Such as: facebook, twitter, gizmodo, ...
     */
    @JvmStatic
    fun removeHashbang(url: String): String {
        return url.replaceFirst("#!".toRegex(), "")
    }

    @JvmStatic
    @JvmOverloads
    fun printNode(root: Element, indentation: Int = 0): String {
        val sb = StringBuilder()
        for (i in 0 until indentation) {
            sb.append(' ')
        }
        sb.append(root.tagName())
        sb.append(":")
        sb.append(root.ownText())
        sb.append("\n")
        for (el in root.children()) {
            sb.append(printNode(el, indentation + 1))
            sb.append("\n")
        }
        return sb.toString()
    }

    @JvmStatic
    fun estimateDate(urlIn: String): String? {
        var url = urlIn
        val index = url.indexOf("://")
        if (index > 0)
            url = url.substring(index + 3)

        var year = -1
        var yearCounter = -1
        var month = -1
        var monthCounter = -1
        var day = -1
        val strs = url.split("/")
        for (counter in strs.indices) {
            val str = strs[counter]
            if (str.length == 4) {
                try {
                    year = Integer.parseInt(str)
                } catch (ex: Exception) {
                    continue
                }
                if (year < 1970 || year > 3000) {
                    year = -1
                    continue
                }
                yearCounter = counter
            } else if (str.length == 2) {
                if (monthCounter < 0 && counter == yearCounter + 1) {
                    try {
                        month = Integer.parseInt(str)
                    } catch (ex: Exception) {
                        continue
                    }
                    if (month < 1 || month > 12) {
                        month = -1
                        continue
                    }
                    monthCounter = counter
                } else if (counter == monthCounter + 1) {
                    try {
                        day = Integer.parseInt(str)
                    } catch (ex: Exception) {
                    }
                    if (day < 1 || day > 31) {
                        day = -1
                        continue
                    }
                    break
                }
            }
        }

        if (year < 0)
            return null

        val str = StringBuilder()
        str.append(year)
        if (month < 1)
            return str.toString()

        str.append('/')
        if (month < 10)
            str.append('0')
        str.append(month)
        if (day < 1)
            return str.toString()

        str.append('/')
        if (day < 10)
            str.append('0')
        str.append(day)
        return str.toString()
    }

    @JvmStatic
    fun completeDate(dateStr: String?): String? {
        if (dateStr == null)
            return null

        var index = dateStr.indexOf('/')
        if (index > 0) {
            index = dateStr.indexOf('/', index + 1)
            return if (index > 0)
                dateStr
            else
                "$dateStr/01"
        }
        return "$dateStr/01/01"
    }

    /**
     * keep in mind: simpleDateFormatter is not thread safe! call completeDate
     * before applying this formatter.
     */
    @JvmStatic
    fun createDateFormatter(): SimpleDateFormat {
        return SimpleDateFormat("yyyy/MM/dd")
    }

    // with the help of http://stackoverflow.com/questions/1828775/httpclient-and-ssl
    @JvmStatic
    fun enableAnySSL() {
        try {
            val ctx = SSLContext.getInstance("TLS")
            ctx.init(arrayOfNulls<KeyManager>(0), arrayOf<TrustManager>(DefaultTrustManager()), SecureRandom())
            SSLContext.setDefault(ctx)
        } catch (ex: Exception) {
            ex.printStackTrace()
        }
    }

    private class DefaultTrustManager : X509TrustManager {

        @Throws(CertificateException::class)
        override fun checkClientTrusted(arg0: Array<X509Certificate>?, arg1: String?) {
        }

        @Throws(CertificateException::class)
        override fun checkServerTrusted(arg0: Array<X509Certificate>?, arg1: String?) {
        }

        override fun getAcceptedIssuers(): Array<X509Certificate>? {
            return null
        }
    }

    @JvmStatic
    fun countLetters(str: String): Int {
        val len = str.length
        var chars = 0
        for (i in 0 until len) {
            if (Character.isLetter(str[i]))
                chars++
        }
        return chars
    }
}
