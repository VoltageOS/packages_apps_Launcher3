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
package com.android.launcher3.allapps;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.AttributeSet;
import android.view.View;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;

import com.android.launcher3.BubbleTextView;
import com.android.launcher3.DeviceProfile;
import com.android.launcher3.Launcher;
import com.android.launcher3.LauncherAppState;
import com.android.launcher3.LauncherFiles;
import com.android.launcher3.LauncherPrefs;
import com.android.launcher3.R;
import com.android.launcher3.model.data.AppInfo;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class PinnedAppsRowView extends HorizontalScrollView implements FloatingHeaderRow, SharedPreferences.OnSharedPreferenceChangeListener, AllAppsStore.OnUpdateListener {

    private boolean mIsSetup;
    private int mExpectedHeight;
    private Launcher mLauncher;
    private FloatingHeaderView mParent;
    private LinearLayout mContainer;

    public PinnedAppsRowView(Context context) {
        this(context, null);
    }

    public PinnedAppsRowView(Context context, AttributeSet attrs) {
        super(context, attrs);
        
        Context c = context;
        while (c != null) {
            if (c instanceof Launcher) {
                mLauncher = (Launcher) c;
                break;
            }
            if (c instanceof android.content.ContextWrapper) {
                c = ((android.content.ContextWrapper) c).getBaseContext();
            } else {
                break;
            }
        }

        if (mLauncher == null) {
            setVisibility(View.GONE);
        }

        setFillViewport(true);
        setHorizontalScrollBarEnabled(false);
        setOverScrollMode(OVER_SCROLL_NEVER);

        mContainer = new LinearLayout(context);
        mContainer.setOrientation(LinearLayout.HORIZONTAL);
        addView(mContainer, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        if (mLauncher == null) {
            super.onMeasure(widthMeasureSpec, heightMeasureSpec);
            return;
        }

        int width = MeasureSpec.getSize(widthMeasureSpec);
        int availableWidth = width - getPaddingLeft() - getPaddingRight();
        
        DeviceProfile dp = mLauncher.getDeviceProfile();
        int maxCols = dp.inv.numAllAppsColumns;
        int count = mContainer.getChildCount();
        
        if (count > 0 && availableWidth > 0 && maxCols > 0) {
            int itemWidth = availableWidth / maxCols;
            for (int i = 0; i < count; i++) {
                View child = mContainer.getChildAt(i);
                LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) child.getLayoutParams();
                if (lp.width != itemWidth) {
                    lp.width = itemWidth;
                }
            }
        }
        
        super.onMeasure(widthMeasureSpec, heightMeasureSpec);
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        if (mLauncher == null) return;
        mLauncher.getSharedPrefs().registerOnSharedPreferenceChangeListener(this);
        if (mLauncher.getAppsView() != null) {
            mLauncher.getAppsView().getAppsStore().addUpdateListener(this);
        }
        reloadPinnedApps();
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        if (mLauncher == null) return;
        mLauncher.getSharedPrefs().unregisterOnSharedPreferenceChangeListener(this);
        if (mLauncher.getAppsView() != null) {
            mLauncher.getAppsView().getAppsStore().removeUpdateListener(this);
        }
    }

    @Override
    public void onAppsUpdated() {
        if (mLauncher == null) return;
        reloadPinnedApps();
    }

    @Override
    public void setup(FloatingHeaderView parent, FloatingHeaderRow[] allRows, boolean tabsHidden) {
        mParent = parent;
        mIsSetup = true;
        if (mLauncher != null) {
            reloadPinnedApps();
        }
    }

    public void reloadPinnedApps() {
        if (mLauncher == null) return;
        int prevExpectedHeight = mExpectedHeight;
        SharedPreferences prefs = mLauncher.getSharedPrefs();
        boolean enabled = prefs.getBoolean("pref_pinned_apps_enabled", false);

        if (!enabled) {
            mContainer.removeAllViews();
            mExpectedHeight = 0;
            setVisibility(View.GONE);
            if (mParent != null && mExpectedHeight != prevExpectedHeight) {
                mParent.onHeightUpdated();
            }
            return;
        }

        Set<String> pinnedComponents = prefs.getStringSet("pref_pinned_apps_list", new HashSet<>());
        if (pinnedComponents.isEmpty()) {
            mContainer.removeAllViews();
            mExpectedHeight = 0;
            setVisibility(View.GONE);
            if (mParent != null && mExpectedHeight != prevExpectedHeight) {
                mParent.onHeightUpdated();
            }
            return;
        }

        if (mLauncher.getAppsView() == null) {
            return;
        }

        AllAppsStore store = mLauncher.getAppsView().getAppsStore();
        AppInfo[] allApps = store.getApps();
        if (allApps.length == 0) {
            return;
        }

        List<AppInfo> pinnedApps = new ArrayList<>();
        for (AppInfo info : allApps) {
            if (pinnedComponents.contains(info.componentName.flattenToString())) {
                pinnedApps.add(info);
            }
        }

        int itemsToBind = pinnedApps.size();

        mContainer.removeAllViews();
        for (int i = 0; i < itemsToBind; i++) {
            BubbleTextView icon = (BubbleTextView) mLauncher.getLayoutInflater().inflate(
                    R.layout.all_apps_pinned_row_icon, mContainer, false);
            icon.applyFromApplicationInfo(pinnedApps.get(i));
            icon.setOnClickListener(mLauncher.getItemOnClickListener());
            icon.setOnLongClickListener(mLauncher.getAllAppsItemLongClickListener());
            int height = mLauncher.getDeviceProfile().getAllAppsProfile().getCellHeightPx();
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, height);
            mContainer.addView(icon, lp);
        }

        mExpectedHeight = itemsToBind > 0 ? getExpectedHeight() : 0;
        setVisibility(itemsToBind > 0 ? View.VISIBLE : View.GONE);

        if (mParent != null && mExpectedHeight != prevExpectedHeight) {
            mParent.onHeightUpdated();
        }
    }

    @Override
    public int getExpectedHeight() {
        if (mLauncher == null || mContainer.getChildCount() == 0) return 0;
        DeviceProfile deviceProfile = mLauncher.getDeviceProfile();
        int iconHeight = deviceProfile.getAllAppsProfile().getIconSizePx();
        int iconPadding = deviceProfile.getAllAppsProfile().getIconDrawablePaddingPx();
        int textHeight = com.android.launcher3.Utilities.calculateTextHeight(
                deviceProfile.getAllAppsProfile().getIconTextSizePx());
        int verticalPadding = getResources().getDimensionPixelSize(
                R.dimen.all_apps_predicted_icon_vertical_padding);
        int topRowExtraHeight = getResources().getDimensionPixelSize(
                R.dimen.all_apps_search_top_row_extra_height);
        int totalHeight = iconHeight + iconPadding + textHeight + verticalPadding * 2;
        int extraHeight = LauncherPrefs.ENABLE_TWOLINE_ALLAPPS_TOGGLE.get(getContext())
                ? (textHeight + topRowExtraHeight) : topRowExtraHeight;
        totalHeight += extraHeight;
        return totalHeight + getPaddingTop() + getPaddingBottom();
    }

    @Override
    public boolean shouldDraw() {
        return getVisibility() != View.GONE;
    }

    @Override
    public boolean hasVisibleContent() {
        return mContainer.getChildCount() > 0;
    }

    @Override
    public void setVerticalScroll(int scroll, boolean isScrolledOut) {
        if (!isScrolledOut) {
            setTranslationY(scroll);
        }
        setAlpha(isScrolledOut ? 0 : 1);
        if (getVisibility() != View.GONE) {
            com.android.launcher3.anim.AlphaUpdateListener.updateVisibility(this);
        }
    }

    @Override
    public Class<? extends FloatingHeaderRow> getTypeClass() {
        return PinnedAppsRowView.class;
    }

    @Override
    public View getFocusedChild() {
        if (mContainer.getChildCount() > 0) {
            return mContainer.getChildAt(0);
        }
        return null;
    }

    @Override
    public void onSharedPreferenceChanged(SharedPreferences sharedPreferences, String key) {
        if ("pref_pinned_apps_enabled".equals(key) || "pref_pinned_apps_list".equals(key)) {
            if (mLauncher != null) {
                reloadPinnedApps();
            }
        }
    }
}
