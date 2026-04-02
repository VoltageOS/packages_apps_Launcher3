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

import com.android.launcher3.BubbleTextView
import com.android.launcher3.Launcher
import com.android.launcher3.LauncherPrefs
import com.android.launcher3.model.data.AppInfo
import com.android.launcher3.popup.PopupContainer
import com.android.launcher3.popup.PopupContainerWithArrow
import com.android.launcher3.popup.SystemShortcut

internal object DockSuggestionPopup {

    interface ActionHandler {
        fun onPin(): Boolean
        fun onHideForNow(): Boolean
        fun onDontSuggest(): Boolean
    }

    fun show(
        anchor: BubbleTextView,
        app: AppInfo,
        actionHandler: ActionHandler? = null,
    ): Boolean {
        val launcher = Launcher.getLauncher(anchor.context)
        if (PopupContainer.getOpen(launcher) != null) {
            anchor.clearFocus()
            return true
        }

        val shortcuts = buildSystemShortcuts(launcher, app, anchor, actionHandler)
        if (shortcuts.isEmpty()) {
            return false
        }

        val container =
            PopupContainerWithArrow.create<Launcher>(
                context = launcher,
                originalView = anchor,
                itemInfo = app,
            )
        container.configureForLauncher(launcher, app)
        container.populateAndShowRows(0, shortcuts)
        container.requestFocus()
        return true
    }

    private fun buildSystemShortcuts(
        launcher: Launcher,
        app: AppInfo,
        anchor: BubbleTextView,
        actionHandler: ActionHandler?,
    ): MutableList<SystemShortcut<Launcher>> {
        val shortcuts = mutableListOf<SystemShortcut<Launcher>>()

        if (actionHandler != null && !LauncherPrefs.WORKSPACE_LOCK.get(launcher)) {
            shortcuts.add(
                DockPinShortcut(
                    launcher = launcher,
                    itemInfo = app,
                    originalView = anchor,
                    onPin = actionHandler::onPin,
                ),
            )
        }
        if (actionHandler != null) {
            shortcuts.add(
                DockHideSuggestionShortcut(
                    launcher = launcher,
                    itemInfo = app,
                    originalView = anchor,
                    onHide = actionHandler::onHideForNow,
                ),
            )
            shortcuts.add(
                DockDontSuggestShortcut(
                    launcher = launcher,
                    itemInfo = app,
                    originalView = anchor,
                    onDontSuggest = actionHandler::onDontSuggest,
                ),
            )
        }

        @Suppress("UNCHECKED_CAST")
        (SystemShortcut.APP_INFO.getShortcut(launcher, app, anchor) as SystemShortcut<Launcher>?)
            ?.let(shortcuts::add)

        return shortcuts
    }
}
