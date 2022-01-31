/*
 * Copyright (C) 2022 Project Kaleidoscope
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

package com.android.quickstep.views;

import android.content.Context;
import android.util.AttributeSet;
import android.util.FloatProperty;
import android.view.Gravity;
import android.widget.Button;
import android.widget.FrameLayout.LayoutParams;

import com.android.launcher3.anim.AlphaUpdateListener;
import com.android.launcher3.DeviceProfile;
import com.android.launcher3.util.MultiValueAlpha;
import com.android.quickstep.SysUINavigationMode.Mode;

public class MidClearAllButton extends Button {

    public static final int HIDDEN_DISABLED = 1 << 0;
    public static final int HIDDEN_NO_TASKS = 1 << 1;

    private static final int ALPHA_VISIBILITY = 0;
    private static final int ALPHA_STATE_CTRL = 1;
    public static final int ALPHA_FS_PROGRESS = 2;

    public static final FloatProperty<MidClearAllButton> STATE_CTRL_ALPHA =
            new FloatProperty<MidClearAllButton>("state control alpha") {
                @Override
                public Float get(MidClearAllButton view) {
                    return view.getAlpha(ALPHA_STATE_CTRL);
                }

                @Override
                public void setValue(MidClearAllButton view, float v) {
                    view.setAlpha(ALPHA_STATE_CTRL, v);
                }
            };

    private DeviceProfile mDp;
    private int mHideReason;
    private MultiValueAlpha mAlpha;

    public MidClearAllButton(Context context, AttributeSet attrs) {
        super(context, attrs);
        mAlpha = new MultiValueAlpha(this, 3);
        mAlpha.setUpdateVisibility(true);
    }

    public void setDp(DeviceProfile dp) {
        mDp = dp;
    }

    public void setAlpha(int alphaType, float alpha) {
        mAlpha.getProperty(alphaType).setValue(alpha);
    }

    public float getAlpha(int alphaType) {
        return mAlpha.getProperty(alphaType).getValue();
    }

    public void hide(int hideReason, boolean hide) {
        if (hide)
            mHideReason |= hideReason;
        else
            mHideReason &= ~hideReason;

        setAlpha(ALPHA_VISIBILITY, mHideReason == 0 ? 1 : 0);
    }

    public void updateVerticalMargin(Mode mode) {
        LayoutParams lp = (LayoutParams)getLayoutParams();
        int bottomMargin;

        if (mode == Mode.THREE_BUTTONS)
            bottomMargin = mDp.midClearAllMarginThreeButtonPx;
        else
            bottomMargin = mDp.midClearAllMarginGesturePx;

        lp.setMargins(lp.leftMargin, lp.topMargin, lp.rightMargin, bottomMargin);
        lp.gravity = Gravity.CENTER_HORIZONTAL | Gravity.BOTTOM;
    }
}
