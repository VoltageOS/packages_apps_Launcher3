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

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.pm.LauncherApps
import android.os.SystemClock
import android.os.UserHandle
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach

class DockSuggestionsController(private val context: Context) {

    interface Listener {
        fun onDockSlotsChanged(slots: List<DockSlot>)
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val resolver = DockSlotResolver(context)
    private var listener: Listener? = null

    private var lastTotal = 4
    private var lastPinnedPackagesByRank: List<String?> = emptyList()
    private var lastOccupiedRanks: Set<Int> = emptySet()
    private var lastPublishedSlots: List<DockSlot> = emptyList()
    private var isLauncherVisible = false
    private var lastPauseTime = 0L

    private val packagesCallback = object : LauncherApps.Callback() {
        override fun onPackageRemoved(pkg: String, user: UserHandle) = resolver.onAppRemoved(pkg)
        override fun onPackageAdded(pkg: String, user: UserHandle) = Unit
        override fun onPackageChanged(pkg: String, user: UserHandle) = Unit
        override fun onPackagesAvailable(pkgs: Array<out String>, user: UserHandle, replacing: Boolean) =
            Unit
        override fun onPackagesUnavailable(
            pkgs: Array<out String>,
            user: UserHandle,
            replacing: Boolean,
        ) {
            pkgs.forEach { resolver.onAppRemoved(it) }
        }
    }

    fun init(listener: Listener) {
        this.listener = listener
        try {
            context.getSystemService(LauncherApps::class.java)?.registerCallback(packagesCallback)
        } catch (e: Exception) {
            Log.e(TAG, "registerCallback failed", e)
        }
        resolver.slots.onEach { slots ->
            lastPublishedSlots = slots
            if (isLauncherVisible) {
                listener.onDockSlotsChanged(slots)
            }
        }.launchIn(scope)
    }

    fun destroy() {
        try {
            context.getSystemService(LauncherApps::class.java)?.unregisterCallback(packagesCallback)
        } catch (_: Exception) {
        }
        resolver.destroy()
        scope.cancel()
        listener = null
    }

    fun onLauncherResumed(
        totalSlots: Int = lastTotal,
        pinnedPackagesByRank: List<String?> = lastPinnedPackagesByRank,
        occupiedRanks: Set<Int> = lastOccupiedRanks,
        foreground: String? = null,
    ) {
        isLauncherVisible = true
        lastTotal = totalSlots
        lastPinnedPackagesByRank = pinnedPackagesByRank
        lastOccupiedRanks = occupiedRanks
        if (!isActive()) {
            listener?.onDockSlotsChanged(emptyList())
            return
        }
        listener?.onDockSlotsChanged(lastPublishedSlots)
        if (!DockSuggestionsHelper.hasUsageStatsPermission(context)) {
            resolver.onPermissionRevoked()
            return
        }
        if (shouldRefreshForThisResume()) {
            resolver.invalidateCache()
        }
        resolver.update(
            pinnedPackagesByRank = pinnedPackagesByRank,
            occupiedRanks = occupiedRanks,
            total = totalSlots,
            mode = DockSuggestionsHelper.getSuggestionMode(context),
            foreground = foreground,
        )
    }

    fun onLauncherPaused() {
        isLauncherVisible = false
        lastPauseTime = SystemClock.elapsedRealtime()
        resolver.cancelPendingRefresh()
    }

    fun isActive() =
        DockSuggestionsHelper.isFeatureEnabled(context) &&
            DockSuggestionsHelper.hasUsageStatsPermission(context)

    fun showOnboardingIfNeeded() {
        if (!DockSuggestionsHelper.needsOnboarding(context)) return
        AlertDialog.Builder(context)
            .setTitle(com.android.launcher3.R.string.dock_recents_permission_title)
            .setMessage(com.android.launcher3.R.string.dock_recents_permission_message)
            .setPositiveButton(com.android.launcher3.R.string.dock_recents_permission_grant) { _, _ ->
                context.startActivity(
                    DockSuggestionsHelper.usageAccessSettingsIntent()
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun shouldRefreshForThisResume(): Boolean {
        if (lastPauseTime == 0L) {
            return true
        }
        return SystemClock.elapsedRealtime() - lastPauseTime > HOME_VISIT_CACHE_WINDOW_MS
    }

    companion object {
        private const val TAG = "DockSuggestionsController"
        private const val HOME_VISIT_CACHE_WINDOW_MS = 2_000L
    }
}
