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

import kotlin.math.pow

internal data class DockUsageCandidate(
    val packageName: String,
    val launchCount: Int,
    val lastUsedTime: Long,
)

internal object DockSuggestionRanker {
    internal const val FREQUENCY_WEIGHT = 0.65
    internal const val RECENCY_WEIGHT = 0.35
    internal const val STABILITY_BONUS = 0.12
    internal const val NOW_PLAYING_BONUS = 0.18
    internal const val RECENCY_HALF_LIFE_MS = 12L * 60 * 60 * 1_000

    fun sort(
        candidates: Collection<DockUsageCandidate>,
        mode: DockSuggestionMode,
        stablePackages: Set<String>,
        boostedPackages: Set<String> = emptySet(),
        now: Long = System.currentTimeMillis(),
    ): List<DockUsageCandidate> {
        return when (mode) {
            DockSuggestionMode.OFF -> emptyList()
            DockSuggestionMode.RECENT ->
                candidates.sortedWith(
                    compareByDescending<DockUsageCandidate> { it.lastUsedTime }
                        .thenByDescending { it.launchCount }
                        .thenBy { it.packageName }
                )
            DockSuggestionMode.TOP_APPS ->
                candidates.sortedWith(
                    compareByDescending<DockUsageCandidate> { it.launchCount }
                        .thenByDescending { it.lastUsedTime }
                        .thenBy { it.packageName }
                )
            DockSuggestionMode.SMART -> {
                val maxLaunchCount = candidates.maxOfOrNull { it.launchCount }?.coerceAtLeast(1) ?: 1
                val scores = candidates.associateWith { candidate ->
                    val frequencyScore = candidate.launchCount.toDouble() / maxLaunchCount
                    val recencyScore = recencyScore(candidate.lastUsedTime, now)
                    val stabilityScore =
                        if (candidate.packageName in stablePackages) STABILITY_BONUS else 0.0
                    val contextualBoost =
                        if (candidate.packageName in boostedPackages) NOW_PLAYING_BONUS else 0.0
                    frequencyScore * FREQUENCY_WEIGHT +
                        recencyScore * RECENCY_WEIGHT +
                        stabilityScore +
                        contextualBoost
                }
                candidates.sortedWith(
                    compareByDescending<DockUsageCandidate> { scores[it] ?: 0.0 }
                        .thenByDescending { it.lastUsedTime }
                        .thenByDescending { it.launchCount }
                        .thenBy { it.packageName }
                )
            }
        }
    }

    private fun recencyScore(lastUsedTime: Long, now: Long): Double {
        if (lastUsedTime <= 0L) return 0.0
        val ageMs = (now - lastUsedTime).coerceAtLeast(0L)
        return 0.5.pow(ageMs.toDouble() / RECENCY_HALF_LIFE_MS)
    }
}
