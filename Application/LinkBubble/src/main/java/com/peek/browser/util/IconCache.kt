/*
 * Copyright (C) 2008 The Android Open Source Project
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
 */

package com.peek.browser.util

import android.app.ActivityManager
import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Drawable

/**
 * Cache of application icons.  Icons can be made from any thread.
 */
class IconCache(context: Application) {

    private class CacheEntry {
        var icon: Bitmap? = null
        var title: String? = null
    }

    private val mDefaultIcon: Bitmap
    private val mContext: Application = context
    private val mPackageManager: PackageManager = context.packageManager
    private val mCache = HashMap<ComponentName, CacheEntry>(INITIAL_ICON_CACHE_CAPACITY)
    private var mIconDpi: Int

    init {
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        mIconDpi = activityManager.launcherLargeIconDensity

        // need to set mIconDpi before getting default icon
        mDefaultIcon = makeDefaultIcon()
    }

    fun getFullResDefaultActivityIcon(): Drawable {
        return getFullResIcon(Resources.getSystem(), android.R.mipmap.sym_def_app_icon)
    }

    fun getFullResIcon(resources: Resources, iconId: Int): Drawable {
        val d: Drawable? = try {
            resources.getDrawableForDensity(iconId, mIconDpi)
        } catch (e: Resources.NotFoundException) {
            null
        }

        return d ?: getFullResDefaultActivityIcon()
    }

    fun getFullResIcon(packageName: String, iconId: Int): Drawable {
        val resources: Resources? = try {
            mPackageManager.getResourcesForApplication(packageName)
        } catch (e: PackageManager.NameNotFoundException) {
            null
        }
        if (resources != null) {
            if (iconId != 0) {
                return getFullResIcon(resources, iconId)
            }
        }
        return getFullResDefaultActivityIcon()
    }

    fun getFullResIcon(info: ResolveInfo): Drawable {
        return getFullResIcon(info.activityInfo)
    }

    fun getFullResIcon(info: ActivityInfo): Drawable {
        val resources: Resources? = try {
            mPackageManager.getResourcesForApplication(info.applicationInfo)
        } catch (e: PackageManager.NameNotFoundException) {
            null
        }
        if (resources != null) {
            val iconId = info.iconResource
            if (iconId != 0) {
                return getFullResIcon(resources, iconId)
            }
        }
        return getFullResDefaultActivityIcon()
    }

    private fun makeDefaultIcon(): Bitmap {
        val d = getFullResDefaultActivityIcon()
        val b = Bitmap.createBitmap(maxOf(d.intrinsicWidth, 1),
                maxOf(d.intrinsicHeight, 1),
                Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        d.setBounds(0, 0, b.width, b.height)
        d.draw(c)
        c.setBitmap(null)
        return b
    }

    /**
     * Remove any records for the supplied ComponentName.
     */
    fun remove(componentName: ComponentName) {
        synchronized(mCache) {
            mCache.remove(componentName)
        }
    }

    /**
     * Empty out the cache.
     */
    fun flush() {
        synchronized(mCache) {
            mCache.clear()
        }
    }

    fun getIcon(intent: Intent): Bitmap {
        synchronized(mCache) {
            val resolveInfo = mPackageManager.resolveActivity(intent, 0)
            val component = intent.component

            if (resolveInfo == null || component == null) {
                return mDefaultIcon
            }

            val entry = cacheLocked(component, resolveInfo, null)
            return entry.icon ?: mDefaultIcon
        }
    }

    fun getIcon(component: ComponentName?, resolveInfo: ResolveInfo?,
                labelCache: HashMap<Any, CharSequence>?): Bitmap? {
        synchronized(mCache) {
            if (resolveInfo == null || component == null) {
                return null
            }

            val entry = cacheLocked(component, resolveInfo, labelCache)
            return entry.icon
        }
    }

    fun isDefaultIcon(icon: Bitmap): Boolean {
        return mDefaultIcon === icon
    }

    private fun cacheLocked(componentName: ComponentName, info: ResolveInfo,
                             labelCache: HashMap<Any, CharSequence>?): CacheEntry {
        var entry = mCache[componentName]
        if (entry == null) {
            entry = CacheEntry()

            mCache[componentName] = entry

            val key = Util.getComponentNameFromResolveInfo(info)
            if (labelCache != null && labelCache.containsKey(key)) {
                entry.title = labelCache[key].toString()
            } else {
                entry.title = info.loadLabel(mPackageManager).toString()
                if (labelCache != null) {
                    entry.title?.let { labelCache[key] = it }
                }
            }
            if (entry.title == null) {
                entry.title = info.activityInfo.name
            }

            entry.icon = Util.createIconBitmap(getFullResIcon(info), mContext)
        }
        return entry
    }

    fun getAllIcons(): HashMap<ComponentName, Bitmap?> {
        synchronized(mCache) {
            val set = HashMap<ComponentName, Bitmap?>()
            for (cn in mCache.keys) {
                val e = mCache[cn]
                set[cn] = e?.icon
            }
            return set
        }
    }

    companion object {
        private const val INITIAL_ICON_CACHE_CAPACITY = 50
    }
}
