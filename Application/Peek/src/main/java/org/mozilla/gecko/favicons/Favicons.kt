/* -*- Mode: Java; c-basic-offset: 4; tab-width: 20; indent-tabs-mode: nil; -*-
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.gecko.favicons

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.text.TextUtils
import android.util.Log
import com.peek.browser.R
import com.peek.browser.util.NetworkConnectivity
import org.mozilla.gecko.favicons.cache.FaviconCache
import org.mozilla.gecko.util.NonEvictingLruCache
import org.mozilla.gecko.util.ThreadUtils
import java.net.URI
import java.net.URISyntaxException
import java.util.Collections

class Favicons @JvmOverloads constructor(cacheSize: Int = FAVICON_CACHE_SIZE_BYTES) {

    @SuppressLint("UseSparseArrays")
    private val mLoadTasks: MutableMap<Int, LoadFaviconTask> = Collections.synchronizedMap(HashMap())

    // Cache to hold mappings between page URLs and Favicon URLs. Used to avoid going to the DB when
    // doing so is not necessary.
    private val mPageURLMappings = NonEvictingLruCache<String, String>(NUM_PAGE_URL_MAPPINGS_TO_STORE)

    fun getFaviconURLForPageURLFromCache(pageURL: String?): String? {
        return mPageURLMappings.get(pageURL!!)
    }

    /**
     * Insert the given pageUrl->faviconUrl mapping into the memory cache of such mappings.
     * Useful for short-circuiting local database access.
     */
    fun putFaviconURLForPageURLInCache(pageURL: String?, faviconURL: String) {
        mPageURLMappings.put(pageURL!!, faviconURL)
    }

    private val mFaviconsCache: FaviconCache

    init {
        val res = sContext!!.resources
        mFaviconsCache = FaviconCache(cacheSize, res.getDimensionPixelSize(R.dimen.favicon_largest_interesting_size))

        // Initialize page mappings for each of our special pages.
        // LB_CHANGE:
        //for (String url : AboutPages.getDefaultIconPages()) {
        //    mPageURLMappings.putWithoutEviction(url, BUILT_IN_FAVICON_URL);
        //}

        // Load and cache the built-in favicon in each of its sizes.
        // TODO: don't open the zip twice!
        /* LB_CHANGE:
        ArrayList<Bitmap> toInsert = new ArrayList<Bitmap>(2);
        toInsert.add(loadBrandingBitmap(context, "favicon64.png"));
        toInsert.add(loadBrandingBitmap(context, "favicon32.png"));
        putFaviconsInMemCache(BUILT_IN_FAVICON_URL, toInsert.iterator(), true);
        */
    }

    /**
     * Returns either NOT_LOADING, or LOADED if the onFaviconLoaded call could
     * be made on the main thread.
     * If no listener is provided, NOT_LOADING is returned.
     */
    fun dispatchResult(pageUrl: String?, faviconURL: String?, image: Bitmap?,
                        listener: OnFaviconLoadedListener?): Int {
        if (listener == null) {
            return NOT_LOADING
        }

        /* LB_CHANGE
        if (ThreadUtils.isOnUiThread()) {
            listener.onFaviconLoaded(pageUrl, faviconURL, image);
            return LOADED;
        }

        // We want to always run the listener on UI thread.
        ThreadUtils.postToUiThread(new Runnable() {
            @Override
            public void run() {
                listener.onFaviconLoaded(pageUrl, faviconURL, image);
            }
        });
        */
        listener.onFaviconLoaded(pageUrl, faviconURL, image)
        return NOT_LOADING
    }

    /**
     * Only returns a non-null Bitmap if the entire path is cached -- the
     * page URL to favicon URL, and the favicon URL to in-memory bitmaps.
     *
     * Returns null otherwise.
     */
    fun getCachedFaviconForSize(pageURL: String, targetSize: Int): Bitmap? {
        val faviconURL = mPageURLMappings.get(pageURL) ?: return null
        return getSizedFaviconFromCache(faviconURL, targetSize)
    }

    /**
     * Get a Favicon as close as possible to the target dimensions for the URL provided.
     * If a result is instantly available from the cache, it is returned and the listener is invoked.
     * Otherwise, the result is drawn from the database or network and the listener invoked when the
     * result becomes available.
     *
     * @param pageURL Page URL for which a Favicon is desired.
     * @param faviconURL URL of the Favicon to be downloaded, if known. If none provided, an educated
     *                    guess is made by the system.
     * @param targetSize Target size of the returned Favicon
     * @param listener Listener to call with the result of the load operation, if the result is not
     *                  immediately available.
     * @return The id of the asynchronous task created, NOT_LOADING if none is created, or
     *         LOADED if the value could be dispatched on the current thread.
     */
    fun getFaviconForSize(pageURL: String, faviconURL: String?, targetSize: Int, flags: Int, listener: OnFaviconLoadedListener?): Int {
        // Do we know the favicon URL for this page already?
        var cacheURL = faviconURL
        if (cacheURL == null) {
            cacheURL = mPageURLMappings.get(pageURL)
        }

        // If there's no favicon URL given, try and hit the cache with the default one.
        if (cacheURL == null) {
            cacheURL = guessDefaultFaviconURL(pageURL)
        }

        // If it's something we can't even figure out a default URL for, just give up.
        if (cacheURL == null) {
            return dispatchResult(pageURL, null, sDefaultFavicon, listener)
        }

        // If the device is offline, display the default favicon until the page reloads.
        if (flags and FLAG_OFFLINE_NO_CACHE != 0 && !NetworkConnectivity.isConnected(sContext!!)) {
            return dispatchResult(pageURL, null, sDefaultFavicon, listener)
        }

        val cachedIcon = getSizedFaviconFromCache(cacheURL, targetSize)
        if (cachedIcon != null) {
            return dispatchResult(pageURL, cacheURL, cachedIcon, listener)
        }

        // Check if favicon has failed.
        if (mFaviconsCache.isFailedFavicon(cacheURL)) {
            return dispatchResult(pageURL, cacheURL, sDefaultFavicon, listener)
        }

        // Failing that, try and get one from the database or internet.
        return loadUncachedFavicon(pageURL, faviconURL, flags, targetSize, listener)
    }

    /**
     * Returns the cached Favicon closest to the target size if any exists or is coercible. Returns
     * null otherwise. Does not query the database or network for the Favicon is the result is not
     * immediately available.
     *
     * @param faviconURL URL of the Favicon to query for.
     * @param targetSize The desired size of the returned Favicon.
     * @return The cached Favicon, rescaled to be as close as possible to the target size, if any exists.
     *         null if no applicable Favicon exists in the cache.
     */
    fun getSizedFaviconFromCache(faviconURL: String?, targetSize: Int): Bitmap? {
        return mFaviconsCache.getFaviconForDimensions(faviconURL, targetSize)
    }

    /**
     * Attempts to find a Favicon for the provided page URL from either the mem cache or the database.
     * Does not need an explicit favicon URL, since, as we are accessing the database anyway, we
     * can query the history DB for the Favicon URL.
     * Handy for easing the transition from caching with page URLs to caching with Favicon URLs.
     *
     * A null result is passed to the listener if no value is locally available. The Favicon is not
     * added to the failure cache.
     *
     * @param pageURL Page URL for which a Favicon is wanted.
     * @param targetSize Target size of the desired Favicon to pass to the cache query
     * @param callback Callback to fire with the result.
     * @return The job ID of the spawned async task, if any.
     */
    fun getSizedFaviconForPageFromLocal(pageURL: String, callback: OnFaviconLoadedListener?): Int {
        return getSizedFaviconForPageFromLocal(pageURL, sDefaultFaviconSize, callback)
    }

    fun getSizedFaviconForPageFromLocal(pageURL: String, targetSize: Int, callback: OnFaviconLoadedListener?): Int {
        // Firstly, try extremely hard to cheat.
        // Have we cached this favicon URL? If we did, we can consult the memcache right away.
        val targetURL = mPageURLMappings.get(pageURL)
        if (targetURL != null) {
            // Check if favicon has failed.
            if (mFaviconsCache.isFailedFavicon(targetURL)) {
                return dispatchResult(pageURL, targetURL, null, callback)
            }

            // Do we have a Favicon in the cache for this favicon URL?
            val result = getSizedFaviconFromCache(targetURL, targetSize)
            if (result != null) {
                // Victory - immediate response!
                return dispatchResult(pageURL, targetURL, result, callback)
            }
        }

        // No joy using in-memory resources. Go to background thread and ask the database.
        val task = LoadFaviconTask(ThreadUtils.getBackgroundHandler()!!, this, pageURL, targetURL, 0, callback, targetSize, true)
        val taskId = task.getId()
        mLoadTasks[taskId] = task
        task.execute()
        return taskId
    }

    /**
     * Helper method to determine the URL of the Favicon image for a given page URL by querying the
     * history database. Should only be called from the background thread - does database access.
     *
     * @param pageURL The URL of a webpage with a Favicon.
     * @return The URL of the Favicon used by that webpage, according to either the History database
     *         or a somewhat educated guess.
     */
    fun getFaviconUrlForPageUrl(pageURL: String?): String? {
        // Attempt to determine the Favicon URL from the Tabs datastructure. Can dodge having to use
        // the database sometimes by doing this.
        var targetURL: String? = null
        /* LB_CHANGE:
        Tab theTab = Tabs.getInstance().getTabForUrl(pageURL);
        if (theTab != null) {
            targetURL = theTab.getFaviconURL();
            if (targetURL != null) {
                return targetURL;
            }
        }

        targetURL = BrowserDB.getFaviconUrlForHistoryUrl(sContext.getContentResolver(), pageURL);
        */
        if (targetURL == null) {
            // Nothing in the history database. Fall back to the default URL and hope for the best.
            targetURL = guessDefaultFaviconURL(pageURL)
        }
        return targetURL
    }

    /**
     * Helper function to create an async job to load a Favicon which does not exist in the memcache.
     * Contains logic to prevent the repeated loading of Favicons which have previously failed.
     * There is no support for recovery from transient failures.
     *
     * @param pageUrl URL of the page for which to load a Favicon. If null, no job is created.
     * @param faviconUrl The URL of the Favicon to load. If null, an attempt to infer the value from
     *                   the history database will be made, and ultimately an attempt to guess will
     *                   be made.
     * @param flags Flags to be used by the LoadFaviconTask while loading. Currently only one flag
     *              is supported, LoadFaviconTask.FLAG_PERSIST.
     *              If FLAG_PERSIST is set and the Favicon is ultimately loaded from the internet,
     *              the downloaded Favicon is subsequently stored in the local database.
     *              If FLAG_PERSIST is unset, the downloaded Favicon is stored only in the memcache.
     *              FLAG_PERSIST has no effect on loads which come from the database.
     * @param listener The OnFaviconLoadedListener to invoke with the result of this Favicon load.
     * @return The id of the LoadFaviconTask handling this job.
     */
    private fun loadUncachedFavicon(pageUrl: String?, faviconUrl: String?, flags: Int, targetSize: Int, listener: OnFaviconLoadedListener?): Int {
        // Handle the case where we have no page url.
        if (TextUtils.isEmpty(pageUrl)) {
            dispatchResult(null, null, null, listener)
            return NOT_LOADING
        }

        val task = LoadFaviconTask(ThreadUtils.getBackgroundHandler()!!, this, pageUrl, faviconUrl, flags, listener, targetSize, false)

        val taskId = task.getId()
        mLoadTasks[taskId] = task

        task.execute()

        return taskId
    }

    fun putFaviconInMemCache(pageUrl: String?, image: Bitmap?) {
        if (pageUrl == null || image == null) {
            return
        }
        mFaviconsCache.putSingleFavicon(pageUrl, image)
    }

    fun putFaviconsInMemCache(pageUrl: String, images: MutableIterator<Bitmap>, permanently: Boolean) {
        mFaviconsCache.putFavicons(pageUrl, images, permanently)
    }

    fun clearMemCache() {
        mFaviconsCache.evictAll()
        mPageURLMappings.evictAll()
    }

    fun putFaviconInFailedCache(faviconURL: String?) {
        mFaviconsCache.putFailed(faviconURL!!)
    }

    fun cancelFaviconLoad(taskId: Int): Boolean {
        if (taskId == NOT_LOADING) {
            return false
        }

        val cancelled: Boolean
        synchronized(mLoadTasks) {
            if (!mLoadTasks.containsKey(taskId))
                return false

            Log.d(LOGTAG, "Cancelling favicon load ($taskId)")

            val task = mLoadTasks[taskId]
            cancelled = task!!.cancel(false)
        }
        return cancelled
    }

    fun close() {
        Log.d(LOGTAG, "Closing Favicons database")

        // Cancel any pending tasks
        synchronized(mLoadTasks) {
            val taskIds = mLoadTasks.keys
            val iter = taskIds.iterator()
            while (iter.hasNext()) {
                val taskId = iter.next()
                cancelFaviconLoad(taskId)
            }
            mLoadTasks.clear()
        }

        LoadFaviconTask.closeHTTPClient()
    }

    /**
     * Get the dominant colour of the Favicon at the URL given, if any exists in the cache.
     *
     * @param url The URL of the Favicon, to be used as the cache key for the colour value.
     * @return The dominant colour of the provided Favicon.
     */
    fun getFaviconColor(url: String?): Int {
        return mFaviconsCache.getDominantColor(url!!)
    }

    /**
     * Helper method to get the default Favicon URL for a given pageURL. Generally: somewhere.com/favicon.ico
     *
     * @param pageURL Page URL for which a default Favicon URL is requested
     * @return The default Favicon URL.
     */
    fun guessDefaultFaviconURL(pageURL: String?): String? {
        // Special-casing for about: pages. The favicon for about:pages which don't provide a link tag
        // is bundled in the database, keyed only by page URL, hence the need to return the page URL
        // here. If the database ever migrates to stop being silly in this way, this can plausibly
        // be removed.
        /* LB_CHANGE:
        if (AboutPages.isAboutPage(pageURL) || pageURL.startsWith("jar:")) {
            return pageURL;
        } */

        try {
            // Fall back to trying "someScheme:someDomain.someExtension/favicon.ico".
            val u = URI(pageURL)
            return URI(u.scheme,
                    u.authority,
                    "/favicon.ico", null,
                    null).toString()
        } catch (e: URISyntaxException) {
            Log.e(LOGTAG, "URISyntaxException getting default favicon URL", e)
            return null
        }
    }

    fun removeLoadTask(taskId: Int) {
        mLoadTasks.remove(taskId)
    }

    /**
     * Method to wrap FaviconCache.isFailedFavicon for use by LoadFaviconTask.
     *
     * @param faviconURL Favicon URL to check for failure.
     */
    fun isFailedFavicon(faviconURL: String?): Boolean {
        return mFaviconsCache.isFailedFavicon(faviconURL)
    }

    /**
     * Sidestep the cache and get, from either the database or the internet, the largest available
     * Favicon for the given page URL. Useful for creating homescreen shortcuts without being limited
     * by possibly low-resolution values in the cache.
     * Deduces the favicon URL from the history database and, ultimately, guesses.
     *
     * @param url Page URL to get a large favicon image fro.
     * @param onFaviconLoadedListener Listener to call back with the result.
     */
    fun getLargestFaviconForPage(url: String, onFaviconLoadedListener: OnFaviconLoadedListener?) {
        loadUncachedFavicon(url, null, 0, -1, onFaviconLoadedListener)
    }

    companion object {
        private const val LOGTAG = "GeckoFavicons"

        // A magic URL representing the app's own favicon, used for about: pages.
        //private static final String BUILT_IN_FAVICON_URL = "about:favicon";

        // Size of the favicon bitmap cache, in bytes (Counting payload only).
        const val FAVICON_CACHE_SIZE_BYTES = 512 * 1024

        // Number of URL mappings from page URL to Favicon URL to cache in memory.
        const val NUM_PAGE_URL_MAPPINGS_TO_STORE = 128

        const val NOT_LOADING = 0
        const val LOADED = 1
        const val FLAG_PERSIST = 2
        const val FLAG_SCALE = 4
        const val FLAG_OFFLINE_NO_CACHE = 8

        @JvmField
        var sContext: Context? = null

        // The default Favicon to show if no other can be found.
        @JvmField
        var sDefaultFavicon: Bitmap? = null

        // The density-adjusted default Favicon dimensions.
        @JvmField
        var sDefaultFaviconSize: Int = 0

        /**
         * Called by GeckoApp on startup to pass this class a reference to the GeckoApp object used as
         * the application's Context.
         * Consider replacing with references to a staticly held reference to the GeckoApp object.
         *
         * @param context A reference to the GeckoApp instance.
         */
        @JvmStatic
        fun attachToContext(context: Context) {
            val res = context.resources
            sContext = context

            // Decode the default Favicon ready for use.
            sDefaultFavicon = BitmapFactory.decodeResource(res, R.drawable.fallback_favicon)

            sDefaultFaviconSize = res.getDimensionPixelSize(R.dimen.favicon_bg)
        }
    }
}
