/*
 * Copyright (C) 2017 The Android Open Source Project
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
package com.android.launcher3.allapps.search;

import static android.view.View.MeasureSpec.EXACTLY;
import static android.view.View.MeasureSpec.getSize;
import static android.view.View.MeasureSpec.makeMeasureSpec;

import static com.android.launcher3.Utilities.prefixTextWithIcon;
import static com.android.launcher3.icons.IconNormalizer.ICON_VISIBLE_AREA_FACTOR;

import android.content.Context;
import android.content.Intent;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.text.Editable;
import android.text.Selection;
import android.text.SpannableStringBuilder;
import android.text.TextWatcher;
import android.text.method.TextKeyListener;
import android.util.AttributeSet;
import android.view.KeyEvent;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup.MarginLayoutParams;
import android.widget.PopupMenu;

import com.android.launcher3.DeviceProfile;
import com.android.launcher3.ExtendedEditText;
import com.android.launcher3.Insettable;
import com.android.launcher3.LauncherPrefs;
import com.android.launcher3.R;
import com.android.launcher3.Utilities;
import com.android.launcher3.allapps.ActivityAllAppsContainerView;
import com.android.launcher3.allapps.AllAppsStore;
import com.android.launcher3.allapps.BaseAllAppsAdapter.AdapterItem;
import com.android.launcher3.allapps.PrivateProfileManager;
import com.android.launcher3.allapps.SearchUiManager;
import com.android.launcher3.search.SearchCallback;
import com.android.launcher3.util.ApiWrapper;
import com.android.launcher3.util.Themes;
import com.android.launcher3.views.ActivityContext;

import java.util.ArrayList;

/**
 * Layout to contain the All-apps search UI.
 */
public class AppsSearchContainerLayout extends ExtendedEditText
        implements SearchUiManager, SearchCallback<AdapterItem>,
        AllAppsStore.OnUpdateListener, Insettable {

    private boolean mIsSearchSessionActive = false;

    private final ActivityContext mLauncher;
    private final AllAppsSearchBarController mSearchBarController;
    private final SpannableStringBuilder mSearchQueryBuilder;

    private ActivityAllAppsContainerView<?> mAppsView;

    // The amount of pixels to shift down and overlap with the rest of the content.
    private final int mContentOverlap;

    public AppsSearchContainerLayout(Context context) {
        this(context, null);
    }

    public AppsSearchContainerLayout(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public AppsSearchContainerLayout(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);

        mLauncher = ActivityContext.lookupContext(context);
        mSearchBarController = new AllAppsSearchBarController();

        mSearchQueryBuilder = new SpannableStringBuilder();
        Selection.setSelection(mSearchQueryBuilder, 0);

        addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                if (s != null && !s.isEmpty()) {
                    mIsSearchSessionActive = true;
                }
            }

            @Override
            public void afterTextChanged(Editable s) {
            }
        });

        mContentOverlap =
                getResources().getDimensionPixelSize(R.dimen.all_apps_search_bar_content_overlap);

        setUpSortingOptionsIcon(context);
    }

    /** Shows the overflow icon that opens the app drawer sorting options. */
    private void setUpSortingOptionsIcon(Context context) {
        Drawable optionsIcon = context.getDrawable(R.drawable.ic_more_vert_dots);
        if (optionsIcon == null) {
            return;
        }
        optionsIcon.setTint(Themes.getAttrColor(context, android.R.attr.textColorPrimary));
        setCompoundDrawablesRelativeWithIntrinsicBounds(null, null, optionsIcon, null);
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        if(mAppsView != null)
            mAppsView.getAppsStore().addUpdateListener(this);
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        if(mAppsView != null)
            mAppsView.getAppsStore().removeUpdateListener(this);
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        // Update the width to match the grid padding
        if (mAppsView == null) {
            super.onMeasure(widthMeasureSpec, heightMeasureSpec);
            return;
        }
        DeviceProfile dp = mLauncher.getDeviceProfile();
        int myRequestedWidth = getSize(widthMeasureSpec);
        View widthSource = mAppsView.getActiveRecyclerView();
        if (widthSource == null) {
            widthSource = mAppsView.getAppsRecyclerViewContainer();
        }
        if (widthSource == null) {
            super.onMeasure(widthMeasureSpec, heightMeasureSpec);
            return;
        }
        int rowWidth = myRequestedWidth - widthSource.getPaddingLeft()
                - widthSource.getPaddingRight();

        int cellWidth = DeviceProfile.calculateCellWidth(rowWidth,
                dp.getWorkspaceProfile().getCellLayoutBorderSpacePx().x,
                dp.getHotseatProfile().getNumShownIcons());
        int iconVisibleSize =
                Math.round(ICON_VISIBLE_AREA_FACTOR * dp.getWorkspaceProfile().getIconSizePx());
        int iconPadding = cellWidth - iconVisibleSize;

        int myWidth = rowWidth - iconPadding + getPaddingLeft() + getPaddingRight();
        super.onMeasure(makeMeasureSpec(myWidth, EXACTLY), heightMeasureSpec);
    }

    @Override
    protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        super.onLayout(changed, left, top, right, bottom);

        // Shift the widget horizontally so that its centered in the parent (b/63428078)
        View parent = (View) getParent();
        int availableWidth = parent.getWidth() - parent.getPaddingLeft() - parent.getPaddingRight();
        int myWidth = right - left;
        int expectedLeft = parent.getPaddingLeft() + (availableWidth - myWidth) / 2;
        int shift = expectedLeft - left;
        setTranslationX(shift);

        offsetTopAndBottom(mContentOverlap);
    }

    @Override
    public void initializeSearch(ActivityAllAppsContainerView<?> appsView) {
        mAppsView = appsView;
        mSearchBarController.initialize(
                new DefaultAppSearchAlgorithm(getContext(), mLauncher.getUiExecutor(), true),
                this, mLauncher, this);
    }

    @Override
    public void onAppsUpdated() {
        mSearchBarController.refreshSearchResult();
    }

    @Override
    public void resetSearch() {
        // The doc comment for this method in SearchUiManager says this should close any active
        // search session.
        mIsSearchSessionActive = false;
        mSearchBarController.reset();
    }

    @Override
    public boolean isSearchQueryEmpty() {
        String query = Utilities.trim(getEditableText().toString());
        return query.isEmpty();
    }

    @Override
    public boolean shouldInterceptBackButton() {
        return mIsSearchSessionActive;
    }

    @Override
    public void preDispatchKeyEvent(KeyEvent event) {
        // Determine if the key event was actual text, if so, focus the search bar and then dispatch
        // the key normally so that it can process this key event
        if (!mSearchBarController.isSearchFieldFocused() &&
                event.getAction() == KeyEvent.ACTION_DOWN) {
            final int unicodeChar = event.getUnicodeChar();
            final boolean isKeyNotWhitespace = unicodeChar > 0 &&
                    !Character.isWhitespace(unicodeChar) && !Character.isSpaceChar(unicodeChar);
            if (isKeyNotWhitespace) {
                boolean gotKey = TextKeyListener.getInstance().onKeyDown(this, mSearchQueryBuilder,
                        event.getKeyCode(), event);
                if (gotKey && mSearchQueryBuilder.length() > 0) {
                    mSearchBarController.focusSearchField();
                }
            }
        }
    }

    @Override
    public void onSearchResult(String query, ArrayList<AdapterItem> items) {
        if (query.equalsIgnoreCase(getContext().getString(R.string.private_space_label))) {
            privateSpaceQuery();
            return;
        }
        if (items != null) {
            mAppsView.setSearchResults(items);
        }
    }

    @Override
    public void clearSearchResult() {
        // The doc comment for clearSearchResult in the SearchCallback interface says:
        // "Called when the search results should be cleared." This is also called when the search
        // query is empty by AllAppsSearchBarController#afterTextChanged.

        // Clear the search query
        mSearchQueryBuilder.clear();
        mSearchQueryBuilder.clearSpans();
        Selection.setSelection(mSearchQueryBuilder, 0);

        // The doc comment for ActivityAllAppsContainerView#onClearSearchResult says, "Invoke when
        // the current search session is finished," but this is being called in a method called
        // clearSearchResult. Finishing a search session and clearing the search result should have
        // different semantics.
        //
        // NexusLauncher has similar logic guarding this call in
        // UniversalSearchInputView#clearSearchResult.
        if (!mIsSearchSessionActive) {
            mAppsView.onClearSearchResult();
        } else {
            // Must do this or else the latest non-empty search results will remain. This is
            // normally handled as a result of calling onClearSearchResult
            mAppsView.setSearchResults(null);
        }
    }

    @Override
    public void setInsets(Rect insets) {
        MarginLayoutParams mlp = (MarginLayoutParams) getLayoutParams();
        mlp.topMargin = insets.top;
        requestLayout();
    }

    @Override
    public ExtendedEditText getEditText() {
        return this;
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (event.getAction() == MotionEvent.ACTION_UP) {
            Drawable endDrawable = getCompoundDrawablesRelative()[2]; // end drawable
            if (endDrawable != null) {
                boolean isRtl = getLayoutDirection() == LAYOUT_DIRECTION_RTL;
                float x = event.getX();
                boolean isOptionsClick = false;
                if (!isRtl && x >= (getWidth() - getPaddingRight() - endDrawable.getIntrinsicWidth() - 30)) {
                    isOptionsClick = true;
                } else if (isRtl && x <= (getPaddingLeft() + endDrawable.getIntrinsicWidth() + 30)) {
                    isOptionsClick = true;
                }

                if (isOptionsClick) {
                    showSortingOptions();
                    return true;
                }
            }
        }
        return super.onTouchEvent(event);
    }

    private void showSortingOptions() {
        PopupMenu popup = new PopupMenu(getContext(), this, android.view.Gravity.END);
        popup.getMenu().add(0, 0, 0, getContext().getString(R.string.sort_alphabetical));
        popup.getMenu().add(0, 1, 1, getContext().getString(R.string.sort_install_date));
        popup.getMenu().add(0, 2, 2, getContext().getString(R.string.sort_usage));

        popup.setOnMenuItemClickListener((MenuItem item) -> {
            int sortMode = item.getItemId();
            if (sortMode == 2) {
                android.app.AppOpsManager appOps = (android.app.AppOpsManager) getContext().getSystemService(Context.APP_OPS_SERVICE);
                int mode = appOps.checkOpNoThrow(android.app.AppOpsManager.OPSTR_GET_USAGE_STATS, android.os.Process.myUid(), getContext().getPackageName());
                if (mode == android.app.AppOpsManager.MODE_DEFAULT) {
                    mode = getContext().checkCallingOrSelfPermission(android.Manifest.permission.PACKAGE_USAGE_STATS) == android.content.pm.PackageManager.PERMISSION_GRANTED
                            ? android.app.AppOpsManager.MODE_ALLOWED
                            : android.app.AppOpsManager.MODE_IGNORED;
                }
                if (mode != android.app.AppOpsManager.MODE_ALLOWED) {
                    Intent intent = new Intent(android.provider.Settings.ACTION_USAGE_ACCESS_SETTINGS);
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    getContext().startActivity(intent);
                    return true;
                }
            }
            LauncherPrefs.get(getContext()).put(LauncherPrefs.APP_DRAWER_SORT_MODE, sortMode);
            if (mAppsView != null) {
                mAppsView.getAppsStore().notifyUpdate();
            }
            return true;
        });
        popup.show();
    }

    private void privateSpaceQuery() {
        PrivateProfileManager privateProfileManager = mAppsView.getPrivateProfileManager();
        if (privateProfileManager.isPrivateSpaceHidden()) {
            privateProfileManager.setQuietMode(false);
        } else if (!mAppsView.hasPrivateProfile()) {
            final Intent privateSpaceSettingsIntent =
                    ApiWrapper.INSTANCE.get(getContext()).getPrivateSpaceSettingsIntent();
            if (privateSpaceSettingsIntent != null) {
                mLauncher.startActivitySafely(mAppsView, privateSpaceSettingsIntent, null);
            }
        }
    }
}
