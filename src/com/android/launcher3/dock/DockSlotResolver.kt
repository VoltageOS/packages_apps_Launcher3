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

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.pm.LauncherApps
import android.os.Process
import android.os.SystemClock
import android.util.Log
import com.android.launcher3.model.data.AppInfo
import com.android.launcher3.model.data.ItemInfoWithIcon
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class DockSlotResolver(private val context: Context) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private var cachedSuggestions: List<AppInfo> = emptyList()
    private var lastQueryTime = 0L
    private var pinnedPackagesByRank: List<String?> = emptyList()
    private var occupiedRanks: Set<Int> = emptySet()
    private var totalSlots = 4
    private var foregroundPackage: String? = null
    private var mode = DockSuggestionMode.SMART
    private var debounceJob: Job? = null
    private var lastShownSuggestionPackages: Set<String> = emptySet()

    private val _slots = MutableStateFlow<List<DockSlot>>(emptyList())
    val slots: StateFlow<List<DockSlot>> = _slots.asStateFlow()

    fun update(
        pinnedPackagesByRank: List<String?>,
        occupiedRanks: Set<Int>,
        total: Int,
        mode: DockSuggestionMode,
        foreground: String? = null,
    ) {
        this.pinnedPackagesByRank = pinnedPackagesByRank
        this.occupiedRanks = occupiedRanks
        totalSlots = total
        this.mode = mode
        foregroundPackage = foreground
        scheduleRefresh()
    }

    fun onAppRemoved(packageName: String) {
        cachedSuggestions = cachedSuggestions.filter { it.componentName?.packageName != packageName }
        lastShownSuggestionPackages = lastShownSuggestionPackages - packageName
        publish()
    }

    fun onPermissionRevoked() {
        cachedSuggestions = emptyList()
        lastShownSuggestionPackages = emptySet()
        publish()
    }

    fun invalidateCache() {
        lastQueryTime = 0L
    }

    fun cancelPendingRefresh() {
        debounceJob?.cancel()
        debounceJob = null
    }

    fun destroy() {
        cancelPendingRefresh()
        scope.coroutineContext[Job]?.cancel()
    }

    private fun scheduleRefresh() {
        debounceJob?.cancel()
        debounceJob = scope.launch {
            delay(DEBOUNCE_MS)
            maybeRefresh()
            publish()
        }
    }

    private suspend fun maybeRefresh() {
        if (!mode.isEnabled) {
            cachedSuggestions = emptyList()
            return
        }
        val elapsed = SystemClock.elapsedRealtime() - lastQueryTime
        if (elapsed < CACHE_TTL_MS && cachedSuggestions.isNotEmpty()) return
        if (!DockSuggestionsHelper.hasUsageStatsPermission(context)) {
            cachedSuggestions = emptyList()
            return
        }
        val request =
            SuggestionQuery(
                pinnedPackages = pinnedPackagesByRank.presentPackages(),
                mode = mode,
                foregroundPackage = foregroundPackage,
                stablePackages = lastShownSuggestionPackages,
                boostedPackages = boostedPackagesFor(mode),
            )
        cachedSuggestions = withContext(Dispatchers.IO) { querySuggestions(request) }
        lastQueryTime = SystemClock.elapsedRealtime()
    }

    private fun publish() {
        val resolvedSlots =
            resolve(
                pinnedPackagesByRank = pinnedPackagesByRank,
                occupiedRanks = occupiedRanks,
                suggestions = cachedSuggestions,
                total = totalSlots,
                foreground = foregroundPackage,
            )
        lastShownSuggestionPackages =
            resolvedSlots.mapNotNull { (it as? DockSlot.Suggested)?.app?.componentName?.packageName }.toSet()
        _slots.value = resolvedSlots
    }

    internal fun resolve(
        pinnedPackagesByRank: List<String?>,
        occupiedRanks: Set<Int>,
        suggestions: List<AppInfo>,
        total: Int,
        foreground: String? = null,
    ): List<DockSlot> {
        val result = MutableList<DockSlot>(total) { DockSlot.Empty }
        val usedPackages = pinnedPackagesByRank.presentPackages().toMutableSet().apply {
            foreground?.let(::add)
        }
        var nextSuggestionIndex = 0
        for (rank in 0 until total) {
            val pinnedPackage = pinnedPackagesByRank.getOrNull(rank)
            if (!pinnedPackage.isNullOrEmpty()) {
                result[rank] = DockSlot.Pinned
                continue
            }
            if (rank in occupiedRanks) {
                result[rank] = DockSlot.Blocked
                continue
            }
            while (nextSuggestionIndex < suggestions.size) {
                val app = suggestions[nextSuggestionIndex++]
                val pkg = app.componentName?.packageName ?: continue
                if (DockSuggestionsHelper.isPackageSuppressed(context, pkg)) continue
                if (pkg in usedPackages) continue
                result[rank] = DockSlot.Suggested(app)
                usedPackages.add(pkg)
                break
            }
        }
        return result
    }

    private fun querySuggestions(request: SuggestionQuery): List<AppInfo> {
        return try {
            val usageStatsManager = context.getSystemService(UsageStatsManager::class.java)
                ?: return emptyList()
            val launcherApps = context.getSystemService(LauncherApps::class.java) ?: return emptyList()
            val now = System.currentTimeMillis()
            val candidates = queryUsageCandidates(usageStatsManager, now)
            val rankedCandidates =
                DockSuggestionRanker.sort(
                    candidates = candidates,
                    mode = request.mode,
                    stablePackages = request.stablePackages,
                    boostedPackages = request.boostedPackages,
                    now = now,
                )
            buildSuggestions(
                launcherApps = launcherApps,
                user = Process.myUserHandle(),
                rankedCandidates = rankedCandidates,
                excludedPackages = buildExcludedPackages(request),
            )
        } catch (e: Exception) {
            Log.e(TAG, "querySuggestions failed", e)
            emptyList()
        }
    }

    private fun queryUsageCandidates(
        usageStatsManager: UsageStatsManager,
        now: Long,
    ): List<DockUsageCandidate> {
        val usageByPackage = linkedMapOf<String, MutableUsageStats>()
        val events = usageStatsManager.queryEvents(now - WINDOW_MS, now)
        val event = UsageEvents.Event()
        while (events != null && events.hasNextEvent()) {
            events.getNextEvent(event)
            val pkg = event.packageName ?: continue
            if (pkg == context.packageName) continue
            val stats = usageByPackage.getOrPut(pkg) { MutableUsageStats() }
            when (event.eventType) {
                UsageEvents.Event.ACTIVITY_RESUMED -> {
                    stats.launchCount++
                    stats.lastUsedTime = maxOf(stats.lastUsedTime, event.timeStamp)
                }
                UsageEvents.Event.ACTIVITY_PAUSED,
                UsageEvents.Event.ACTIVITY_STOPPED -> {
                    stats.lastUsedTime = maxOf(stats.lastUsedTime, event.timeStamp)
                }
            }
        }
        return usageByPackage.map { (pkg, stats) ->
            DockUsageCandidate(pkg, stats.launchCount, stats.lastUsedTime)
        }
    }

    private fun buildSuggestions(
        launcherApps: LauncherApps,
        user: android.os.UserHandle,
        rankedCandidates: List<DockUsageCandidate>,
        excludedPackages: MutableSet<String>,
    ): List<AppInfo> {
        val suggestions = mutableListOf<AppInfo>()
        for (candidate in rankedCandidates) {
            val packageName = candidate.packageName
            if (packageName in excludedPackages || packageName in PACKAGE_DENYLIST) {
                continue
            }
            if (DockSuggestionsHelper.isPackageSuppressed(context, packageName)) {
                continue
            }

            val suggestion = loadSuggestionApp(launcherApps, packageName, user) ?: continue
            excludedPackages.add(packageName)
            suggestions.add(suggestion)
        }
        return suggestions
    }

    private fun buildExcludedPackages(request: SuggestionQuery): MutableSet<String> =
        request.pinnedPackages.toMutableSet().apply {
            add(context.packageName)
            request.foregroundPackage?.let(::add)
        }

    private fun boostedPackagesFor(mode: DockSuggestionMode): Set<String> {
        if (mode != DockSuggestionMode.SMART) {
            return emptySet()
        }
        return DockSuggestionsHelper.getBoostedPackage(context)?.let(::setOf) ?: emptySet()
    }

    private fun loadSuggestionApp(
        launcherApps: LauncherApps,
        packageName: String,
        user: android.os.UserHandle,
    ): AppInfo? {
        return try {
            val activity = launcherApps.getActivityList(packageName, user).firstOrNull() ?: return null
            AppInfo(context, activity, user).takeIf { isEligibleSuggestion(packageName, it) }
        } catch (e: Exception) {
            Log.w(TAG, "Skipping ineligible smart dock package: $packageName", e)
            null
        }
    }

    private fun isEligibleSuggestion(packageName: String, app: AppInfo): Boolean {
        return when {
            (app.runtimeStatusFlags and ItemInfoWithIcon.FLAG_NOT_PINNABLE) != 0 -> false
            (app.runtimeStatusFlags and ItemInfoWithIcon.FLAG_DISABLED_SUSPENDED) != 0 -> false
            app.isDisabled -> false
            packageName in PACKAGE_DENYLIST -> false
            else -> true
        }
    }

    private class MutableUsageStats {
        var launchCount: Int = 0
        var lastUsedTime: Long = 0L
    }

    private data class SuggestionQuery(
        val pinnedPackages: Set<String>,
        val mode: DockSuggestionMode,
        val foregroundPackage: String?,
        val stablePackages: Set<String>,
        val boostedPackages: Set<String>,
    )

    companion object {
        private const val TAG = "DockSlotResolver"
        private const val WINDOW_MS = 7L * 24 * 60 * 60 * 1_000
        private const val CACHE_TTL_MS = 8_000L
        private const val DEBOUNCE_MS = 120L
        private val PACKAGE_DENYLIST = setOf(
            "com.android.settings",
            "com.android.packageinstaller",
            "com.google.android.packageinstaller",
            "com.android.permissioncontroller",
            "com.google.android.permissioncontroller",
            "com.google.android.setupwizard",
            "com.android.managedprovisioning",
            "com.android.provision",
        )
    }
}

private fun List<String?>.presentPackages(): Set<String> =
    filterNotNull().filter { it.isNotEmpty() }.toSet()
