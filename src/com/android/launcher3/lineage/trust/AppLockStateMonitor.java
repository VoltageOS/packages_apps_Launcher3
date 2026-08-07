/*
 * Copyright (C) 2026 The Android Open Source Project
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
package com.android.launcher3.lineage.trust;

import android.content.Context;
import android.os.UserHandle;

import com.android.internal.content.PackageMonitor;
import com.android.launcher3.LauncherAppState;

/** Invalidates Launcher package state after a system AppLock change. */
public final class AppLockStateMonitor extends PackageMonitor {
    private final Context mContext;

    public AppLockStateMonitor(Context context) {
        mContext = context.getApplicationContext();
    }

    @Override
    public void onPackageAppLockEnabled(String packageName) {
        reloadPackage(packageName);
    }

    @Override
    public void onPackageAppLockDisabled(String packageName) {
        reloadPackage(packageName);
    }

    private void reloadPackage(String packageName) {
        LauncherAppState.INSTANCE.get(mContext).getModel().newModelCallbacks()
                .onPackageChanged(packageName, UserHandle.of(UserHandle.myUserId()));
    }
}
