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

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.content.Context
import android.util.AttributeSet
import android.widget.FrameLayout
import com.android.launcher3.BubbleTextView
import com.android.launcher3.model.data.AppInfo

class DockSlotView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0,
) : FrameLayout(context, attrs, defStyle) {

    interface SuggestionActionListener {
        fun onPinRequested(app: AppInfo, rank: Int): Boolean
        fun onHideForNow(app: AppInfo): Boolean
        fun onDontSuggest(app: AppInfo): Boolean
    }

    private var currentSlot: DockSlot = DockSlot.Empty
    val currentAppPkg: String?
        get() = (currentSlot as? DockSlot.Suggested)?.app?.componentName?.packageName

    private var currentRank = -1
    private var suggestionActionListener: SuggestionActionListener? = null
    private var runningAnimator: AnimatorSet? = null

    init {
        isClickable = true
        isFocusable = true
    }

    fun bind(slot: DockSlot, rank: Int = currentRank) {
        currentRank = rank
        applySlot(slot, resetVisualState = true, cancelRunningAnimation = true)
    }

    fun setRank(rank: Int) {
        currentRank = rank
    }

    fun animateOut(onEnd: Runnable) {
        clearRunningAnimator()
        AnimatorSet().apply {
            playTogether(ObjectAnimator.ofFloat(this@DockSlotView, ALPHA, alpha, 0f))
            duration = EXIT_DURATION
            interpolator = android.view.animation.DecelerateInterpolator()
        }.also { animator ->
            startAnimator(animator) { onEnd.run() }
        }
    }

    fun animateIn(delayMs: Long = 0L) {
        clearRunningAnimator()
        scaleX = 1f
        scaleY = 1f
        alpha = 0f
        AnimatorSet().apply {
            playTogether(ObjectAnimator.ofFloat(this@DockSlotView, ALPHA, 0f, targetAlpha(slot = currentSlot)))
            duration = ENTER_DURATION
            startDelay = delayMs
            interpolator = android.view.animation.DecelerateInterpolator()
        }.also { animator ->
            startAnimator(animator)
        }
    }

    fun animateChange(slot: DockSlot) {
        clearRunningAnimator()
        val targetAlpha = targetAlpha(slot)
        val animation = AnimatorSet()
        val playOut = AnimatorSet().apply {
            playTogether(ObjectAnimator.ofFloat(this@DockSlotView, ALPHA, alpha, CHANGE_FADE_ALPHA))
            duration = CHANGE_OUT_DURATION
            interpolator = android.view.animation.AccelerateInterpolator()
        }
        playOut.addListener(object : AnimatorListenerAdapter() {
            private var cancelled = false

            override fun onAnimationCancel(animation: Animator) {
                cancelled = true
            }

            override fun onAnimationEnd(animation: Animator) {
                if (!cancelled) {
                    applySlot(slot, resetVisualState = false, cancelRunningAnimation = false)
                    alpha = CHANGE_FADE_ALPHA
                }
            }
        })
        val playIn = AnimatorSet().apply {
            playTogether(ObjectAnimator.ofFloat(this@DockSlotView, ALPHA, CHANGE_FADE_ALPHA, targetAlpha))
            duration = CHANGE_IN_DURATION
            interpolator = android.view.animation.DecelerateInterpolator()
        }
        animation.playSequentially(playOut, playIn)
        startAnimator(animation)
    }

    fun setSuggestionActionListener(listener: SuggestionActionListener) {
        suggestionActionListener = listener
    }

    private fun bindApp(app: AppInfo) {
        val btv = getOrCreateBubble()
        btv.applyFromApplicationInfo(app)
        btv.tag = app
        btv.isClickable = false
        btv.isFocusable = false
        setOnClickListener { 
            com.android.launcher3.touch.ItemClickHandler.INSTANCE.onClick(btv) 
        }
    }

    private fun getOrCreateBubble(): BubbleTextView =
        getChildAt(0) as? BubbleTextView ?: BubbleTextView(context).also {
            addView(it, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        }

    private fun showSuggestionPopup(app: AppInfo): Boolean {
        val bubble = getChildAt(0) as? BubbleTextView ?: return false
        val actionHandler =
            if (currentRank >= 0 && suggestionActionListener != null) {
                object : DockSuggestionPopup.ActionHandler {
                    override fun onPin(): Boolean =
                        suggestionActionListener?.onPinRequested(app, currentRank) ?: false

                    override fun onHideForNow(): Boolean =
                        suggestionActionListener?.onHideForNow(app) ?: false

                    override fun onDontSuggest(): Boolean =
                        suggestionActionListener?.onDontSuggest(app) ?: false
                }
            } else {
                null
            }
        return DockSuggestionPopup.show(bubble, app, actionHandler)
    }

    companion object {
        private const val EXIT_DURATION = 120L
        private const val ENTER_DURATION = 150L
        private const val CHANGE_OUT_DURATION = 60L
        private const val CHANGE_IN_DURATION = 90L
        private const val CHANGE_FADE_ALPHA = 0.35f
        private const val SUGGESTED_ALPHA = 0.94f
    }

    private fun applySlot(
        slot: DockSlot,
        resetVisualState: Boolean,
        cancelRunningAnimation: Boolean,
    ) {
        if (cancelRunningAnimation) {
            clearRunningAnimator()
        }
        currentSlot = slot
        setOnClickListener(null)
        setOnLongClickListener(null)
        when (slot) {
            DockSlot.Pinned -> {
                resetVisualStateIfNeeded(resetVisualState, alpha = 0f)
                removeAllViews()
            }
            DockSlot.Blocked -> {
                resetVisualStateIfNeeded(resetVisualState, alpha = 0f)
                removeAllViews()
            }
            is DockSlot.Suggested -> {
                resetVisualStateIfNeeded(resetVisualState, alpha = SUGGESTED_ALPHA)
                bindApp(slot.app)
                setOnLongClickListener {
                    showSuggestionPopup(slot.app)
                }
            }
            DockSlot.Empty -> {
                resetVisualStateIfNeeded(resetVisualState, alpha = 0f)
                removeAllViews()
            }
        }
        invalidate()
    }

    private fun resetVisualStateIfNeeded(reset: Boolean, alpha: Float) {
        if (!reset) {
            return
        }
        this.alpha = alpha
        scaleX = 1f
        scaleY = 1f
    }

    private fun targetAlpha(slot: DockSlot): Float =
        if (slot is DockSlot.Suggested) SUGGESTED_ALPHA else 1f

    private fun clearRunningAnimator() {
        runningAnimator?.cancel()
        runningAnimator = null
    }

    private fun startAnimator(animator: AnimatorSet, onEnd: (() -> Unit)? = null) {
        runningAnimator = animator
        animator.addListener(object : AnimatorListenerAdapter() {
            private var cancelled = false

            override fun onAnimationCancel(animation: Animator) {
                cancelled = true
            }

            override fun onAnimationEnd(animation: Animator) {
                if (runningAnimator === animation) {
                    runningAnimator = null
                }
                if (!cancelled) {
                    onEnd?.invoke()
                }
            }
        })
        animator.start()
    }
}
