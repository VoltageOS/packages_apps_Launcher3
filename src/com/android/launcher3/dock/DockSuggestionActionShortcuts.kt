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

import android.view.View
import com.android.launcher3.AbstractFloatingView
import com.android.launcher3.Launcher
import com.android.launcher3.R
import com.android.launcher3.logging.StatsLogManager
import com.android.launcher3.model.data.ItemInfo
import com.android.launcher3.popup.SystemShortcut

class DockHideSuggestionShortcut(
    launcher: Launcher,
    itemInfo: ItemInfo,
    originalView: View,
    private val onHide: () -> Boolean,
) : SystemShortcut<Launcher>(
    R.drawable.ic_remove_no_shadow,
    R.string.dock_suggestion_hide_for_now,
    launcher,
    itemInfo,
    originalView,
    false,
) {
    override fun onClick(view: View) {
        AbstractFloatingView.closeAllOpenViews(mTarget)
        onHide()
    }
}

class DockDontSuggestShortcut(
    launcher: Launcher,
    itemInfo: ItemInfo,
    originalView: View,
    private val onDontSuggest: () -> Boolean,
) : SystemShortcut<Launcher>(
    R.drawable.ic_block_no_shadow,
    R.string.dismiss_prediction_label,
    launcher,
    itemInfo,
    originalView,
    false,
) {
    override fun onClick(view: View) {
        AbstractFloatingView.closeAllOpenViews(mTarget)
        mTarget.statsLogManager.logger().withItemInfo(mItemInfo)
            .log(StatsLogManager.LauncherEvent.LAUNCHER_SYSTEM_SHORTCUT_DONT_SUGGEST_APP_TAP)
        onDontSuggest()
    }
}
