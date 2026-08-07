/*
 * Copyright (C) 2019 The LineageOS Project
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
package com.android.launcher3.lineage.trust.db;

import android.graphics.drawable.Drawable;
import androidx.annotation.NonNull;

public class TrustComponent {
    @NonNull
    private final String mPackageName;
    @NonNull
    private final Drawable mIcon;
    @NonNull
    private final String mLabel;

    private boolean mIsHidden;
    private final boolean mIsAppLockSupported;
    private final boolean mIsAppLockEnabled;

    public TrustComponent(@NonNull String packageName, @NonNull Drawable icon,
                          @NonNull String label, boolean isHidden, boolean isAppLockSupported,
                          boolean isAppLockEnabled) {
        mPackageName = packageName;
        mIcon = icon;
        mLabel = label;
        mIsHidden = isHidden;
        mIsAppLockSupported = isAppLockSupported;
        mIsAppLockEnabled = isAppLockEnabled;
    }

    @NonNull
    public String getPackageName() {
        return mPackageName;
    }

    @NonNull
    public Drawable getIcon() {
        return mIcon;
    }

    @NonNull
    public String getLabel() {
        return mLabel;
    }

    public boolean isHidden() {
        return mIsHidden;
    }

    public boolean isAppLockEnabled() {
        return mIsAppLockEnabled;
    }

    public boolean isAppLockSupported() {
        return mIsAppLockSupported;
    }

    public void invertVisibility() {
        mIsHidden = !mIsHidden;
    }

    @Override
    public boolean equals(Object other) {
        if (!(other instanceof TrustComponent)) {
            return false;
        }

        TrustComponent otherComponent = (TrustComponent) other;
        return otherComponent.getPackageName().equals(mPackageName)
                && otherComponent.isHidden() == mIsHidden
                && otherComponent.isAppLockEnabled() == mIsAppLockEnabled
                && otherComponent.isAppLockSupported() == mIsAppLockSupported;
    }

    @Override
    public int hashCode() {
        return mPackageName.hashCode() + (mIsHidden ? 1 : 0) + (mIsAppLockEnabled ? 2 : 0)
                + (mIsAppLockSupported ? 4 : 0);
    }
}
