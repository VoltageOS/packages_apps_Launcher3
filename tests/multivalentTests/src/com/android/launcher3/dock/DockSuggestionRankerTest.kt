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

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith

@SmallTest
@RunWith(AndroidJUnit4::class)
class DockSuggestionRankerTest {

    @Test
    fun legacyModeMigration_mapsOldPrefsCorrectly() {
        assertThat(DockSuggestionMode.fromLegacy(fillEnabled = false, showMostUsed = false))
            .isEqualTo(DockSuggestionMode.OFF)
        assertThat(DockSuggestionMode.fromLegacy(fillEnabled = true, showMostUsed = false))
            .isEqualTo(DockSuggestionMode.RECENT)
        assertThat(DockSuggestionMode.fromLegacy(fillEnabled = true, showMostUsed = true))
            .isEqualTo(DockSuggestionMode.TOP_APPS)
    }

    @Test
    fun topApps_prefersLaunchCount() {
        val ranked =
            DockSuggestionRanker.sort(
                candidates =
                    listOf(
                        DockUsageCandidate("com.example.rare", launchCount = 2, lastUsedTime = 500),
                        DockUsageCandidate("com.example.frequent", launchCount = 8, lastUsedTime = 100),
                    ),
                mode = DockSuggestionMode.TOP_APPS,
                stablePackages = emptySet(),
                now = 1_000,
            )

        assertThat(ranked.map { it.packageName })
            .containsExactly("com.example.frequent", "com.example.rare")
            .inOrder()
    }

    @Test
    fun recent_prefersLatestUse() {
        val ranked =
            DockSuggestionRanker.sort(
                candidates =
                    listOf(
                        DockUsageCandidate("com.example.old", launchCount = 20, lastUsedTime = 100),
                        DockUsageCandidate("com.example.new", launchCount = 1, lastUsedTime = 900),
                    ),
                mode = DockSuggestionMode.RECENT,
                stablePackages = emptySet(),
                now = 1_000,
            )

        assertThat(ranked.map { it.packageName })
            .containsExactly("com.example.new", "com.example.old")
            .inOrder()
    }

    @Test
    fun smart_appliesStabilityBonusWithoutIgnoringStrongSignals() {
        val ranked =
            DockSuggestionRanker.sort(
                candidates =
                    listOf(
                        DockUsageCandidate("com.example.stable", launchCount = 5, lastUsedTime = 800),
                        DockUsageCandidate("com.example.fresh", launchCount = 5, lastUsedTime = 900),
                    ),
                mode = DockSuggestionMode.SMART,
                stablePackages = setOf("com.example.stable"),
                now = 1_000,
            )

        assertThat(ranked.first().packageName).isEqualTo("com.example.stable")
    }
}
