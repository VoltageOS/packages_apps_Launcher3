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

package com.android.launcher3.qsb;

import static android.appwidget.AppWidgetManager.ACTION_APPWIDGET_BIND;
import static android.appwidget.AppWidgetManager.EXTRA_APPWIDGET_ID;
import static android.appwidget.AppWidgetManager.EXTRA_APPWIDGET_PROVIDER;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.os.Bundle;

public class QsbSetupActivity extends Activity {
    private static final int REQUEST_BIND_QSB = 1;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        Intent intent = getIntent();
        if (intent == null) {
            finish();
            return;
        }

        Intent bindIntent = new Intent(ACTION_APPWIDGET_BIND)
                .putExtra(EXTRA_APPWIDGET_ID, intent.getIntExtra(EXTRA_APPWIDGET_ID, -1))
                .putExtra(EXTRA_APPWIDGET_PROVIDER,
                        intent.getParcelableExtra(EXTRA_APPWIDGET_PROVIDER, ComponentName.class));
        startActivityForResult(bindIntent, REQUEST_BIND_QSB);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQUEST_BIND_QSB) {
            return;
        }

        int widgetId = -1;
        if (resultCode == RESULT_OK && data != null) {
            widgetId = data.getIntExtra(EXTRA_APPWIDGET_ID, -1);
        }
        QsbContainerView.saveHotseatWidgetId(this, widgetId);
        finish();
    }
}
