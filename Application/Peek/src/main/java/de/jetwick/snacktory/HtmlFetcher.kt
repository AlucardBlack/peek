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

import org.slf4j.LoggerFactory
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.FileReader
import java.io.FileWriter
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.MalformedURLException
import java.net.Proxy
import java.net.URL
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.GZIPInputStream
import java.util.zip.Inflater
import java.util.zip.InflaterInputStream

/**
 * Class to fetch articles. This class is thread safe.
 *
 * @author Peter Karich
 */
class HtmlFetcher {

    // We never use it, maybe we should remove it in future
    private var referrer = ""
    private var userAgent = "Mozilla/5.0 (compatible; Peek-Reader)"
    private var cacheControl = "max-age=0"
    private var language = "en-us"
    private var accept = "application/xml,application/xhtml+xml,text/html;q=0.9,text/plain;q=0.8,image/png,*/*;q=0.5"
    private var charset = "UTF-8"
    private var cache: SCache? = null
    private val cacheCounter = AtomicInteger(0)
    private var maxTextLength = -1
    private var extractor: ArticleTextExtractor = ArticleTextExtractor()
    private val furtherResolveNecessary = linkedSetOf(
            "bit.ly", "cli.gs", "deck.ly", "fb.me", "feedproxy.google.com", "flic.kr",
            "fur.ly", "goo.gl", "is.gd", "ink.co", "j.mp", "lnkd.in", "on.fb.me", "ow.ly",
            "plurl.us", "sns.mx", "snurl.com", "su.pr", "t.co", "tcrn.ch", "tl.gd", "tiny.cc",
            "tinyurl.com", "tmi.me", "tr.im", "twurl.nl"
    )

    fun setExtractor(extractor: ArticleTextExtractor) {
        this.extractor = extractor
    }

    fun getExtractor(): ArticleTextExtractor {
        return extractor
    }

    fun setCache(cache: SCache?): HtmlFetcher {
        this.cache = cache
        return this
    }

    fun getCache(): SCache? {
        return cache
    }

    fun getCacheCounter(): Int {
        return cacheCounter.get()
    }

    fun clearCacheCounter(): HtmlFetcher {
        cacheCounter.set(0)
        return this
    }

    fun setMaxTextLength(maxTextLength: Int): HtmlFetcher {
        this.maxTextLength = maxTextLength
        return this
    }

    fun getMaxTextLength(): Int {
        return maxTextLength
    }

    fun setAccept(accept: String) {
        this.accept = accept
    }

    fun setCharset(charset: String) {
        this.charset = charset
    }

    fun setCacheControl(cacheControl: String) {
        this.cacheControl = cacheControl
    }

    fun getLanguage(): String {
        return language
    }

    fun setLanguage(language: String) {
        this.language = language
    }

    fun getReferrer(): String {
        return referrer
    }

    fun setReferrer(referrer: String): HtmlFetcher {
        this.referrer = referrer
        return this
    }

    fun getUserAgent(): String {
        return userAgent
    }

    fun setUserAgent(userAgent: String) {
        this.userAgent = userAgent
    }

    fun getAccept(): String {
        return accept
    }

    fun getCacheControl(): String {
        return cacheControl
    }

    fun getCharset(): String {
        return charset
    }

    @Throws(Exception::class)
    fun fetchAndExtract(urlIn: String, timeout: Int, resolve: Boolean): JResult {
        val originalUrl = urlIn
        var url = SHelper.removeHashbang(urlIn)
        var gUrl = SHelper.getUrlFromUglyGoogleRedirect(url)
        if (gUrl != null)
            url = gUrl
        else {
            gUrl = SHelper.getUrlFromUglyFacebookRedirect(url)
            if (gUrl != null)
                url = gUrl
        }

        if (resolve) {
            // check if we can avoid resolving the URL (which hits the website!)
            val res = getFromCache(url, originalUrl)
            if (res != null)
                return res

            val resUrl = getResolvedUrl(url, timeout)
            if (resUrl.isEmpty()) {
                if (logger.isDebugEnabled)
                    logger.warn("resolved url is empty. Url is: $url")

                val result = JResult()
                cache?.put(url, result)
                return result.setUrl(url)
            }

            // if resolved url is longer then use it!
            if (resUrl.trim().length > url.length) {
                // this is necessary e.g. for some homebaken url resolvers which return
                // the resolved url relative to url!
                url = SHelper.useDomainOfFirstArg4Second(url, resUrl)
            }
        }

        // check if we have the (resolved) URL in cache
        val res = getFromCache(url, originalUrl)
        if (res != null)
            return res

        val result = JResult()
        // or should we use? <link rel="canonical" href="http://www.N24.de/news/newsitem_6797232.html"/>
        result.setUrl(url)
        result.setOriginalUrl(originalUrl)
        //result.setDate(SHelper.estimateDate(url));

        // Immediately put the url into the cache as extracting content takes time.
        cache?.let {
            it.put(originalUrl, result)
            it.put(url, result)
        }

        val lowerUrl = url.lowercase()
        if (SHelper.isDoc(lowerUrl) || SHelper.isApp(lowerUrl) || SHelper.isPackage(lowerUrl)) {
            // skip
        } else if (SHelper.isVideo(lowerUrl) || SHelper.isAudio(lowerUrl)) {
            result.setVideoUrl(url)
        } else if (SHelper.isImage(lowerUrl)) {
            result.setImageUrl(url)
        } else {
            extractor.extractContent(result, fetchAsString(url, timeout))
            if (result.getFaviconUrl().isEmpty())
                result.setFaviconUrl(SHelper.getDefaultFavicon(url))

            // some links are relative to root and do not include the domain of the url :(
            result.setFaviconUrl(fixUrl(url, result.getFaviconUrl()))
            result.setImageUrl(fixUrl(url, result.getImageUrl()))
            result.setVideoUrl(fixUrl(url, result.getVideoUrl()))
            result.setRssUrl(fixUrl(url, result.getRssUrl()))
        }
        result.setText(lessText(result.getText()))
        synchronized(result) {
            (result as java.lang.Object).notifyAll()
        }
        return result
    }

    @Throws(Exception::class)
    fun extract(url: String, html: String): JResult {
        val result = JResult()
        // or should we use? <link rel="canonical" href="http://www.N24.de/news/newsitem_6797232.html"/>
        result.setUrl(url)
        result.setOriginalUrl(url)
        //result.setDate(SHelper.estimateDate(url));

        // Immediately put the url into the cache as extracting content takes time.
        cache?.put(url, result)

        val lowerUrl = url.lowercase()
        if (SHelper.isDoc(lowerUrl) || SHelper.isApp(lowerUrl) || SHelper.isPackage(lowerUrl)) {
            // skip
        } else if (SHelper.isVideo(lowerUrl) || SHelper.isAudio(lowerUrl)) {
            result.setVideoUrl(url)
        } else if (SHelper.isImage(lowerUrl)) {
            result.setImageUrl(url)
        } else {
            extractor.extractContent(result, html)
            if (result.getFaviconUrl().isEmpty())
                result.setFaviconUrl(SHelper.getDefaultFavicon(url))

            // some links are relative to root and do not include the domain of the url :(
            result.setFaviconUrl(fixUrl(url, result.getFaviconUrl()))
            result.setImageUrl(fixUrl(url, result.getImageUrl()))
            result.setVideoUrl(fixUrl(url, result.getVideoUrl()))
            result.setRssUrl(fixUrl(url, result.getRssUrl()))
        }
        result.setText(lessText(result.getText()))
        synchronized(result) {
            (result as java.lang.Object).notifyAll()
        }
        return result
    }

    fun lessText(text: String?): String {
        if (text == null)
            return ""

        if (maxTextLength >= 0 && text.length > maxTextLength)
            return text.substring(0, maxTextLength)

        return text
    }

    @JvmOverloads
    @Throws(MalformedURLException::class, IOException::class)
    fun fetchAsString(urlAsString: String, timeout: Int, includeSomeGooseOptions: Boolean = true): String {
        val hConn = createUrlConnection(urlAsString, timeout, includeSomeGooseOptions)
        hConn.instanceFollowRedirects = true
        val encoding = hConn.contentEncoding
        val `is`: InputStream = if (encoding != null && encoding.equals("gzip", ignoreCase = true)) {
            GZIPInputStream(hConn.inputStream)
        } else if (encoding != null && encoding.equals("deflate", ignoreCase = true)) {
            InflaterInputStream(hConn.inputStream, Inflater(true))
        } else {
            hConn.inputStream
        }

        val enc = Converter.extractEncoding(hConn.contentType)
        val res = createConverter(urlAsString).streamToString(`is`, enc)
        if (logger.isDebugEnabled)
            logger.debug(res.length.toString() + " FetchAsString:" + urlAsString)
        return res
    }

    fun createConverter(url: String): Converter {
        return Converter(url)
    }

    /**
     * On some devices we have to hack:
     * http://developers.sun.com/mobility/reference/techart/design_guidelines/http_redirection.html
     *
     * @param timeout Sets a specified timeout value, in milliseconds
     * @return the resolved url if any. Or null if it couldn't resolve the url
     * (within the specified time) or the same url if response code is OK
     */
    fun getResolvedUrl(urlAsString: String, timeout: Int): String {
        var newUrl: String? = null
        var responseCode = -1
        try {
            val hConn = createUrlConnection(urlAsString, timeout, true)
            // force no follow
            hConn.instanceFollowRedirects = false
            // the program doesn't care what the content actually is !!
            // http://java.sun.com/developer/JDCTechTips/2003/tt0422.html
            hConn.requestMethod = "HEAD"
            hConn.connect()
            responseCode = hConn.responseCode
            hConn.inputStream.close()
            if (responseCode == HttpURLConnection.HTTP_OK)
                return urlAsString

            newUrl = hConn.getHeaderField("Location")
            return if (responseCode / 100 == 3 && newUrl != null) {
                newUrl = newUrl.replace(" ", "+")
                // some services use (none-standard) utf8 in their location header
                if (urlAsString.startsWith("http://bit.ly") || urlAsString.startsWith("http://is.gd"))
                    newUrl = encodeUriFromHeader(newUrl)

                // fix problems if shortened twice. as it is often the case after twitters' t.co bullshit
                if (furtherResolveNecessary.contains(SHelper.extractDomain(newUrl, true)))
                    newUrl = getResolvedUrl(newUrl, timeout)

                newUrl
            } else
                urlAsString
        } catch (ex: Exception) {
            logger.warn("getResolvedUrl:" + urlAsString + " Error:" + ex.message)
            return ""
        } finally {
            if (logger.isDebugEnabled)
                logger.debug("$responseCode url:$urlAsString resolved:$newUrl")
        }
    }

    protected fun createUrlConnection(urlAsStr: String, timeout: Int,
                                       includeSomeGooseOptions: Boolean): HttpURLConnection {
        val url = URL(urlAsStr)
        //using proxy may increase latency
        val hConn = url.openConnection(Proxy.NO_PROXY) as HttpURLConnection
        hConn.setRequestProperty("User-Agent", userAgent)
        hConn.setRequestProperty("Accept", accept)

        if (includeSomeGooseOptions) {
            hConn.setRequestProperty("Accept-Language", language)
            hConn.setRequestProperty("content-charset", charset)
            hConn.addRequestProperty("Referer", referrer)
            // avoid the cache for testing purposes only?
            hConn.setRequestProperty("Cache-Control", cacheControl)
        }

        // suggest respond to be gzipped or deflated (which is just another compression)
        // http://stackoverflow.com/q/3932117
        hConn.setRequestProperty("Accept-Encoding", "gzip, deflate")
        hConn.connectTimeout = timeout
        hConn.readTimeout = timeout
        return hConn
    }

    @Throws(Exception::class)
    private fun getFromCache(url: String, originalUrl: String): JResult? {
        cache?.let {
            val res = it.get(url)
            if (res != null) {
                // e.g. the cache returned a shortened url as original url now we want to store the
                // current original url! Also it can be that the cache response to url but the JResult
                // does not contain it so overwrite it:
                res.setUrl(url)
                res.setOriginalUrl(originalUrl)
                cacheCounter.addAndGet(1)
                return res
            }
        }
        return null
    }

    companion object {
        init {
            SHelper.enableCookieMgmt()
            SHelper.enableUserAgentOverwrite()
            SHelper.enableAnySSL()
        }

        private val logger = LoggerFactory.getLogger("HtmlFetcher")

        @JvmStatic
        @Throws(Exception::class)
        fun main(args: Array<String>) {
            val reader = BufferedReader(FileReader("urls.txt"))
            var line: String?
            val existing = LinkedHashSet<String>()
            while (reader.readLine().also { line = it } != null) {
                val l = line!!
                val index1 = l.indexOf("\"")
                val index2 = l.indexOf("\"", index1 + 1)
                val url = l.substring(index1 + 1, index2)
                val domainStr = SHelper.extractDomain(url, true)
                var counterStr = ""
                // TODO more similarities
                if (existing.contains(domainStr))
                    counterStr = "2"
                else
                    existing.add(domainStr)

                val html = HtmlFetcher().fetchAsString(url, 20000)
                val outFile = domainStr + counterStr + ".html"
                val writer = BufferedWriter(FileWriter(outFile))
                writer.write(html)
                writer.close()
            }
            reader.close()
        }

        /**
         * Takes a URI that was decoded as ISO-8859-1 and applies percent-encoding
         * to non-ASCII characters. Workaround for broken origin servers that send
         * UTF-8 in the Location: header.
         */
        @JvmStatic
        fun encodeUriFromHeader(badLocation: String): String {
            val sb = StringBuilder()

            for (ch in badLocation.toCharArray()) {
                if (ch < 128.toChar()) {
                    sb.append(ch)
                } else {
                    // this is ONLY valid if the uri was decoded using ISO-8859-1
                    sb.append(String.format("%%%02X", ch.code))
                }
            }

            return sb.toString()
        }

        private fun fixUrl(url: String, urlOrPath: String): String {
            return SHelper.useDomainOfFirstArg4Second(url, urlOrPath)
        }
    }
}
