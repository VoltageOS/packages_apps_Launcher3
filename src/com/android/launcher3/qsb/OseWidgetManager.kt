/*
 * Copyright (C) 2025 The Android Open Source Project
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

import android.app.Activity.RESULT_OK
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetManager.INVALID_APPWIDGET_ID
import android.appwidget.AppWidgetProviderInfo
import android.appwidget.AppWidgetProviderInfo.WIDGET_CATEGORY_SEARCHBOX
import android.appwidget.AppWidgetProviderInfo.WIDGET_FEATURE_CONFIGURATION_OPTIONAL
import android.appwidget.AppWidgetProviderInfo.WIDGET_FEATURE_HIDE_FROM_PICKER
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.os.Process.myUserHandle
import android.util.Log
import android.widget.Toast
import androidx.annotation.VisibleForTesting
import com.android.launcher3.BaseActivity
import com.android.launcher3.InvariantDeviceProfile
import com.android.launcher3.InvariantDeviceProfile.OnIDPChangeListener
import com.android.launcher3.LauncherConstants.ActivityCodes.REQUEST_BIND_DOCK_SEARCH_WIDGET
import com.android.launcher3.LauncherConstants.ActivityCodes.REQUEST_CONFIGURE_DOCK_SEARCH_WIDGET
import com.android.launcher3.LauncherConstants.ActivityCodes.REQUEST_RECONFIGURE_APPWIDGET
import com.android.launcher3.LauncherPrefChangeListener
import com.android.launcher3.LauncherPrefs
import com.android.launcher3.R
import com.android.launcher3.dagger.ApplicationContext
import com.android.launcher3.dagger.LauncherAppSingleton
import com.android.launcher3.graphics.theme.ThemePreference
import com.android.launcher3.qsb.OSEManager.Companion.OSE_LOOPER
import com.android.launcher3.qsb.OSEManager.OSEInfo
import com.android.launcher3.util.DaggerSingletonTracker
import com.android.launcher3.util.Executors.MAIN_EXECUTOR
import com.android.launcher3.util.PackageUserKey
import com.android.launcher3.widget.WidgetManagerHelper
import com.android.launcher3.widget.util.WidgetSizeHandler
import javax.inject.Inject

/**
 * Manager for default search widget
 *
 * Listens to OSEManager for any OSE changes and provides the updated widget configurations
 */
@LauncherAppSingleton
class OseWidgetManager
@Inject
constructor(
    @ApplicationContext private val context: Context,
    oseManager: OSEManager,
    private val widgetHost: QsbAppWidgetHost,
    private val sizeHandler: WidgetSizeHandler,
    private val idp: InvariantDeviceProfile,
    tracker: DaggerSingletonTracker,
    themePreference: ThemePreference,
) {

    private val mutableState = QsbAppWidgetHost.MutableState()

    val providerInfo = mutableState.providerInfo.asListenable()
    val views = mutableState.views.asListenable()

    private val executor = OSE_LOOPER

    private var lastOseInfo: OSEInfo? = null

    @Volatile private var pendingConfigActivity = false
    @Volatile private var pendingBindRequest = false

    init {
        tracker.addCloseable(widgetHost.addCallbacks(mutableState))
        tracker.addCloseable(oseManager.oseInfo.forEach(executor, this::handleOseInfoUpdate))

        val idpListener = OnIDPChangeListener { updateWidgetSizeAsync() }
        idp.addOnChangeListener(idpListener)
        tracker.addCloseable(themePreference.forEach(executor) { updateWidgetSizeAsync() })
        tracker.addCloseable { idp.removeOnChangeListener(idpListener) }

        try {
            val prefs = LauncherPrefs.get(context)
            val listener = LauncherPrefChangeListener { key ->
                if (
                    key == LauncherPrefs.SHOW_HOTSEAT_QSB.sharedPrefKey ||
                        key == LauncherPrefs.DOCK_SEARCH_WIDGET.sharedPrefKey
                ) {
                    executor.execute { handleQsbPreferenceChange() }
                }
            }
            prefs.addListener(
                listener,
                LauncherPrefs.SHOW_HOTSEAT_QSB,
                LauncherPrefs.DOCK_SEARCH_WIDGET,
            )
            tracker.addCloseable {
                prefs.removeListener(
                    listener,
                    LauncherPrefs.SHOW_HOTSEAT_QSB,
                    LauncherPrefs.DOCK_SEARCH_WIDGET,
                )
            }
        } catch (e: IllegalStateException) {}
    }

    private fun handleOseInfoUpdate(info: OSEInfo) {
        lastOseInfo = info

        if (!LauncherPrefs.isHotseatQsbEnabled(context)) {
            releaseActiveWidget()
            return
        }

        val customProvider = DockSearchWidgetHelper.getEligibleSelectedProvider(context)
        if (customProvider != null) {
            bindProvider(
                DockSearchWidgetHelper.getProviderInfo(context, customProvider)
                    ?: findSearchWidgetForPackage(context, customProvider.packageName)
            )
            return
        }

        val providerPkg =
            if (info.pkg != null) {
                info.pkg
            } else {
                dispatchNullValues()
                return
            }
        bindProvider(findSearchWidgetForPackage(context, providerPkg))
    }

    private fun bindProvider(searchWidget: AppWidgetProviderInfo?) {
        val currentWidgetId = widgetHost.getBoundWidgetId()
        val currentInfo =
            if (currentWidgetId != INVALID_APPWIDGET_ID)
                AppWidgetManager.getInstance(context).getAppWidgetInfo(currentWidgetId)
            else null

        if (currentInfo?.provider == searchWidget?.provider) {
            widgetHost.setActiveWidget(currentWidgetId, currentInfo)
            updateWidgetSizeAsync()
            return
        }

        if (searchWidget == null) {
            widgetHost.setActiveWidget(INVALID_APPWIDGET_ID, null)
            dispatchNullValues()
            return
        }

        val widgetId = widgetHost.allocateAppWidgetId()
        val bindOptions = sizeHandler.getWidgetSizeOptions(idp.numColumns, 1)
        val bindSuccess =
            AppWidgetManager.getInstance(context)
                .bindAppWidgetIdIfAllowed(
                    widgetId,
                    searchWidget.profile,
                    searchWidget.provider,
                    bindOptions,
                )

        if (bindSuccess) {
            widgetHost.setActiveWidget(widgetId, searchWidget)
            updateWidgetSizeAsync()
            if (DockSearchWidgetHelper.consumePendingConfiguration(context)) {
                pendingConfigActivity = true
            }
        } else {
            widgetHost.deleteAppWidgetId(widgetId)
            pendingBindRequest = DockSearchWidgetHelper.isCustomWidgetEnabled(context)
            widgetHost.setActiveWidget(INVALID_APPWIDGET_ID, null)
            dispatchNullValues()
        }
    }

    private fun handleQsbPreferenceChange() {
        if (LauncherPrefs.isHotseatQsbEnabled(context)) {
            handleOseInfoUpdate(lastOseInfo ?: OSEInfo(null))
        } else {
            releaseActiveWidget()
        }
    }

    private fun releaseActiveWidget() {
        widgetHost.setActiveWidget(INVALID_APPWIDGET_ID, null)
        dispatchNullValues()
    }

    private fun updateWidgetSizeAsync() {
        val widgetId = widgetHost.getActiveWidgetId()
        if (widgetId != INVALID_APPWIDGET_ID) {
            sizeHandler.updateHotseatQsbSizeRangesAsync(widgetId, executor)
        }
    }

    private fun dispatchNullValues() {
        if (mutableState.providerInfo.value != null) mutableState.providerInfo.dispatchValue(null)
        if (mutableState.views.value != null) mutableState.views.dispatchValue(null)
    }

    fun tryStartPendingBindActivity(activity: BaseActivity): Boolean {
        if (!pendingBindRequest || !DockSearchWidgetHelper.isCustomWidgetEnabled(context)) {
            return false
        }
        val provider = DockSearchWidgetHelper.getEligibleSelectedProvider(context) ?: return false
        pendingBindRequest = false
        val widgetId = widgetHost.allocateAppWidgetId()
        return try {
            activity.startActivityForResult(
                Intent(AppWidgetManager.ACTION_APPWIDGET_BIND)
                    .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)
                    .putExtra(AppWidgetManager.EXTRA_APPWIDGET_PROVIDER, provider),
                REQUEST_BIND_DOCK_SEARCH_WIDGET,
            )
            true
        } catch (e: ActivityNotFoundException) {
            widgetHost.deleteAppWidgetId(widgetId)
            Toast.makeText(activity, R.string.activity_not_found, Toast.LENGTH_SHORT).show()
            false
        }
    }

    fun handleBindActivityResult(resultCode: Int, data: Intent?, activity: BaseActivity? = null) {
        val widgetId =
            data?.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, INVALID_APPWIDGET_ID)
                ?: INVALID_APPWIDGET_ID
        if (resultCode != RESULT_OK || widgetId == INVALID_APPWIDGET_ID) {
            if (widgetId != INVALID_APPWIDGET_ID) {
                widgetHost.deleteAppWidgetId(widgetId)
            }
            DockSearchWidgetHelper.clearPendingConfiguration(context)
            return
        }
        executor.execute {
            val provider = DockSearchWidgetHelper.getEligibleSelectedProvider(context)
            val searchWidget =
                provider?.let {
                    DockSearchWidgetHelper.getProviderInfo(context, it)
                        ?: findSearchWidgetForPackage(context, it.packageName)
                }
            if (searchWidget == null) {
                widgetHost.deleteAppWidgetId(widgetId)
                return@execute
            }
            widgetHost.setActiveWidget(widgetId, searchWidget)
            updateWidgetSizeAsync()
            val needsConfig = DockSearchWidgetHelper.consumePendingConfiguration(context)
            if (needsConfig && activity != null) {
                MAIN_EXECUTOR.execute { startConfigActivity(activity) }
            } else if (needsConfig) {
                pendingConfigActivity = true
            }
        }
    }

    fun tryStartPendingConfigActivity(activity: BaseActivity): Boolean {
        if (!pendingConfigActivity) {
            return false
        }
        pendingConfigActivity = false
        return startConfigActivity(activity)
    }

    fun startConfigActivity(activity: BaseActivity): Boolean {
        val widgetId = widgetHost.getActiveWidgetId()
        if (widgetId == INVALID_APPWIDGET_ID || widgetId == 0) {
            Log.e(TAG, "Couldn't find a valid widgetId")
            return false
        }
        try {
            widgetHost.startAppWidgetConfigureActivityForResult(
                activity,
                widgetId,
                0,
                if (DockSearchWidgetHelper.isCustomWidgetEnabled(context))
                    REQUEST_CONFIGURE_DOCK_SEARCH_WIDGET
                else REQUEST_RECONFIGURE_APPWIDGET,
                activity
                    .makeDefaultActivityOptions(-1 /* SPLASH_SCREEN_STYLE_UNDEFINED */)
                    .toBundle(),
            )
            return true
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(activity, R.string.activity_not_found, Toast.LENGTH_SHORT).show()
        } catch (e: SecurityException) {
            Log.e(TAG, "Security Exception $e")
        }
        return false
    }

    companion object {
        private const val TAG = "OseWidgetManager"

        @VisibleForTesting
        fun findSearchWidgetForPackage(context: Context, pkg: String): AppWidgetProviderInfo? {
            val allEligibleWidgets =
                WidgetManagerHelper(context)
                    .getAllProviders(PackageUserKey(pkg, myUserHandle()))
                    .filter {
                        it.configure == null ||
                            ((it.widgetFeatures and WIDGET_FEATURE_CONFIGURATION_OPTIONAL) != 0) ||
                            DockSearchWidgetHelper.isSelectedProviderPackage(context, pkg)
                    }
                    .filter {
                        !DockSearchWidgetHelper.isSelectedProviderPackage(context, pkg) ||
                            DockSearchWidgetHelper.fitsInDockRow(context, it)
                    }
            val allSearchBoxWidgets =
                allEligibleWidgets.filter { (it.widgetCategory and WIDGET_CATEGORY_SEARCHBOX) != 0 }
            return allSearchBoxWidgets.firstOrNull {
                (it.widgetFeatures and WIDGET_FEATURE_HIDE_FROM_PICKER) == 0
            }
                ?: allSearchBoxWidgets.firstOrNull()
                ?: allEligibleWidgets.firstOrNull {
                    DockSearchWidgetHelper.isEligibleDockSearchWidget(context, it)
                }
                ?: allEligibleWidgets.firstOrNull()
        }
    }
}
