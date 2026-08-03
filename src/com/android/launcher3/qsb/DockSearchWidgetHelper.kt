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

package com.android.launcher3.qsb

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.appwidget.AppWidgetProviderInfo.RESIZE_HORIZONTAL
import android.appwidget.AppWidgetProviderInfo.RESIZE_VERTICAL
import android.appwidget.AppWidgetProviderInfo.WIDGET_CATEGORY_SEARCHBOX
import android.appwidget.AppWidgetProviderInfo.WIDGET_FEATURE_HIDE_FROM_PICKER
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import com.android.launcher3.LauncherPrefs
import com.android.launcher3.R
import java.util.Locale


object DockSearchWidgetHelper {

    private const val MIN_DOCK_WIDGET_ASPECT_RATIO = 3.0f

    @JvmStatic
    fun isCustomWidgetEnabled(context: Context): Boolean =
        LauncherPrefs.isCustomSearchWidgetEnabled(context)

    @JvmStatic
    fun getSelectedProviderFlattened(context: Context): String =
        try {
            LauncherPrefs.get(context).get(LauncherPrefs.DOCK_SEARCH_WIDGET)
        } catch (ignored: IllegalStateException) {
            ""
        }

    @JvmStatic
    fun getSelectedProvider(context: Context): ComponentName? {
        val flattened = getSelectedProviderFlattened(context)
        if (flattened.isEmpty()) return null
        return ComponentName.unflattenFromString(flattened)
    }

    @JvmStatic
    fun isSelectedProviderPackage(context: Context, packageName: String): Boolean {
        if (!isCustomWidgetEnabled(context)) return false
        return getSelectedProvider(context)?.packageName == packageName
    }

    @JvmStatic
    fun setSelectedProvider(context: Context, provider: ComponentName?) {
        LauncherPrefs.get(context)
            .put(LauncherPrefs.DOCK_SEARCH_WIDGET, provider?.flattenToString() ?: "")
        if (provider == null) {
            clearPendingConfiguration(context)
        }
    }

    @JvmStatic
    fun getEligibleSelectedProvider(context: Context): ComponentName? {
        val provider = getSelectedProvider(context) ?: return null
        val info = getProviderInfo(context, provider) ?: return null
        if (!isEligibleDockSearchWidget(context, info)) {
            setSelectedProvider(context, null)
            return null
        }
        return provider
    }

    @JvmStatic
    fun hasEligibleSearchWidget(context: Context): Boolean =
        AppWidgetManager.getInstance(context).installedProviders.any {
            isEligibleDockSearchWidget(context, it)
        }

    @JvmStatic
    fun getEligibleSearchWidgets(context: Context): List<AppWidgetProviderInfo> {
        val pm = context.packageManager
        return AppWidgetManager.getInstance(context)
            .installedProviders
            .asSequence()
            .filter { isEligibleDockSearchWidget(context, it) }
            .sortedWith(
                compareBy<AppWidgetProviderInfo>(
                    { safeAppLabel(pm, it.provider.packageName) },
                    { it.loadLabel(pm).toString() },
                )
            )
            .toList()
    }

    @JvmStatic
    fun isEligibleDockSearchWidget(context: Context, info: AppWidgetProviderInfo): Boolean {
        if (!fitsInDockRow(context, info)) {
            return false
        }
        if ((info.widgetFeatures and WIDGET_FEATURE_HIDE_FROM_PICKER) != 0 &&
            (info.widgetCategory and WIDGET_CATEGORY_SEARCHBOX) == 0
        ) {
            return false
        }
        if ((info.widgetCategory and WIDGET_CATEGORY_SEARCHBOX) != 0) {
            return true
        }
        val pm = context.packageManager
        val widgetLabel = info.loadLabel(pm).lowercase(Locale.US)
        val className = info.provider.shortClassName.lowercase(Locale.US)
        if (widgetLabel.contains("search") || className.contains("search")) {
            return true
        }
        return safeAppLabel(pm, info.provider.packageName).lowercase(Locale.US).contains("search")
    }

    @JvmStatic
    fun fitsInDockRow(context: Context, info: AppWidgetProviderInfo): Boolean {
        val minHeight = getMinimumWidgetHeight(info)
        if (minHeight <= 0) {
            return true
        }
        val maxHeight = context.resources.getDimensionPixelSize(R.dimen.qsb_widget_height)
        if (minHeight > maxHeight) {
            return false
        }
        return getMinimumWidgetWidth(info) >= minHeight * MIN_DOCK_WIDGET_ASPECT_RATIO
    }

    @JvmStatic
    fun getMinimumWidgetHeight(info: AppWidgetProviderInfo): Int =
        if ((info.resizeMode and RESIZE_VERTICAL) != 0 &&
            info.minResizeHeight > 0 &&
           info.minResizeHeight < info.minHeight
        ) info.minResizeHeight
        else info.minHeight

    @JvmStatic
    fun getMinimumWidgetWidth(info: AppWidgetProviderInfo): Int =
        if ((info.resizeMode and RESIZE_HORIZONTAL) != 0 &&
            info.minResizeWidth > 0 &&
            info.minResizeWidth < info.minWidth
        ) info.minResizeWidth
        else info.minWidth

    @JvmStatic
    fun supportsConfiguration(info: AppWidgetProviderInfo?): Boolean = info?.configure != null

    @JvmStatic
    fun setPendingConfiguration(context: Context, pending: Boolean) {
        LauncherPrefs.get(context).put(LauncherPrefs.DOCK_SEARCH_WIDGET_PENDING_CONFIG, pending)
    }

    @JvmStatic
    fun consumePendingConfiguration(context: Context): Boolean {
        val prefs = LauncherPrefs.get(context)
        val pending = prefs.get(LauncherPrefs.DOCK_SEARCH_WIDGET_PENDING_CONFIG)
        if (pending) {
            prefs.put(LauncherPrefs.DOCK_SEARCH_WIDGET_PENDING_CONFIG, false)
        }
        return pending
    }

    @JvmStatic
    fun clearPendingConfiguration(context: Context) {
        LauncherPrefs.get(context).put(LauncherPrefs.DOCK_SEARCH_WIDGET_PENDING_CONFIG, false)
    }

    @JvmStatic
    fun getProviderInfo(context: Context, provider: ComponentName): AppWidgetProviderInfo? =
        AppWidgetManager.getInstance(context).installedProviders.firstOrNull {
            it.provider == provider
        }

    @JvmStatic
    fun getWidgetLabel(context: Context, info: AppWidgetProviderInfo): String {
        val pm = context.packageManager
        return "${safeAppLabel(pm, info.provider.packageName)} — ${info.loadLabel(pm)}"
    }

    @JvmStatic
    fun getSelectedWidgetLabel(context: Context): String {
        val provider =
            getSelectedProvider(context)
                ?: return context.getString(R.string.dock_search_widget_default)
        val info = getProviderInfo(context, provider)
        return if (info != null) getWidgetLabel(context, info) else provider.flattenToString()
    }

    private fun safeAppLabel(pm: PackageManager, packageName: String): String =
        try {
            pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString()
        } catch (e: Exception) {
            packageName
        }
}
