/*
 * Copyright (C) 2016 The Android Open Source Project
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

package com.android.launcher3.qsb;

import static android.appwidget.AppWidgetManager.ACTION_APPWIDGET_BIND;
import static android.appwidget.AppWidgetManager.EXTRA_APPWIDGET_ID;
import static android.appwidget.AppWidgetManager.EXTRA_APPWIDGET_PROVIDER;
import static android.content.Intent.ACTION_PACKAGE_ADDED;
import static android.content.Intent.ACTION_PACKAGE_CHANGED;
import static android.content.Intent.ACTION_PACKAGE_REMOVED;

import android.app.Activity;
import android.app.Fragment;
import android.app.SearchManager;
import android.appwidget.AppWidgetHost;
import android.appwidget.AppWidgetHostView;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProviderInfo;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.util.AttributeSet;
import android.util.SizeF;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.WorkerThread;

import com.android.launcher3.BuildConfig;
import com.android.launcher3.DeviceProfile;
import com.android.launcher3.InvariantDeviceProfile;
import com.android.launcher3.LauncherAppState;
import com.android.launcher3.LauncherPrefs;
import com.android.launcher3.R;
import com.android.launcher3.dagger.LauncherComponentProvider;
import com.android.launcher3.Utilities;
import com.android.launcher3.graphics.FragmentWithPreview;
import com.android.launcher3.views.ActivityContext;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;

/**
 * A frame layout which contains a QSB. This internally uses fragment to bind the view, which
 * allows it to contain the logic for {@link Fragment#startActivityForResult(Intent, int)}.
 *
 * Note: WidgetManagerHelper can be disabled using FeatureFlags. In QSB, we should use
 * AppWidgetManager directly, so that it keeps working in that case.
 */
public class QsbContainerView extends FrameLayout
        implements SharedPreferences.OnSharedPreferenceChangeListener {

    public static final String SEARCH_ENGINE_SETTINGS_KEY = "selected_search_engine";
    private static final int HOTSEAT_QSB_WIDGET_HOST_ID = 1027;
    private static final String HOTSEAT_WIDGET_ID_KEY = "qsb_widget_id_hotseat";

    private final BroadcastReceiver mReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (!isSelfManaged()) {
                return;
            }
            String pkgName = intent.getData() == null ? null : intent.getData().getSchemeSpecificPart();
            String searchPkg = getSearchWidgetPackageName(context);
            if ((mWidgetInfo != null && mWidgetInfo.provider.getPackageName().equals(pkgName))
                    || (pkgName != null && pkgName.equals(searchPkg))) {
                rebindQsb();
            }
        }
    };

    private boolean mReceiverRegistered;
    private boolean mUsesEmbeddedFragment;
    @Nullable private QsbWidgetHost mQsbWidgetHost;
    @Nullable private AppWidgetProviderInfo mWidgetInfo;
    @Nullable private QsbWidgetHostView mQsb;

    /**
     * Returns the package name for user configured search provider or from searchManager
     * @param context
     * @return String
     */
    @WorkerThread
    @Nullable
    public static String getSearchWidgetPackageName(@NonNull Context context) {
        return findSearchWidgetPackageName(context, true /* includeGooglePackage */);
    }

    @WorkerThread
    @Nullable
    public static String getWidgetSearchWidgetPackageName(@NonNull Context context) {
        return findSearchWidgetPackageName(context, false /* includeGooglePackage */);
    }

    @WorkerThread
    @Nullable
    private static String findSearchWidgetPackageName(
            @NonNull Context context, boolean includeGooglePackage) {
        LinkedHashSet<String> providerCandidates = new LinkedHashSet<>();

        String userSelectedPackage = Settings.Secure.getString(
                context.getContentResolver(), SEARCH_ENGINE_SETTINGS_KEY);
        if (userSelectedPackage != null && !userSelectedPackage.isEmpty()) {
            providerCandidates.add(userSelectedPackage);
        }

        SearchManager searchManager = context.getSystemService(SearchManager.class);
        if (searchManager != null) {
            try {
                ComponentName componentName = searchManager.getGlobalSearchActivity();
                if (componentName != null) {
                    providerCandidates.add(componentName.getPackageName());
                }
            } catch (IllegalStateException e) {
                // Ignore and continue with the remaining fallbacks.
            }
        }

        if (includeGooglePackage && Utilities.isGSAEnabled(context)) {
            providerCandidates.add(Utilities.GSA_PACKAGE);
        }

        providerCandidates.addAll(getFallbackSearchWidgetPackages(context, includeGooglePackage));

        for (String providerPkg : providerCandidates) {
            if (providerPkg == null || providerPkg.isEmpty()
                    || (!includeGooglePackage && Utilities.GSA_PACKAGE.equals(providerPkg))
                    || !Utilities.isPackageInstalled(context, providerPkg)) {
                continue;
            }
            if (getSearchWidgetProviderInfo(context, providerPkg) != null) {
                return providerPkg;
            }
        }
        return null;
    }

    /**
     * returns it's AppWidgetProviderInfo using package name from getSearchWidgetPackageName
     * @param context
     * @return AppWidgetProviderInfo
     */
    @WorkerThread
    @Nullable
    public static AppWidgetProviderInfo getSearchWidgetProviderInfo(@NonNull Context context) {
        String providerPkg = getSearchWidgetPackageName(context);
        return providerPkg == null ? null : getSearchWidgetProviderInfo(context, providerPkg);
    }

    @WorkerThread
    @Nullable
    public static AppWidgetProviderInfo getWidgetSearchWidgetProviderInfo(
            @NonNull Context context) {
        String providerPkg = getWidgetSearchWidgetPackageName(context);
        return providerPkg == null ? null : getSearchWidgetProviderInfo(context, providerPkg);
    }

    @WorkerThread
    @Nullable
    private static AppWidgetProviderInfo getSearchWidgetProviderInfo(
            @NonNull Context context, @NonNull String providerPkg) {
        AppWidgetProviderInfo defaultWidgetForSearchPackage = null;
        AppWidgetManager appWidgetManager = AppWidgetManager.getInstance(context);
        for (AppWidgetProviderInfo info :
                appWidgetManager.getInstalledProvidersForPackage(providerPkg, null)) {
            if (info.provider.getPackageName().equals(providerPkg)) {
                if ((info.widgetCategory
                        & AppWidgetProviderInfo.WIDGET_CATEGORY_SEARCHBOX) != 0) {
                    return info;
                } else if (defaultWidgetForSearchPackage == null) {
                    defaultWidgetForSearchPackage = info;
                }
            }
        }
        return defaultWidgetForSearchPackage;
    }

    @WorkerThread
    @NonNull
    private static LinkedHashSet<String> getFallbackSearchWidgetPackages(
            @NonNull Context context, boolean includeGooglePackage) {
        LinkedHashSet<String> fallbacks = new LinkedHashSet<>(Arrays.asList(
                context.getResources().getStringArray(R.array.qsb_search_fallback)));
        if (!includeGooglePackage) {
            fallbacks.remove(Utilities.GSA_PACKAGE);
        }

        PackageManager packageManager = context.getPackageManager();
        Intent browserIntent = new Intent(Intent.ACTION_VIEW, Uri.parse("http://www.google.com"));
        for (ResolveInfo info : packageManager.queryIntentActivities(
                browserIntent, PackageManager.MATCH_ALL)) {
            if (info.activityInfo == null) {
                continue;
            }
            String packageName = info.activityInfo.packageName;
            if (!includeGooglePackage && Utilities.GSA_PACKAGE.equals(packageName)) {
                continue;
            }
            if (!fallbacks.contains(packageName)
                    && getSearchWidgetProviderInfo(context, packageName) != null) {
                fallbacks.add(packageName);
            }
        }
        return fallbacks;
    }

    /**
     * returns componentName for searchWidget if package name is known.
     */
    @WorkerThread
    @Nullable
    public static ComponentName getSearchComponentName(@NonNull  Context context) {
        AppWidgetProviderInfo providerInfo =
                QsbContainerView.getSearchWidgetProviderInfo(context);
        if (providerInfo != null) {
            return providerInfo.provider;
        } else {
            String pkgName = QsbContainerView.getSearchWidgetPackageName(context);
            if (pkgName != null) {
                //we don't know the class name yet. we'll put the package name as placeholder
                return new ComponentName(pkgName, pkgName);
            }
            return null;
        }
    }

    public QsbContainerView(Context context) {
        this(context, null);
    }

    public QsbContainerView(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public QsbContainerView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        if (!isInEditMode()) {
            mQsbWidgetHost = createHotseatHost();
        }
    }

    @Override
    public void setPadding(int left, int top, int right, int bottom) {
        super.setPadding(0, 0, 0, 0);
    }

    protected void setPaddingUnchecked(int left, int top, int right, int bottom) {
        super.setPadding(left, top, right, bottom);
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();
        mUsesEmbeddedFragment = getChildCount() > 0;
    }

    private boolean isSelfManaged() {
        return !mUsesEmbeddedFragment;
    }

    protected QsbWidgetHost createHotseatHost() {
        return new QsbWidgetHost(getContext(), HOTSEAT_QSB_WIDGET_HOST_ID,
                QsbWidgetHostView::new, this::rebindQsb);
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        if (!isSelfManaged() || mQsbWidgetHost == null || !Utilities.showQSB(getContext())) {
            return;
        }
        mQsbWidgetHost.startListening();
        rebindQsb();
        LauncherPrefs.getPrefs(getContext()).registerOnSharedPreferenceChangeListener(this);

        IntentFilter intentFilter = new IntentFilter();
        intentFilter.addAction(ACTION_PACKAGE_ADDED);
        intentFilter.addAction(ACTION_PACKAGE_CHANGED);
        intentFilter.addAction(ACTION_PACKAGE_REMOVED);
        intentFilter.addDataScheme("package");
        getContext().registerReceiver(mReceiver, intentFilter, Context.RECEIVER_EXPORTED);
        mReceiverRegistered = true;
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        if (!isSelfManaged() || mQsbWidgetHost == null) {
            return;
        }
        if (mReceiverRegistered) {
            getContext().unregisterReceiver(mReceiver);
            mReceiverRegistered = false;
        }
        LauncherPrefs.getPrefs(getContext()).unregisterOnSharedPreferenceChangeListener(this);
        mQsbWidgetHost.stopListening();
    }

    @Override
    public void onVisibilityAggregated(boolean isVisible) {
        super.onVisibilityAggregated(isVisible);
        if (!isVisible || !isSelfManaged() || mQsb == null) {
            return;
        }
        int orientation = getContext().getResources().getConfiguration().orientation;
        if (mQsb.isReinflateRequired(orientation)) {
            rebindQsb();
        }
    }

    private void rebindQsb() {
        if (!isSelfManaged()) {
            return;
        }
        removeAllViews();
        if (Utilities.showQSB(getContext())) {
            addView(createQsb(this));
        }
    }

    private View createQsb(ViewGroup container) {
        if (mQsbWidgetHost == null) {
            return QsbWidgetHostView.getDefaultView(container);
        }

        mWidgetInfo = getWidgetSearchWidgetProviderInfo(getContext());
        if (mWidgetInfo == null) {
            return getDefaultView(container, false /* showSetupIcon */);
        }

        Bundle opts = createBindOptions();
        Context context = getContext();
        AppWidgetManager widgetManager = AppWidgetManager.getInstance(context);

        int widgetId = LauncherPrefs.getPrefs(context).getInt(HOTSEAT_WIDGET_ID_KEY, -1);
        AppWidgetProviderInfo widgetInfo = widgetManager.getAppWidgetInfo(widgetId);
        boolean isWidgetBound =
                widgetInfo != null && widgetInfo.provider.equals(mWidgetInfo.provider);

        int oldWidgetId = widgetId;
        if (!isWidgetBound) {
            if (widgetId > -1) {
                mQsbWidgetHost.deleteHost();
            }

            widgetId = mQsbWidgetHost.allocateAppWidgetId();
            isWidgetBound = widgetManager.bindAppWidgetIdIfAllowed(
                    widgetId, mWidgetInfo.getProfile(), mWidgetInfo.provider, opts);
            if (!isWidgetBound) {
                mQsbWidgetHost.deleteAppWidgetId(widgetId);
                widgetId = -1;
            }

            if (oldWidgetId != widgetId) {
                saveHotseatWidgetId(context, widgetId);
            }
        }

        if (isWidgetBound) {
            mQsb = (QsbWidgetHostView) mQsbWidgetHost.createView(context, widgetId, mWidgetInfo);
            mQsb.setId(R.id.qsb_widget);
            mQsb.setLayoutParams(new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            mQsb.setHotseatVisualCompensation(isSelfManaged());

            if (!containsAll(widgetManager.getAppWidgetOptions(widgetId), opts)) {
                mQsb.updateAppWidgetOptions(opts);
            }
            return mQsb;
        }

        return getDefaultView(container, true /* showSetupIcon */);
    }

    protected Bundle createBindOptions() {
        if (isSelfManaged()) {
            return createHotseatBindOptions();
        }
        InvariantDeviceProfile idp = LauncherAppState.getIDP(getContext());
        return LauncherComponentProvider.get(getContext())
                .getWidgetSizeHandler().getWidgetSizeOptions(idp.numColumns, 1);
    }

    private Bundle createHotseatBindOptions() {
        DeviceProfile deviceProfile = ActivityContext.lookupContext(getContext()).getDeviceProfile();
        float density = getResources().getDisplayMetrics().density;
        int widthDp = Math.round(Utilities.getHotseatQsbWidth(getContext()) / density);
        int heightDp = Math.round(deviceProfile.getHotseatProfile().getQsbHeight() / density);

        ArrayList<SizeF> sizes = new ArrayList<>(1);
        sizes.add(new SizeF(widthDp, heightDp));

        Bundle options = new Bundle();
        options.putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, widthDp);
        options.putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, heightDp);
        options.putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH, widthDp);
        options.putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, heightDp);
        options.putParcelableArrayList(AppWidgetManager.OPTION_APPWIDGET_SIZES, sizes);
        return options;
    }

    protected View getDefaultView(ViewGroup container, boolean showSetupIcon) {
        View v = QsbWidgetHostView.getDefaultView(container);
        if (showSetupIcon && mQsbWidgetHost != null && mWidgetInfo != null) {
            View setupButton = v.findViewById(R.id.btn_qsb_setup);
            setupButton.setVisibility(View.VISIBLE);
            setupButton.setOnClickListener(v2 -> getContext().startActivity(
                    new Intent(getContext(), QsbSetupActivity.class)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            .putExtra(EXTRA_APPWIDGET_ID, mQsbWidgetHost.allocateAppWidgetId())
                            .putExtra(EXTRA_APPWIDGET_PROVIDER, mWidgetInfo.provider)));
        }
        return v;
    }

    public static void saveHotseatWidgetId(@NonNull Context context, int widgetId) {
        LauncherPrefs.getPrefs(context).edit().putInt(HOTSEAT_WIDGET_ID_KEY, widgetId).apply();
    }

    @Override
    public void onSharedPreferenceChanged(SharedPreferences sharedPreferences, String key) {
        if (HOTSEAT_WIDGET_ID_KEY.equals(key) && isSelfManaged()) {
            rebindQsb();
        }
    }

    /**
     * A fragment to display the QSB.
     */
    public static class QsbFragment extends FragmentWithPreview {

        public static final int QSB_WIDGET_HOST_ID = 1026;
        private static final int REQUEST_BIND_QSB = 1;

        protected String mKeyWidgetId = "qsb_widget_id";
        private QsbWidgetHost mQsbWidgetHost;
        protected AppWidgetProviderInfo mWidgetInfo;
        private QsbWidgetHostView mQsb;

        // We need to store the orientation here, due to a bug (b/64916689) that results in widgets
        // being inflated in the wrong orientation.
        private int mOrientation;

        @Override
        public void onInit(Bundle savedInstanceState) {
            mQsbWidgetHost = createHost();
            mOrientation = getContext().getResources().getConfiguration().orientation;
        }

        protected QsbWidgetHost createHost() {
            return new QsbWidgetHost(getContext(), QSB_WIDGET_HOST_ID,
                    (c) -> new QsbWidgetHostView(c), this::rebindFragment);
        }

        private FrameLayout mWrapper;

        @Override
        public View onCreateView(
                LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {

            mWrapper = new FrameLayout(getContext());
            // Only add the view when enabled
            if (isQsbEnabled()) {
                mQsbWidgetHost.startListening();
                mWrapper.addView(createQsb(mWrapper));
            }
            return mWrapper;
        }

        private View createQsb(ViewGroup container) {
            mWidgetInfo = getSearchWidgetProvider();
            if (mWidgetInfo == null) {
                // There is no search provider, just show the default widget.
                return getDefaultView(container, false /* show setup icon */);
            }
            Bundle opts = createBindOptions();
            Context context = getContext();
            AppWidgetManager widgetManager = AppWidgetManager.getInstance(context);

            int widgetId = LauncherPrefs.getPrefs(context).getInt(mKeyWidgetId, -1);
            AppWidgetProviderInfo widgetInfo = widgetManager.getAppWidgetInfo(widgetId);
            boolean isWidgetBound = (widgetInfo != null) &&
                    widgetInfo.provider.equals(mWidgetInfo.provider);

            int oldWidgetId = widgetId;
            if (!isWidgetBound && !isInPreviewMode()) {
                if (widgetId > -1) {
                    // widgetId is already bound and its not the correct provider. reset host.
                    mQsbWidgetHost.deleteHost();
                }

                widgetId = mQsbWidgetHost.allocateAppWidgetId();
                isWidgetBound = widgetManager.bindAppWidgetIdIfAllowed(
                        widgetId, mWidgetInfo.getProfile(), mWidgetInfo.provider, opts);
                if (!isWidgetBound) {
                    mQsbWidgetHost.deleteAppWidgetId(widgetId);
                    widgetId = -1;
                }

                if (oldWidgetId != widgetId) {
                    saveWidgetId(widgetId);
                }
            }

            if (isWidgetBound) {
                mQsb = (QsbWidgetHostView) mQsbWidgetHost.createView(context, widgetId,
                        mWidgetInfo);
                mQsb.setId(R.id.qsb_widget);

                if (!isInPreviewMode()) {
                    if (!containsAll(AppWidgetManager.getInstance(context)
                            .getAppWidgetOptions(widgetId), opts)) {
                        mQsb.updateAppWidgetOptions(opts);
                    }
                }
                return mQsb;
            }

            // Return a default widget with setup icon.
            return getDefaultView(container, true /* show setup icon */);
        }

        private void saveWidgetId(int widgetId) {
            LauncherPrefs.getPrefs(getContext()).edit().putInt(mKeyWidgetId, widgetId).apply();
        }

        @Override
        public void onActivityResult(int requestCode, int resultCode, Intent data) {
            if (requestCode == REQUEST_BIND_QSB) {
                if (resultCode == Activity.RESULT_OK) {
                    saveWidgetId(data.getIntExtra(EXTRA_APPWIDGET_ID, -1));
                    rebindFragment();
                } else {
                    mQsbWidgetHost.deleteHost();
                }
            }
        }

        @Override
        public void onResume() {
            super.onResume();
            if (mQsb != null && mQsb.isReinflateRequired(mOrientation)) {
                rebindFragment();
            }
        }

        @Override
        public void onDestroy() {
            mQsbWidgetHost.stopListening();
            super.onDestroy();
        }

        private void rebindFragment() {
            // Exit if the embedded qsb is disabled
            if (!isQsbEnabled()) {
                return;
            }

            if (mWrapper != null && getContext() != null) {
                mWrapper.removeAllViews();
                mWrapper.addView(createQsb(mWrapper));
            }
        }

        public boolean isQsbEnabled() {
            return false;
        }

        protected Bundle createBindOptions() {
            InvariantDeviceProfile idp = LauncherAppState.getIDP(getContext());
            return LauncherComponentProvider.get(getContext())
                    .getWidgetSizeHandler().getWidgetSizeOptions(idp.numColumns, 1);
        }

        protected View getDefaultView(ViewGroup container, boolean showSetupIcon) {
            // Return a default widget with setup icon.
            View v = QsbWidgetHostView.getDefaultView(container);
            if (showSetupIcon) {
                View setupButton = v.findViewById(R.id.btn_qsb_setup);
                setupButton.setVisibility(View.VISIBLE);
                setupButton.setOnClickListener((v2) -> startActivityForResult(
                        new Intent(ACTION_APPWIDGET_BIND)
                                .putExtra(EXTRA_APPWIDGET_ID, mQsbWidgetHost.allocateAppWidgetId())
                                .putExtra(EXTRA_APPWIDGET_PROVIDER, mWidgetInfo.provider),
                        REQUEST_BIND_QSB));
            }
            return v;
        }


        /**
         * Returns a widget with category {@link AppWidgetProviderInfo#WIDGET_CATEGORY_SEARCHBOX}
         * provided by the package from getSearchProviderPackageName
         * If widgetCategory is not supported, or no such widget is found, returns the first widget
         * provided by the package.
         */
        @WorkerThread
        protected AppWidgetProviderInfo getSearchWidgetProvider() {
            return getSearchWidgetProviderInfo(getContext());
        }
    }

    public static class QsbWidgetHost extends AppWidgetHost {

        private final WidgetViewFactory mViewFactory;
        private final WidgetProvidersUpdateCallback mWidgetsUpdateCallback;

        public QsbWidgetHost(Context context, int hostId, WidgetViewFactory viewFactory,
                WidgetProvidersUpdateCallback widgetProvidersUpdateCallback) {
            super(context, hostId);
            mViewFactory = viewFactory;
            mWidgetsUpdateCallback = widgetProvidersUpdateCallback;
        }

        public QsbWidgetHost(Context context, int hostId, WidgetViewFactory viewFactory) {
            this(context, hostId, viewFactory, null);
        }

        @Override
        protected AppWidgetHostView onCreateView(
                Context context, int appWidgetId, AppWidgetProviderInfo appWidget) {
            return mViewFactory.newView(context);
        }

        @Override
        protected void onProvidersChanged() {
            super.onProvidersChanged();
            if (mWidgetsUpdateCallback != null) {
                mWidgetsUpdateCallback.onProvidersUpdated();
            }
        }
    }

    public interface WidgetViewFactory {

        QsbWidgetHostView newView(Context context);
    }

    /**
     * Callback interface for packages list update.
     */
    @FunctionalInterface
    public interface WidgetProvidersUpdateCallback {
        /**
         * Gets called when widget providers list changes
         */
        void onProvidersUpdated();
    }

    /**
     * Returns true if {@param original} contains all entries defined in {@param updates} and
     * have the same value.
     * The comparison uses {@link Object#equals(Object)} to compare the values.
     */
    private static boolean containsAll(Bundle original, Bundle updates) {
        for (String key : updates.keySet()) {
            Object value1 = updates.get(key);
            Object value2 = original.get(key);
            if (value1 == null) {
                if (value2 != null) {
                    return false;
                }
            } else if (!value1.equals(value2)) {
                return false;
            }
        }
        return true;
    }

}
