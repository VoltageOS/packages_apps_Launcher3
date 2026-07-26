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
import android.graphics.Canvas;
import android.graphics.Rect;
import android.util.AttributeSet;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.android.launcher3.DeviceProfile;
import com.android.launcher3.PagedView;
import com.android.launcher3.allapps.BaseAllAppsAdapter.AdapterItem;
import com.android.launcher3.pageindicators.PageIndicatorDots;
import com.android.launcher3.views.ActivityContext;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class OneUiPagedAllAppsView extends PagedView<PageIndicatorDots> {

    public interface OnActivePageChangedListener {
        void onActivePageChanged(@Nullable AllAppsRecyclerView recyclerView, int page);
    }

    private final ActivityContext mActivityContext;
    private final ArrayList<AdapterItem> mAdapterItems = new ArrayList<>();
    private final ArrayList<AllAppsRecyclerView> mPageRecyclerViews = new ArrayList<>();
    private final Rect mPagePadding = new Rect();

    @Nullable private OnActivePageChangedListener mOnActivePageChangedListener;
    @Nullable private BaseAllAppsAdapter mParentAdapter;
    private int mLastItemsPerPage = -1;
    private int mCachedAllAppsColumns = -1;

    public OneUiPagedAllAppsView(Context context) {
        this(context, null);
    }

    public OneUiPagedAllAppsView(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public OneUiPagedAllAppsView(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);
        mActivityContext = ActivityContext.lookupContext(context);
        setClipToPadding(false);
        setClipChildren(false);
        setPageSpacing(0);
    }

    public void setPageIndicator(@Nullable PageIndicatorDots pageIndicator) {
        mPageIndicator = pageIndicator;
        if (mPageIndicator != null) {
            mPageIndicator.setMarkersCount(getChildCount());
            mPageIndicator.setActiveMarker(getNextPage());
        }
    }

    public void setOnActivePageChangedListener(@Nullable OnActivePageChangedListener listener) {
        mOnActivePageChangedListener = listener;
    }

    /**
     * Sets the parent adapter used for creating and binding view holders.
     * This allows pages to render all view types (icons, headers, dividers, etc.)
     * by delegating to the adapter that already knows how to handle them.
     */
    public void setParentAdapter(@Nullable BaseAllAppsAdapter adapter) {
        mParentAdapter = adapter;
    }

    /**
     * Sets the full list of adapter items (icons, headers, dividers, etc.) and rebuilds pages.
     */
    public void setAdapterItems(@NonNull List<AdapterItem> items) {
        setAdapterItems(items, false);
    }

    /**
     * Sets the full list of adapter items and rebuilds pages, optionally preserving the current
     * page position (useful for incremental data updates that shouldn't jump to page 0).
     */
    public void setAdapterItems(@NonNull List<AdapterItem> items, boolean preservePage) {
        mAdapterItems.clear();
        mAdapterItems.addAll(items);
        rebuildPages(preservePage);
    }

    public void setPagePadding(@NonNull Rect padding) {
        mPagePadding.set(padding);
        applyPaddingToPages();
    }

    @Nullable
    public AllAppsRecyclerView getCurrentRecyclerView() {
        int page = getNextPage();
        return page >= 0 && page < mPageRecyclerViews.size() ? mPageRecyclerViews.get(page) : null;
    }

    @NonNull
    public List<AllAppsRecyclerView> getRecyclerViews() {
        return Collections.unmodifiableList(mPageRecyclerViews);
    }

    @Override
    protected void notifyPageSwitchListener(int prevPage) {
        super.notifyPageSwitchListener(prevPage);
        dispatchActivePageChanged();
    }

    @Override
    protected void dispatchDraw(Canvas canvas) {
        boolean isTablet = isTabletLayout();
        if (isTablet && (mPagePadding.left > 0 || mPagePadding.right > 0)) {
            canvas.save();
            canvas.clipRect(
                    getScrollX() + mPagePadding.left,
                    getScrollY(),
                    getScrollX() + getWidth() - mPagePadding.right,
                    getScrollY() + getHeight()
            );
            super.dispatchDraw(canvas);
            canvas.restore();
        } else {
            super.dispatchDraw(canvas);
        }
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        if (w != oldw || h != oldh) {
            mCachedAllAppsColumns = -1;
            post(() -> rebuildPages(true));
        }
    }

    @Override
    protected boolean canScroll(float absVScroll, float absHScroll) {
        return absHScroll > absVScroll && super.canScroll(absVScroll, absHScroll);
    }

    /**
     * Tablet check that does not depend on DeviceProfile/DeviceProperties internals, which
     * changed shape between platform releases.
     */
    private boolean isTabletLayout() {
        return getResources().getConfiguration().smallestScreenWidthDp >= 600;
    }

    /**
     * Resolves the number of All Apps columns. The DeviceProfile field holding this value has
     * been renamed/moved across releases, so look it up defensively and fall back to a sane
     * default instead of hard-coding a single field name.
     */
    private int getAllAppsColumns() {
        if (mCachedAllAppsColumns > 0) {
            return mCachedAllAppsColumns;
        }
        DeviceProfile dp = mActivityContext.getDeviceProfile();
        int cols = readIntMember(dp, "numShownAllAppsColumns");
        if (cols <= 0) {
            cols = readIntMember(dp, "numAllAppsColumns");
        }
        if (cols <= 0) {
            Object inv = readMember(dp, "inv");
            if (inv != null) {
                cols = readIntMember(inv, "numAllAppsColumns");
                if (cols <= 0) {
                    cols = readIntMember(inv, "numColumns");
                }
            }
        }
        if (cols <= 0) {
            cols = isTabletLayout() ? 6 : 4;
        }
        mCachedAllAppsColumns = cols;
        return cols;
    }

    @Nullable
    private static Object readMember(@Nullable Object target, String name) {
        if (target == null) {
            return null;
        }
        String getter = "get" + Character.toUpperCase(name.charAt(0)) + name.substring(1);
        for (Class<?> cls = target.getClass(); cls != null; cls = cls.getSuperclass()) {
            try {
                Field field = cls.getDeclaredField(name);
                field.setAccessible(true);
                return field.get(target);
            } catch (ReflectiveOperationException | RuntimeException ignored) {
                // Try the next lookup strategy.
            }
            for (String methodName : new String[] { name, getter }) {
                try {
                    Method method = cls.getDeclaredMethod(methodName);
                    method.setAccessible(true);
                    return method.invoke(target);
                } catch (ReflectiveOperationException | RuntimeException ignored) {
                    // Try the next lookup strategy.
                }
            }
        }
        return null;
    }

    private static int readIntMember(@Nullable Object target, String name) {
        Object value = readMember(target, name);
        return value instanceof Number ? ((Number) value).intValue() : -1;
    }

    private void rebuildPages(boolean preservePage) {
        int cols = Math.max(1, getAllAppsColumns());
        int rows = getRowsPerPage();
        int cellsPerPage = rows * cols;

        if (cellsPerPage <= 0 || getMeasuredWidth() <= 0 || getMeasuredHeight() <= 0) {
            return;
        }
        if (cellsPerPage == mLastItemsPerPage && getChildCount() > 0 && preservePage) {
            applyPaddingToPages();
            return;
        }
        mLastItemsPerPage = cellsPerPage;
        int pageToRestore = preservePage ? Math.min(getNextPage(), Math.max(0, getPageCount() - 1)) : 0;

        removeAllViews();
        mPageRecyclerViews.clear();

        List<List<AdapterItem>> pages = paginateItems(mAdapterItems, cols, cellsPerPage);
        int adjustedCellHeight = getAdjustedCellHeight();

        for (int pageIdx = 0; pageIdx < pages.size(); pageIdx++) {
            List<AdapterItem> pageItems = pages.get(pageIdx);
            AllAppsRecyclerView recyclerView = new AllAppsRecyclerView(getContext()) {
                @Override
                public void scrollToTop() {
                    if (getScrollbar() != null) {
                        getScrollbar().setThumbOffsetY(0);
                    }
                    RecyclerView.LayoutManager layoutManager = getLayoutManager();
                    if (layoutManager instanceof GridLayoutManager) {
                        ((GridLayoutManager) layoutManager).scrollToPositionWithOffset(0, getPaddingTop());
                    }
                }
            };
            recyclerView.setId(View.generateViewId());
            recyclerView.setLayoutParams(new LayoutParams(LayoutParams.MATCH_PARENT,
                    LayoutParams.MATCH_PARENT));
            recyclerView.setClipToPadding(true);
            recyclerView.setOverScrollMode(OVER_SCROLL_NEVER);
            recyclerView.setVerticalScrollBarEnabled(false);
            recyclerView.setHasFixedSize(true);
            recyclerView.setItemAnimator(null);

            GridLayoutManager glm = new GridLayoutManager(getContext(), cols) {
                @Override
                public boolean canScrollVertically() {
                    return false;
                }
            };
            glm.setSpanSizeLookup(new PageSpanSizeLookup(pageItems, cols));
            recyclerView.setLayoutManager(glm);

            recyclerView.setAdapter(new PageSliceAdapter(pageItems, adjustedCellHeight));
            mPageRecyclerViews.add(recyclerView);
            addView(recyclerView);
        }

        applyPaddingToPages();
        setCurrentPage(Math.min(pageToRestore, Math.max(0, getChildCount() - 1)));
        if (mPageIndicator != null) {
            mPageIndicator.setMarkersCount(getChildCount());
            mPageIndicator.setActiveMarker(getNextPage());
        }
        dispatchActivePageChanged();
    }

    private List<List<AdapterItem>> paginateItems(List<AdapterItem> items, int cols, int cellsPerPage) {
        List<List<AdapterItem>> pages = new ArrayList<>();
        if (items.isEmpty()) {
            pages.add(new ArrayList<>());
            return pages;
        }

        List<AdapterItem> currentPage = new ArrayList<>();
        int cellsUsed = 0;

        for (AdapterItem item : items) {
            int cost;
            if (BaseAllAppsAdapter.isIconViewType(item.viewType)) {
                cost = 1;
            } else {
                int remainder = cellsUsed % cols;
                if (remainder != 0) {
                    cellsUsed += (cols - remainder);
                }
                cost = cols;
            }

            if (cellsUsed + cost > cellsPerPage && !currentPage.isEmpty()) {
                pages.add(currentPage);
                currentPage = new ArrayList<>();
                cellsUsed = 0;
            }

            currentPage.add(item);
            cellsUsed += cost;
        }

        if (!currentPage.isEmpty()) {
            pages.add(currentPage);
        }

        return pages;
    }

    private void applyPaddingToPages() {
        for (AllAppsRecyclerView recyclerView : mPageRecyclerViews) {
            recyclerView.setPadding(mPagePadding.left, mPagePadding.top,
                    mPagePadding.right, mPagePadding.bottom);
        }
    }

    private void dispatchActivePageChanged() {
        if (mOnActivePageChangedListener != null) {
            mOnActivePageChangedListener.onActivePageChanged(getCurrentRecyclerView(), getNextPage());
        }
    }

    private int getRowsPerPage() {
        int defaultRows = Math.max(1, mActivityContext.getDeviceProfile().inv.numRows);
        int availableHeight = Math.max(0, getMeasuredHeight() - mPagePadding.top - mPagePadding.bottom);
        int baseCellHeight = Math.max(1, mActivityContext.getDeviceProfile().getAllAppsProfile().getCellHeightPx());

        if (availableHeight > 0) {
            int minSafeCellHeight = Math.max(1, (int) (baseCellHeight * 0.7f));
            int maxFitRows = Math.max(1, availableHeight / minSafeCellHeight);
            return Math.min(defaultRows, maxFitRows);
        }
        return defaultRows;
    }

    private int getAdjustedCellHeight() {
        int height = getMeasuredHeight();
        int baseCellHeight = Math.max(1, mActivityContext.getDeviceProfile().getAllAppsProfile().getCellHeightPx());
        if (height <= 0) {
            return baseCellHeight;
        }
        int rows = getRowsPerPage();
        if (rows <= 0) {
            return baseCellHeight;
        }
        int availableHeight = Math.max(0, height - mPagePadding.top - mPagePadding.bottom);
        return availableHeight / rows;
    }

    private static class PageSpanSizeLookup extends GridLayoutManager.SpanSizeLookup {
        private final List<AdapterItem> mItems;
        private final int mSpanCount;

        PageSpanSizeLookup(List<AdapterItem> items, int spanCount) {
            mItems = items;
            mSpanCount = spanCount;
        }

        @Override
        public int getSpanSize(int position) {
            if (position >= mItems.size()) {
                return mSpanCount;
            }
            return BaseAllAppsAdapter.isIconViewType(mItems.get(position).viewType)
                    ? 1 : mSpanCount;
        }
    }

    private class PageSliceAdapter extends RecyclerView.Adapter<BaseAllAppsAdapter.ViewHolder> {
        private final List<AdapterItem> mSlice;
        private final int mCellHeight;

        PageSliceAdapter(List<AdapterItem> slice, int cellHeight) {
            mSlice = slice;
            mCellHeight = cellHeight;
        }

        @Override
        public int getItemCount() {
            return mSlice.size();
        }

        @Override
        public int getItemViewType(int position) {
            return mSlice.get(position).viewType;
        }

        @NonNull
        @Override
        public BaseAllAppsAdapter.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            if (mParentAdapter != null) {
                BaseAllAppsAdapter.ViewHolder holder = mParentAdapter.onCreateViewHolder(parent, viewType);
                if (BaseAllAppsAdapter.isIconViewType(viewType)) {
                    ViewGroup.LayoutParams lp = holder.itemView.getLayoutParams();
                    if (lp != null) {
                        lp.height = mCellHeight;
                        holder.itemView.setLayoutParams(lp);
                    }
                }
                return holder;
            }
            return new BaseAllAppsAdapter.ViewHolder(new View(getContext()));
        }

        @Override
        public void onBindViewHolder(@NonNull BaseAllAppsAdapter.ViewHolder holder, int position) {
            if (mParentAdapter == null) {
                return;
            }
            AdapterItem item = mSlice.get(position);
            int globalPos = findGlobalPosition(item);
            if (globalPos >= 0) {
                mParentAdapter.onBindViewHolder(holder, globalPos);
            }
        }

        @Override
        public boolean onFailedToRecycleView(@NonNull BaseAllAppsAdapter.ViewHolder holder) {
            return true;
        }

        private int findGlobalPosition(AdapterItem item) {
            if (mParentAdapter == null) {
                return -1;
            }
            List<AdapterItem> globalItems = mParentAdapter.mApps.getAdapterItems();
            for (int i = 0; i < globalItems.size(); i++) {
                if (globalItems.get(i) == item) {
                    return i;
                }
            }
            return -1;
        }
    }
}
