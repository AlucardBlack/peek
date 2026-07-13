/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.gecko.favicons

import android.graphics.Bitmap
import android.os.Handler
import android.text.TextUtils
import android.util.Log
import com.peek.browser.MainApplication
import org.mozilla.gecko.gfx.BitmapUtils
import org.mozilla.gecko.util.UiAsyncTask
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URISyntaxException
import java.net.URL
import java.util.LinkedList

/**
 * Class representing the asynchronous task to load a Favicon which is not currently in the in-memory
 * cache.
 * The implementation initially tries to get the Favicon from the database. Upon failure, the icon
 * is loaded from the internet.
 */
class LoadFaviconTask @JvmOverloads constructor(
        backgroundThreadHandler: Handler,
        private val mFavicons: Favicons, private val mPageUrl: String?, faviconUrl: String?, private val mFlags: Int,
        private val mListener: OnFaviconLoadedListener?, targetSize: Int = -1, fromLocal: Boolean = false
) : UiAsyncTask<Void, Void, Bitmap?>(backgroundThreadHandler) {

    private val mId: Int = mNextFaviconLoadId.incrementAndGet()
    private var mFaviconUrl: String? = faviconUrl

    private val mOnlyFromLocal: Boolean = fromLocal

    // Assuming square favicons, judging by width only is acceptable.
    private var mTargetWidth: Int = targetSize
    private var mChainees: LinkedList<LoadFaviconTask>? = null
    private var mIsChaining = false

    // Runs in background thread
    private fun loadFaviconFromDb(): Bitmap? {
        // LB_CHANGE:
        return MainApplication.sDatabaseHelper?.getFavicon(mFaviconUrl!!)
    }

    // Runs in background thread
    private fun saveFaviconToDb(favicon: Bitmap?) {
        if (mFlags and FLAG_PERSIST == 0) {
            return
        }

        // LB_CHANGE:
        MainApplication.sDatabaseHelper?.addFaviconForUrl(mFaviconUrl, favicon, mPageUrl)
    }

    /**
     * Helper method for trying the download request to grab a Favicon.
     * @param faviconURI URL of Favicon to try and download
     * @return The HttpURLConnection containing the downloaded Favicon if successful, null otherwise.
     */
    @Throws(URISyntaxException::class, IOException::class)
    private fun tryDownload(faviconURI: URI): HttpURLConnection? {
        val visitedLinkSet = HashSet<String>()
        visitedLinkSet.add(faviconURI.toString())
        return tryDownloadRecurse(faviconURI, visitedLinkSet)
    }

    @Throws(URISyntaxException::class, IOException::class)
    private fun tryDownloadRecurse(faviconURI: URI, visited: HashSet<String>): HttpURLConnection? {
        if (visited.size == MAX_REDIRECTS_TO_FOLLOW) {
            return null
        }

        val url = faviconURI.toURL()
        val connection = url.openConnection() as HttpURLConnection
        connection.instanceFollowRedirects = false
        connection.setRequestProperty("User-Agent", System.getProperty("http.agent"))
        connection.connect()

        // Was the response a failure?
        val status = connection.responseCode

        // Handle HTTP status codes requesting a redirect.
        if (status in 300..399) {
            val newURI = connection.getHeaderField("Location")
            connection.disconnect()

            // Handle mad webservers.
            if (newURI == null || newURI == faviconURI.toString()) {
                return null
            }

            if (visited.contains(newURI)) {
                // Already been redirected here - abort.
                return null
            }

            visited.add(newURI)

            // Sometimes newURI is a value like "/fb/images/favicon.ico" (with no host). In which case, ignore... See #231
            val uri = URI(newURI)
            if (uri.host != null) {
                return tryDownloadRecurse(uri, visited)
            }
            return null
        }

        if (status >= 400) {
            connection.disconnect()
            return null
        }

        return connection
    }

    // Runs in background thread.
    // Does not attempt to fetch from JARs.
    private fun downloadFavicon(targetFaviconURI: URI?): Bitmap? {
        if (targetFaviconURI == null) {
            return null
        }

        // Only get favicons for HTTP/HTTPS.
        val scheme = targetFaviconURI.scheme
        if ("http" != scheme && "https" != scheme) {
            return null
        }

        var image: Bitmap? = null

        var connection: HttpURLConnection? = null
        try {
            // Try the URL we were given.
            connection = tryDownload(targetFaviconURI)
            if (connection == null) {
                return null
            }

            val contentStream = connection.inputStream
            try {
                image = BitmapUtils.decodeStream(contentStream)
            } finally {
                contentStream.close()
            }
        } catch (e: Exception) {
            Log.e(LOGTAG, "Error reading favicon", e)
        } finally {
            connection?.disconnect()
        }

        return image
    }

    override fun doInBackground(vararg unused: Void): Bitmap? {
        if (isCancelled()) {
            return null
        }

        var storedFaviconUrl: String?
        var isUsingDefaultURL = false

        // Handle the case of malformed favicon URL.
        // If favicon is empty, fall back to the stored one.
        if (TextUtils.isEmpty(mFaviconUrl)) {
            // Try to get the favicon URL from the memory cache.
            storedFaviconUrl = mFavicons.getFaviconURLForPageURLFromCache(mPageUrl)

            // If that failed, try to get the URL from the database.
            if (storedFaviconUrl == null) {
                storedFaviconUrl = mFavicons.getFaviconUrlForPageUrl(mPageUrl)
                if (storedFaviconUrl != null) {
                    // If that succeeded, cache the URL loaded from the database in memory.
                    mFavicons.putFaviconURLForPageURLInCache(mPageUrl, storedFaviconUrl)
                }
            }

            // If we found a faviconURL - use it.
            if (storedFaviconUrl != null) {
                mFaviconUrl = storedFaviconUrl
            } else {
                // If we don't have a stored one, fall back to the default.
                mFaviconUrl = mFavicons.guessDefaultFaviconURL(mPageUrl)

                if (TextUtils.isEmpty(mFaviconUrl)) {
                    return null
                }
                isUsingDefaultURL = true
            }
        }

        // Check if favicon has failed - if so, give up. We need this check because, sometimes, we
        // didn't know the real Favicon URL until we asked the database.
        if (mFavicons.isFailedFavicon(mFaviconUrl)) {
            return null
        }

        if (isCancelled()) {
            return null
        }

        var image: Bitmap?
        // Determine if there is already an ongoing task to fetch the Favicon we desire.
        // If there is, just join the queue and wait for it to finish. If not, we carry on.
        synchronized(loadsInFlight) {
            // Another load of the current Favicon is already underway
            val existingTask = loadsInFlight[mFaviconUrl]
            if (existingTask != null && !existingTask.isCancelled()) {
                existingTask.chainTasks(this)
                mIsChaining = true

                // If we are chaining, we want to keep the first task started to do this job as the one
                // in the hashmap so subsequent tasks will add themselves to its chaining list.
                return null
            }

            // We do not want to update the hashmap if the task has chained - other tasks need to
            // chain onto the same parent task.
            loadsInFlight[mFaviconUrl] = this
        }

        if (isCancelled()) {
            return null
        }

        image = loadFaviconFromDb()
        if (imageIsValid(image)) {
            return image
        }

        if (mOnlyFromLocal || isCancelled()) {
            return null
        }

        // Let's see if it's in a JAR.
        image = fetchJARFavicon(mFaviconUrl)
        if (image != null) {
            // We don't want to put this into the DB.
            return image
        }

        try {
            image = downloadFavicon(URI(mFaviconUrl))
        } catch (e: URISyntaxException) {
            Log.e(LOGTAG, "The provided favicon URL is not valid")
            return null
        } catch (e: Exception) {
            Log.e(LOGTAG, "Couldn't download favicon.", e)
        }

        if (imageIsValid(image)) {
            saveFaviconToDb(image)
            return image
        }

        if (isUsingDefaultURL) {
            mFavicons.putFaviconInFailedCache(mFaviconUrl)
            return null
        }

        // If we're not already trying the default URL, try it now.
        val guessed = mFavicons.guessDefaultFaviconURL(mPageUrl)
        if (guessed == null) {
            mFavicons.putFaviconInFailedCache(mFaviconUrl)
            return null
        }

        image = fetchJARFavicon(guessed)
        if (imageIsValid(image)) {
            // We don't want to put this into the DB.
            return image
        }

        try {
            image = downloadFavicon(URI(guessed))
        } catch (e: Exception) {
            // Not interesting. It was an educated guess, anyway.
            return null
        }

        if (imageIsValid(image)) {
            saveFaviconToDb(image)
            return image
        }

        return null
    }

    override fun onPostExecute(image: Bitmap?) {
        if (mIsChaining) {
            return
        }

        // Put what we got in the memcache.
        mFavicons.putFaviconInMemCache(mFaviconUrl, image)

        // Process the result, scale for the listener, etc.
        processResult(image)

        synchronized(loadsInFlight) {
            // Prevent any other tasks from chaining on this one.
            loadsInFlight.remove(mFaviconUrl)
        }

        // Since any update to mChainees is done while holding the loadsInFlight lock, once we reach
        // this point no further updates to that list can possibly take place (As far as other tasks
        // are concerned, there is no longer a task to chain from. The above block will have waited
        // for any tasks that were adding themselves to the list before reaching this point.)

        // As such, I believe we're safe to do the following without holding the lock.
        // This is nice - we do not want to take the lock unless we have to anyway, and chaining rarely
        // actually happens outside of the strange situations unit tests create.

        // Share the result with all chained tasks.
        val chainees = mChainees
        if (chainees != null) {
            for (t in chainees) {
                t.processResult(image)
            }
        }
    }

    private fun processResult(image: Bitmap?) {
        mFavicons.removeLoadTask(mId)

        var scaled = image

        // Notify listeners, scaling if required.
        if (mTargetWidth != -1 && image != null && image.width != mTargetWidth) {
            scaled = mFavicons.getSizedFaviconFromCache(mFaviconUrl, mTargetWidth)
        }

        mFavicons.dispatchResult(mPageUrl, mFaviconUrl, scaled, mListener)
    }

    override fun onCancelled() {
        mFavicons.removeLoadTask(mId)

        synchronized(loadsInFlight) {
            // Only remove from the hashmap if the task there is the one that's being canceled.
            // Cancellation of a task that would have chained is not interesting to the hashmap.
            val primary = loadsInFlight[mFaviconUrl]
            if (primary === this) {
                loadsInFlight.remove(mFaviconUrl)
                return
            }
            if (primary == null) {
                // This shouldn't happen.
                return
            }
            primary.mChainees?.remove(this)
        }

        // Note that we don't call the listener callback if the
        // favicon load is cancelled.
    }

    /**
     * When the result of this job is ready, also notify the chainee of the result.
     * Used for aggregating concurrent requests for the same Favicon into a single actual request.
     * (Don't want to download a hundred instances of Google's Favicon at once, for example).
     * The loadsInFlight lock must be held when calling this function.
     *
     * @param aChainee LoadFaviconTask
     */
    private fun chainTasks(aChainee: LoadFaviconTask) {
        var chainees = mChainees
        if (chainees == null) {
            chainees = LinkedList()
            mChainees = chainees
        }

        chainees.add(aChainee)
    }

    fun getId(): Int {
        return mId
    }

    companion object {
        private const val LOGTAG = "LoadFaviconTask"

        // Access to this map needs to be synchronized prevent multiple jobs loading the same favicon
        // from executing concurrently.
        private val loadsInFlight = HashMap<String?, LoadFaviconTask>()

        const val FLAG_PERSIST = 1
        const val FLAG_SCALE = 2
        private const val MAX_REDIRECTS_TO_FOLLOW = 5

        // LB_CHANGE: change default so as to not conflict with Favicons.LOADED
        private val mNextFaviconLoadId = java.util.concurrent.atomic.AtomicInteger(111)

        /**
         * Retrieve the specified favicon from the JAR, returning null if it's not
         * a JAR URI.
         */
        private fun fetchJARFavicon(uri: String?): Bitmap? {
            if (uri == null) {
                return null
            }
            /* LB_CHANGE:
            if (uri.startsWith("jar:jar:")) {
                Log.d(LOGTAG, "Fetching favicon from JAR.");
                try {
                    return GeckoJarReader.getBitmap(sContext.getResources(), uri);
                } catch (Exception e) {
                    // Just about anything could happen here.
                    Log.w(LOGTAG, "Error fetching favicon from JAR.", e);
                    return null;
                }
            }*/
            return null
        }

        private fun imageIsValid(image: Bitmap?): Boolean {
            return image != null &&
                    image.width > 0 &&
                    image.height > 0
        }

        @JvmStatic
        fun closeHTTPClient() {
            // No-op: HttpURLConnection has no persistent connection pool to close.
        }
    }
}
