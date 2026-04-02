/*
 * Copyright (C) 2011 The Android Open Source Project
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

package com.android.launcher3;

import static android.view.View.MeasureSpec.makeMeasureSpec;

import static com.android.launcher3.LauncherAnimUtils.VIEW_TRANSLATE_X;
import static com.android.launcher3.util.MultiTranslateDelegate.INDEX_BUBBLE_ADJUSTMENT_ANIM;

import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Rect;
import android.os.SystemClock;
import android.util.AttributeSet;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewDebug;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import androidx.annotation.IntDef;
import androidx.annotation.Nullable;

import com.android.launcher3.ShortcutAndWidgetContainer.TranslationProvider;
import com.android.launcher3.celllayout.CellLayoutLayoutParams;
import com.android.launcher3.dock.DockSlot;
import com.android.launcher3.dock.DockSuggestionsHelper;
import com.android.launcher3.dock.DockSlotView;
import com.android.launcher3.model.data.ItemInfo;
import com.android.launcher3.util.HorizontalInsettableView;
import com.android.launcher3.util.LauncherBindableItemsContainer.ItemOperator;
import com.android.launcher3.util.MultiPropertyFactory;
import com.android.launcher3.util.MultiPropertyFactory.MultiProperty;
import com.android.launcher3.util.MultiTranslateDelegate;
import com.android.launcher3.util.MultiValueAlpha;
import com.android.launcher3.views.ActivityContext;

import java.io.PrintWriter;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.ArrayList;
import java.util.List;

/**
 * View class that represents the bottom row of the home screen.
 */
public class Hotseat extends CellLayout implements Insettable {

    public static final int ALPHA_CHANNEL_TASKBAR_ALIGNMENT = 0;
    public static final int ALPHA_CHANNEL_PREVIEW_RENDERER = 1;
    public static final int ALPHA_CHANNEL_TASKBAR_STASH = 2;
    public static final int ALPHA_CHANNEL_ASSISTANT_VISIBILITY = 3;
    public static final int ALPHA_CHANNEL_CHANNELS_COUNT = 4;

    @Retention(RetentionPolicy.RUNTIME)
    @IntDef({ALPHA_CHANNEL_TASKBAR_ALIGNMENT, ALPHA_CHANNEL_PREVIEW_RENDERER,
            ALPHA_CHANNEL_TASKBAR_STASH, ALPHA_CHANNEL_ASSISTANT_VISIBILITY})
    public @interface HotseatQsbAlphaId {
    }

    public static final int ICONS_TRANSLATION_X_NAV_BAR_ALIGNMENT = 0;
    public static final int ICONS_TRANSLATION_X_CHANNELS_COUNT = 1;

    @Retention(RetentionPolicy.RUNTIME)
    @IntDef({ICONS_TRANSLATION_X_NAV_BAR_ALIGNMENT})
    public @interface IconsTranslationX {
    }

    // Ratio of empty space, qsb should take up to appear visually centered.
    public static final float QSB_CENTER_FACTOR = .325f;
    private static final int BUBBLE_BAR_ADJUSTMENT_ANIMATION_DURATION_MS = 250;

    @ViewDebug.ExportedProperty(category = "launcher")
    private boolean mHasVerticalHotseat;
    private Workspace<?> mWorkspace;
    private boolean mSendTouchToWorkspace;
    private final MultiValueAlpha mIconsAlphaChannels;
    private final MultiValueAlpha mQsbAlphaChannels;

    private @Nullable MultiProperty mQsbTranslationX;

    private final MultiPropertyFactory mIconsTranslationXFactory;

    private final View mQsb;

    private final List<DockSlotView> mRecentSlotViews = new ArrayList<>();
    private long mLastDockApplyTime;

    public Hotseat(Context context) {
        this(context, null);
    }

    public Hotseat(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public Hotseat(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);
        if (Utilities.showQSB(context)) {
            mQsb = LayoutInflater.from(context).inflate(R.layout.search_container_hotseat, this,
                    false);
        } else {
            mQsb = LayoutInflater.from(context).inflate(R.layout.empty_view, this,
                    false);
        }

        addView(mQsb);
        mIconsAlphaChannels = new MultiValueAlpha(getShortcutsAndWidgets(),
                ALPHA_CHANNEL_CHANNELS_COUNT);
        mIconsAlphaChannels.setUpdateVisibility(true);
        if (mQsb instanceof Reorderable qsbReorderable) {
            mQsbTranslationX = qsbReorderable.getTranslateDelegate()
                    .getTranslationX(MultiTranslateDelegate.INDEX_NAV_BAR_ANIM);
        }
        mIconsTranslationXFactory = new MultiPropertyFactory<>(getShortcutsAndWidgets(),
                VIEW_TRANSLATE_X, ICONS_TRANSLATION_X_CHANNELS_COUNT, Float::sum);
        mQsbAlphaChannels = new MultiValueAlpha(mQsb, ALPHA_CHANNEL_CHANNELS_COUNT);
        mQsbAlphaChannels.setUpdateVisibility(true);
    }

    /** Provides translation X for hotseat icons for the channel. */
    public MultiProperty getIconsTranslationX(@IconsTranslationX int channelId) {
        return mIconsTranslationXFactory.get(channelId);
    }

    /** Provides translation X for hotseat Qsb. */
    @Nullable
    public MultiProperty getQsbTranslationX() {
        return mQsbTranslationX;
    }

    /**
     * Returns orientation specific cell X given invariant order in the hotseat
     */
    public int getCellXFromOrder(int rank) {
        return mHasVerticalHotseat ? 0 : rank;
    }

    /**
     * Returns orientation specific cell Y given invariant order in the hotseat
     */
    public int getCellYFromOrder(int rank) {
        return mHasVerticalHotseat ? (getCountY() - (rank + 1)) : 0;
    }

    boolean isHasVerticalHotseat() {
        return mHasVerticalHotseat;
    }

    public void resetLayout(boolean hasVerticalHotseat) {
        ActivityContext activityContext = ActivityContext.lookupContext(getContext());
        boolean bubbleBarEnabled = activityContext.isBubbleBarEnabled();
        boolean hasBubbles = activityContext.hasBubbles();
        removeAllViewsInLayout();
        mRecentSlotViews.clear();
        mLastDockApplyTime = SystemClock.uptimeMillis();
        mHasVerticalHotseat = hasVerticalHotseat;
        DeviceProfile dp = mActivity.getDeviceProfile();

        if (bubbleBarEnabled) {
            if (dp.shouldAdjustHotseatForBubbleBar(getContext(), hasBubbles)) {
                getShortcutsAndWidgets().setTranslationProvider(
                        cellX -> dp.getHotseatAdjustedTranslation(getContext(), cellX));
                if (mQsb instanceof HorizontalInsettableView) {
                    HorizontalInsettableView insettableQsb = (HorizontalInsettableView) mQsb;
                    final float insetFraction =
                            (float) dp.getWorkspaceIconProfile().getIconSizePx() / dp.hotseatQsbWidth;
                    // post this to the looper so that QSB has a chance to redraw itself, e.g.
                    // after device rotation
                    mQsb.post(() -> insettableQsb.setHorizontalInsets(insetFraction));
                }
            } else {
                getShortcutsAndWidgets().setTranslationProvider(null);
                if (mQsb instanceof HorizontalInsettableView) {
                    ((HorizontalInsettableView) mQsb).setHorizontalInsets(0);
                }
            }
        }

        resetCellSize(dp);
        if (hasVerticalHotseat) {
            setGridSize(1, dp.numShownHotseatIcons);
        } else {
            setGridSize(dp.numShownHotseatIcons, 1);
        }
    }

    /**
     * Adjust the hotseat icons for the bubble bar.
     *
     * <p>When the bubble bar becomes visible, if needed, this method animates the hotseat icons
     * to reduce the spacing between them and make room for the bubble bar. The QSB width is
     * animated as well to align with the hotseat icons.
     *
     * <p>When the bubble bar goes away, any adjustments that were previously made are reversed.
     */
    public void adjustForBubbleBar(boolean isBubbleBarVisible) {
        DeviceProfile dp = mActivity.getDeviceProfile();
        boolean shouldAdjust = isBubbleBarVisible
                && dp.shouldAdjustHotseatOrQsbForBubbleBar(getContext());
        boolean shouldAdjustHotseat = shouldAdjust
                && dp.shouldAlignBubbleBarWithHotseat();
        ShortcutAndWidgetContainer icons = getShortcutsAndWidgets();
        // update the translation provider for future layout passes of hotseat icons.
        if (shouldAdjustHotseat) {
            icons.setTranslationProvider(
                    cellX -> dp.getHotseatAdjustedTranslation(getContext(), cellX));
        } else {
            icons.setTranslationProvider(null);
        }
        AnimatorSet animatorSet = new AnimatorSet();
        for (int i = 0; i < icons.getChildCount(); i++) {
            View child = icons.getChildAt(i);
            if (child.getLayoutParams() instanceof CellLayoutLayoutParams lp) {
                float tx = shouldAdjustHotseat
                        ? dp.getHotseatAdjustedTranslation(getContext(), lp.getCellX()) : 0;
                if (child instanceof Reorderable) {
                    MultiTranslateDelegate mtd = ((Reorderable) child).getTranslateDelegate();
                    animatorSet.play(
                            mtd.getTranslationX(INDEX_BUBBLE_ADJUSTMENT_ANIM).animateToValue(tx));
                } else {
                    animatorSet.play(ObjectAnimator.ofFloat(child, VIEW_TRANSLATE_X, tx));
                }
            }
        }
        //TODO(b/381109832) refactor & simplify adjustment logic
        boolean shouldAdjustQsb =
                shouldAdjustHotseat || (shouldAdjust && dp.shouldAlignBubbleBarWithQSB());
        if (mQsb instanceof HorizontalInsettableView horizontalInsettableQsb) {
            final float currentInsetFraction = horizontalInsettableQsb.getHorizontalInsets();
            final float targetInsetFraction = shouldAdjustQsb
                    ? (float) dp.getWorkspaceIconProfile().getIconSizePx() / dp.hotseatQsbWidth : 0;
            ValueAnimator qsbAnimator =
                    ValueAnimator.ofFloat(currentInsetFraction, targetInsetFraction);
            qsbAnimator.addUpdateListener(animation -> {
                float insetFraction = (float) animation.getAnimatedValue();
                horizontalInsettableQsb.setHorizontalInsets(insetFraction);
            });
            animatorSet.play(qsbAnimator);
        }
        animatorSet.setDuration(BUBBLE_BAR_ADJUSTMENT_ANIMATION_DURATION_MS).start();
    }

    @Override
    protected int getTranslationXForCell(int cellX, int cellY) {
        TranslationProvider translationProvider = getShortcutsAndWidgets().getTranslationProvider();
        if (translationProvider == null) return 0;
        return (int) translationProvider.getTranslationX(cellX);
    }

    @Override
    public void setInsets(Rect insets) {
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) getLayoutParams();
        DeviceProfile grid = mActivity.getDeviceProfile();

        if (grid.isVerticalBarLayout()) {
            mQsb.setVisibility(View.GONE);
            lp.height = ViewGroup.LayoutParams.MATCH_PARENT;
            if (grid.isSeascape()) {
                lp.gravity = Gravity.LEFT;
                lp.width = grid.hotseatBarSizePx + insets.left;
            } else {
                lp.gravity = Gravity.RIGHT;
                lp.width = grid.hotseatBarSizePx + insets.right;
            }
        } else {
            mQsb.setVisibility(View.VISIBLE);
            lp.gravity = Gravity.BOTTOM;
            lp.width = ViewGroup.LayoutParams.MATCH_PARENT;
            lp.height = grid.hotseatBarSizePx;
        }

        Rect padding = grid.getHotseatLayoutPadding(getContext());
        setPadding(padding.left, padding.top, padding.right, padding.bottom);
        setLayoutParams(lp);
        InsettableFrameLayout.dispatchInsets(this, insets);
    }

    public void setWorkspace(Workspace<?> w) {
        mWorkspace = w;
        setCellLayoutContainer(w);
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent ev) {
        // We allow horizontal workspace scrolling from within the Hotseat. We do this by delegating
        // touch intercept the Workspace, and if it intercepts, delegating touch to the Workspace
        // for the remainder of the this input stream.
        int yThreshold = getMeasuredHeight() - getPaddingBottom();
        if (mWorkspace != null && ev.getY() <= yThreshold) {
            mSendTouchToWorkspace = mWorkspace.onInterceptTouchEvent(ev);
            return mSendTouchToWorkspace;
        }
        return false;
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        // See comment in #onInterceptTouchEvent
        if (mSendTouchToWorkspace) {
            final int action = event.getAction();
            switch (action & MotionEvent.ACTION_MASK) {
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    mSendTouchToWorkspace = false;
            }
            return mWorkspace.onTouchEvent(event);
        }
        // Always let touch follow through to Workspace.
        return false;
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec);

        DeviceProfile dp = mActivity.getDeviceProfile();
        mQsb.measure(makeMeasureSpec(dp.hotseatQsbWidth, MeasureSpec.EXACTLY),
                makeMeasureSpec(dp.getHotseatProfile().getQsbHeight(), MeasureSpec.EXACTLY));
    }

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        super.onLayout(changed, l, t, r, b);

        int qsbMeasuredWidth = mQsb.getMeasuredWidth();
        int left;
        DeviceProfile dp = mActivity.getDeviceProfile();
        if (dp.isQsbInline) {
            int qsbSpace = dp.hotseatBorderSpace;
            left = Utilities.isRtl(getResources()) ? r - getPaddingRight() + qsbSpace
                    : l + getPaddingLeft() - qsbMeasuredWidth - qsbSpace;
        } else {
            left = (r - l - qsbMeasuredWidth) / 2;
        }
        int right = left + qsbMeasuredWidth;

        int bottom = b - t - dp.getQsbOffsetY();
        int top = bottom - dp.getHotseatProfile().getQsbHeight();
        mQsb.layout(left, top, right, bottom);
    }

    /**
     * Sets the alpha value of the specified alpha channel of just our ShortcutAndWidgetContainer.
     */
    public void setIconsAlpha(float alpha, @HotseatQsbAlphaId int channelId) {
        getIconsAlpha(channelId).setValue(alpha);
    }

    /**
     * Sets the alpha value of just our QSB.
     */
    public void setQsbAlpha(float alpha, @HotseatQsbAlphaId int channelId) {
        getQsbAlpha(channelId).setValue(alpha);
    }

    /** Returns the alpha channel for ShortcutAndWidgetContainer */
    public MultiProperty getIconsAlpha(@HotseatQsbAlphaId int channelId) {
        return mIconsAlphaChannels.get(channelId);
    }

    /** Returns the alpha channel for Qsb */
    public MultiProperty getQsbAlpha(@HotseatQsbAlphaId int channelId) {
        return mQsbAlphaChannels.get(channelId);
    }

    /**
     * Returns the QSB inside hotseat
     */
    public View getQsb() {
        return mQsb;
    }

    @Nullable
    @Override
    public View mapOverItems(ItemOperator op) {
        if (Flags.enableQsbOnHotseat()
                && mQsb != null
                && mQsb.getTag() instanceof ItemInfo info
                && op.evaluate(info, mQsb)) {
            return mQsb;
        }
        return super.mapOverItems(op);
    }

    public void applyDockSlots(@androidx.annotation.NonNull List<? extends DockSlot> slots) {
        ShortcutAndWidgetContainer swc = getShortcutsAndWidgets();
        boolean suppressAnimations =
                SystemClock.uptimeMillis() - mLastDockApplyTime < RAPID_UPDATE_WINDOW_MS;
        mLastDockApplyTime = SystemClock.uptimeMillis();
        Launcher launcher = Launcher.getLauncher(getContext());
        List<DockSlotView> outgoing = new java.util.ArrayList<>();
        for (DockSlotView view : mRecentSlotViews) {
            if (view.getParent() == swc) {
                outgoing.add(view);
            }
        }
        mRecentSlotViews.clear();

        if (slots.isEmpty() || !DockSuggestionsHelper.isFeatureEnabled(getContext())) {
            animateOutAll(swc, outgoing, suppressAnimations, () -> {});
            return;
        }

        java.util.List<PendingDockSuggestion> pending = buildPendingDockSuggestions(slots, swc);
        ExistingDockViews existingViews = new ExistingDockViews(outgoing);
        existingViews.captureExactMatches(pending);

        for (PendingDockSuggestion suggestion : pending) {
            DockSlotView existing = existingViews.takeBestMatch(suggestion);
            if (existing == null) {
                DockSlotView view = createSuggestionView(suggestion, launcher);
                swc.addView(view, suggestion.layoutParams);
                mRecentSlotViews.add(view);
                if (!suppressAnimations) {
                    view.animateIn(0);
                }
                continue;
            }

            rebindSuggestionView(existing, suggestion, launcher, suppressAnimations);
            mRecentSlotViews.add(existing);
        }

        java.util.List<DockSlotView> toRemove = existingViews.getUnmatchedViews();
        animateOutAll(swc, toRemove, suppressAnimations, () -> {});
    }

    private void animateOutAll(ShortcutAndWidgetContainer swc,
            List<DockSlotView> views, boolean suppressAnimations, Runnable onDone) {
        if (views.isEmpty()) { onDone.run(); return; }
        if (suppressAnimations) {
            for (DockSlotView v : views) {
                swc.removeView(v);
            }
            onDone.run();
            return;
        }
        java.util.concurrent.atomic.AtomicInteger pending =
                new java.util.concurrent.atomic.AtomicInteger(views.size());
        for (DockSlotView v : views) {
            v.animateOut(() -> {
                swc.removeView(v);
                if (pending.decrementAndGet() == 0) onDone.run();
            });
        }
    }

    private static final long RAPID_UPDATE_WINDOW_MS = 450;

    private static long toCellKey(int cellX, int cellY) {
        return ((long) cellX << 32) | (cellY & 0xffffffffL);
    }

    @Nullable
    private static String getPackageName(DockSlot.Suggested slot) {
        return slot.getApp().componentName != null
                ? slot.getApp().componentName.getPackageName() : null;
    }

    public List<String> getPinnedPackagesByRank() {
        int slotCount = mActivity.getDeviceProfile().numShownHotseatIcons;
        java.util.ArrayList<String> result =
                new java.util.ArrayList<>(java.util.Collections.<String>nCopies(slotCount, null));
        ShortcutAndWidgetContainer swc = getShortcutsAndWidgets();
        for (int i = 0; i < swc.getChildCount(); i++) {
            View child = swc.getChildAt(i);
            if (child == null || child instanceof DockSlotView) {
                continue;
            }
            if (!(child.getLayoutParams() instanceof CellLayoutLayoutParams)) {
                continue;
            }
            CellLayoutLayoutParams lp = (CellLayoutLayoutParams) child.getLayoutParams();
            int rank = slotIndexForCell(lp.getCellX(), lp.getCellY());
            if (rank < 0 || rank >= slotCount) {
                continue;
            }
            Object tag = child.getTag();
            if (tag instanceof ItemInfo) {
                android.content.ComponentName cn = ((ItemInfo) tag).getTargetComponent();
                result.set(rank, cn != null ? cn.getPackageName() : null);
            }
        }
        return result;
    }

    public java.util.Set<Integer> getOccupiedRanks() {
        java.util.Set<Integer> occupiedRanks = new java.util.HashSet<>();
        ShortcutAndWidgetContainer swc = getShortcutsAndWidgets();
        for (int i = 0; i < swc.getChildCount(); i++) {
            View child = swc.getChildAt(i);
            if (child == null || child instanceof DockSlotView
                    || !(child.getLayoutParams() instanceof CellLayoutLayoutParams)) {
                continue;
            }
            CellLayoutLayoutParams lp = (CellLayoutLayoutParams) child.getLayoutParams();
            occupiedRanks.add(slotIndexForCell(lp.getCellX(), lp.getCellY()));
        }
        return occupiedRanks;
    }

    public boolean isRankOccupied(int rank) {
        ShortcutAndWidgetContainer swc = getShortcutsAndWidgets();
        for (int i = 0; i < swc.getChildCount(); i++) {
            View child = swc.getChildAt(i);
            if (child == null || child instanceof DockSlotView
                    || !(child.getLayoutParams() instanceof CellLayoutLayoutParams)) {
                continue;
            }
            CellLayoutLayoutParams lp = (CellLayoutLayoutParams) child.getLayoutParams();
            if (slotIndexForCell(lp.getCellX(), lp.getCellY()) == rank) {
                return true;
            }
        }
        return false;
    }

    private int slotIndexForCell(int cellX, int cellY) {
        return mHasVerticalHotseat ? (getCountY() - (cellY + 1)) : cellX;
    }

    private java.util.List<PendingDockSuggestion> buildPendingDockSuggestions(
            List<? extends DockSlot> slots, ShortcutAndWidgetContainer swc) {
        java.util.Set<Long> occupiedCells = new java.util.HashSet<>();
        java.util.Set<String> usedPackages = new java.util.HashSet<>();
        collectPinnedContent(swc, occupiedCells, usedPackages);

        int slotCount = mActivity.getDeviceProfile().numShownHotseatIcons;
        java.util.List<PendingDockSuggestion> pending = new java.util.ArrayList<>();
        for (int rank = 0; rank < Math.min(slotCount, slots.size()); rank++) {
            DockSlot slot = slots.get(rank);
            if (!(slot instanceof DockSlot.Suggested)) {
                continue;
            }

            DockSlot.Suggested suggested = (DockSlot.Suggested) slot;
            String packageName = getPackageName(suggested);
            CellLayoutLayoutParams layoutParams =
                    new CellLayoutLayoutParams(getCellXFromOrder(rank), getCellYFromOrder(rank), 1, 1);
            long cellKey = toCellKey(layoutParams.getCellX(), layoutParams.getCellY());
            if (occupiedCells.contains(cellKey)
                    || (packageName != null && usedPackages.contains(packageName))) {
                continue;
            }

            pending.add(new PendingDockSuggestion(suggested, layoutParams, rank, packageName));
            occupiedCells.add(cellKey);
            if (packageName != null) {
                usedPackages.add(packageName);
            }
        }
        return pending;
    }

    private void collectPinnedContent(
            ShortcutAndWidgetContainer swc,
            java.util.Set<Long> occupiedCells,
            java.util.Set<String> usedPackages) {
        for (int i = 0; i < swc.getChildCount(); i++) {
            View child = swc.getChildAt(i);
            if (child == null || child instanceof DockSlotView
                    || !(child.getLayoutParams() instanceof CellLayoutLayoutParams)) {
                continue;
            }

            CellLayoutLayoutParams layoutParams = (CellLayoutLayoutParams) child.getLayoutParams();
            occupiedCells.add(toCellKey(layoutParams.getCellX(), layoutParams.getCellY()));

            Object tag = child.getTag();
            if (tag instanceof com.android.launcher3.model.data.WorkspaceItemInfo) {
                android.content.ComponentName componentName =
                        ((com.android.launcher3.model.data.WorkspaceItemInfo) tag)
                                .getTargetComponent();
                if (componentName != null) {
                    usedPackages.add(componentName.getPackageName());
                }
            }
        }
    }

    private DockSlotView createSuggestionView(PendingDockSuggestion suggestion, Launcher launcher) {
        DockSlotView view = new DockSlotView(getContext());
        bindSuggestionActions(view, launcher);
        view.bind(suggestion.slot, suggestion.rank);
        return view;
    }

    private void rebindSuggestionView(
            DockSlotView view,
            PendingDockSuggestion suggestion,
            Launcher launcher,
            boolean suppressAnimations) {
        if (view.getParent() != getShortcutsAndWidgets()) {
            if (view.getParent() instanceof ViewGroup) {
                ((ViewGroup) view.getParent()).removeView(view);
            }
            getShortcutsAndWidgets().addView(view, suggestion.layoutParams);
        } else {
            CellLayoutLayoutParams currentLp = (CellLayoutLayoutParams) view.getLayoutParams();
            if (currentLp.getCellX() != suggestion.layoutParams.getCellX()
                    || currentLp.getCellY() != suggestion.layoutParams.getCellY()) {
                view.setLayoutParams(suggestion.layoutParams);
            }
        }

        bindSuggestionActions(view, launcher);
        view.setRank(suggestion.rank);
        if (suggestion.shouldAnimateChange(view, suppressAnimations)) {
            view.animateChange(suggestion.slot);
        } else {
            view.bind(suggestion.slot, suggestion.rank);
        }
    }

    private void bindSuggestionActions(DockSlotView view, Launcher launcher) {
        view.setSuggestionActionListener(new DockSlotView.SuggestionActionListener() {
            @Override
            public boolean onPinRequested(com.android.launcher3.model.data.AppInfo app, int rank) {
                return launcher.pinDockSuggestion(app, rank);
            }

            @Override
            public boolean onHideForNow(com.android.launcher3.model.data.AppInfo app) {
                return launcher.hideDockSuggestionForNow(app);
            }

            @Override
            public boolean onDontSuggest(com.android.launcher3.model.data.AppInfo app) {
                return launcher.blockDockSuggestion(app);
            }
        });
    }

    private static final class PendingDockSuggestion {
        final DockSlot.Suggested slot;
        final CellLayoutLayoutParams layoutParams;
        final int rank;
        @Nullable final String packageName;

        PendingDockSuggestion(DockSlot.Suggested slot, CellLayoutLayoutParams layoutParams,
                int rank, @Nullable String packageName) {
            this.slot = slot;
            this.layoutParams = layoutParams;
            this.rank = rank;
            this.packageName = packageName;
        }

        long getCellKey() {
            return toCellKey(layoutParams.getCellX(), layoutParams.getCellY());
        }

        boolean shouldAnimateChange(DockSlotView existing, boolean suppressAnimations) {
            return !suppressAnimations
                    && !java.util.Objects.equals(existing.getCurrentAppPkg(), packageName);
        }
    }

    private static final class ExistingDockViews {
        private final java.util.Map<Long, DockSlotView> mViewsByCell = new java.util.HashMap<>();
        private final java.util.Map<String, DockSlotView> mViewsByPackage = new java.util.HashMap<>();
        private final java.util.Map<Long, DockSlotView> mExactMatches = new java.util.HashMap<>();

        ExistingDockViews(List<DockSlotView> views) {
            for (DockSlotView view : views) {
                CellLayoutLayoutParams layoutParams =
                        (CellLayoutLayoutParams) view.getLayoutParams();
                mViewsByCell.put(toCellKey(layoutParams.getCellX(), layoutParams.getCellY()), view);
                String packageName = view.getCurrentAppPkg();
                if (packageName != null) {
                    mViewsByPackage.put(packageName, view);
                }
            }
        }

        void captureExactMatches(java.util.List<PendingDockSuggestion> pendingSuggestions) {
            for (PendingDockSuggestion suggestion : pendingSuggestions) {
                DockSlotView existing = mViewsByCell.get(suggestion.getCellKey());
                if (existing == null
                        || !java.util.Objects.equals(existing.getCurrentAppPkg(),
                                suggestion.packageName)) {
                    continue;
                }

                mExactMatches.put(suggestion.getCellKey(), existing);
                mViewsByCell.remove(suggestion.getCellKey());
                if (suggestion.packageName != null) {
                    mViewsByPackage.remove(suggestion.packageName);
                }
            }
        }

        @Nullable
        DockSlotView takeBestMatch(PendingDockSuggestion suggestion) {
            DockSlotView existing = mExactMatches.remove(suggestion.getCellKey());
            if (existing != null) {
                return existing;
            }

            if (suggestion.packageName != null) {
                existing = mViewsByPackage.remove(suggestion.packageName);
                if (existing != null) {
                    removeFromCellMap(existing);
                    return existing;
                }
            }

            existing = mViewsByCell.remove(suggestion.getCellKey());
            if (existing != null) {
                String oldPackageName = existing.getCurrentAppPkg();
                if (oldPackageName != null) {
                    mViewsByPackage.remove(oldPackageName);
                }
            }
            return existing;
        }

        java.util.List<DockSlotView> getUnmatchedViews() {
            return new java.util.ArrayList<>(mViewsByCell.values());
        }

        private void removeFromCellMap(DockSlotView view) {
            CellLayoutLayoutParams layoutParams =
                    (CellLayoutLayoutParams) view.getLayoutParams();
            mViewsByCell.remove(toCellKey(layoutParams.getCellX(), layoutParams.getCellY()));
        }
    }

    /** Dumps the Hotseat internal state */
    public void dump(String prefix, PrintWriter writer) {
        writer.println(prefix + "Hotseat:");
        mIconsAlphaChannels.dump(
                prefix + "\t",
                writer,
                "mIconsAlphaChannels",
                "ALPHA_CHANNEL_TASKBAR_ALIGNMENT",
                "ALPHA_CHANNEL_PREVIEW_RENDERER",
                "ALPHA_CHANNEL_TASKBAR_STASH");
        mQsbAlphaChannels.dump(
                prefix + "\t",
                writer,
                "mQsbAlphaChannels",
                "ALPHA_CHANNEL_TASKBAR_ALIGNMENT",
                "ALPHA_CHANNEL_PREVIEW_RENDERER",
                "ALPHA_CHANNEL_TASKBAR_STASH"
        );
    }

}
