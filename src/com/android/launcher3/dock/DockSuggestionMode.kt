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

enum class DockSuggestionMode(val prefValue: String) {
    OFF("off"),
    RECENT("recent"),
    TOP_APPS("top_apps"),
    SMART("smart");

    val isEnabled: Boolean
        get() = this != OFF

    companion object {
        @JvmStatic
        fun fromPrefValue(value: String?): DockSuggestionMode =
            values().firstOrNull { it.prefValue == value } ?: SMART

        @JvmStatic
        fun fromLegacy(fillEnabled: Boolean, showMostUsed: Boolean): DockSuggestionMode =
            when {
                !fillEnabled -> OFF
                showMostUsed -> TOP_APPS
                else -> RECENT
            }
    }
}
