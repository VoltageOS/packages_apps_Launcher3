/*
 * Copyright (C) 2026 VoltageOS
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

package com.android.launcher3.dock

import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.provider.Settings
import com.android.launcher3.LauncherPrefs
import com.android.launcher3.util.MSMHProxy

object DockSuggestionsHelper {

    private val hiddenPackagesForSession = linkedSetOf<String>()

    @JvmStatic
    fun isFeatureEnabled(context: Context): Boolean =
        getSuggestionMode(context).isEnabled

    @JvmStatic
    fun getSuggestionMode(context: Context): DockSuggestionMode {
        val prefs = LauncherPrefs.getPrefs(context)
        val modeKey = LauncherPrefs.DOCK_SUGGESTION_MODE.sharedPrefKey
        val storedMode = prefs.getString(modeKey, null)
        if (storedMode != null) {
            return DockSuggestionMode.fromPrefValue(storedMode)
        }

        val legacyFillKey = LauncherPrefs.DOCK_RECENTS_FILL.sharedPrefKey
        val legacyMostUsedKey = LauncherPrefs.DOCK_RECENTS_SHOW_MOST_USED.sharedPrefKey
        val mode =
            if (prefs.contains(legacyFillKey) || prefs.contains(legacyMostUsedKey)) {
                DockSuggestionMode.fromLegacy(
                    fillEnabled = prefs.getBoolean(legacyFillKey, false),
                    showMostUsed = prefs.getBoolean(legacyMostUsedKey, false),
                )
            } else {
                DockSuggestionMode.SMART
            }
        prefs.edit().putString(modeKey, mode.prefValue).apply()
        return mode
    }

    /**
     * Detects usage-stats access by attempting an actual query rather than relying on AppOps,
     * which returns MODE_DEFAULT for privileged apps even when permission is granted.
     */
    @JvmStatic
    fun hasUsageStatsPermission(context: Context): Boolean {
        return try {
            val usm = context.getSystemService(UsageStatsManager::class.java) ?: return false
            val now = System.currentTimeMillis()
            usm.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, now - 60_000L, now) != null
        } catch (_: SecurityException) {
            false
        }
    }

    @JvmStatic
    fun usageAccessSettingsIntent(): Intent = Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)

    @JvmStatic
    fun needsOnboarding(context: Context): Boolean =
        isFeatureEnabled(context) && !hasUsageStatsPermission(context)

    @JvmStatic
    fun getBoostedPackage(context: Context): String? {
        if (!LauncherPrefs.SHOW_QUICKSPACE_NOWPLAYING.get(context)) {
            return null
        }

        return MSMHProxy.INSTANCE(context).getActiveMediaPackageName()
    }

    @JvmStatic
    fun hidePackageForNow(packageName: String) {
        synchronized(hiddenPackagesForSession) {
            hiddenPackagesForSession.add(packageName)
        }
    }

    @JvmStatic
    fun showPackageAgain(packageName: String) {
        synchronized(hiddenPackagesForSession) {
            hiddenPackagesForSession.remove(packageName)
        }
    }

    @JvmStatic
    fun blockPackage(context: Context, packageName: String) {
        showPackageAgain(packageName)
        val blockedPackages = getBlockedPackages(context).toMutableSet()
        if (blockedPackages.add(packageName)) {
            LauncherPrefs.get(context).put(LauncherPrefs.DOCK_SUGGESTION_DENYLIST, blockedPackages)
        }
    }

    @JvmStatic
    fun unblockPackage(context: Context, packageName: String) {
        val blockedPackages = getBlockedPackages(context).toMutableSet()
        if (blockedPackages.remove(packageName)) {
            LauncherPrefs.get(context).put(LauncherPrefs.DOCK_SUGGESTION_DENYLIST, blockedPackages)
        }
    }

    @JvmStatic
    fun isPackageSuppressed(context: Context, packageName: String): Boolean =
        isPackageBlocked(context, packageName) || isPackageHiddenForSession(packageName)

    @JvmStatic
    fun isPackageBlocked(context: Context, packageName: String): Boolean =
        packageName in getBlockedPackages(context)

    @JvmStatic
    fun isPackageHiddenForSession(packageName: String): Boolean =
        synchronized(hiddenPackagesForSession) {
            packageName in hiddenPackagesForSession
        }

    private fun getBlockedPackages(context: Context): Set<String> =
        LauncherPrefs.get(context).get(LauncherPrefs.DOCK_SUGGESTION_DENYLIST)
}
