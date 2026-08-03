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

package com.android.launcher3.settings;

import android.app.AlertDialog;
import android.appwidget.AppWidgetProviderInfo;
import android.content.ComponentName;
import android.content.Context;
import android.util.AttributeSet;

import androidx.preference.Preference;

import com.android.launcher3.R;
import com.android.launcher3.qsb.DockSearchWidgetHelper;

import java.util.List;

public class SearchProviderPreference extends Preference {

    public SearchProviderPreference(Context context, AttributeSet attrs) {
        super(context, attrs);
        updateSummary();
    }

    private void updateSummary() {
        setSummary(DockSearchWidgetHelper.getSelectedWidgetLabel(getContext()));
    }

    @Override
    protected void onClick() {
        super.onClick();
        showPicker();
    }

    private void showPicker() {
        Context context = getContext();
        List<AppWidgetProviderInfo> widgets =
                DockSearchWidgetHelper.getEligibleSearchWidgets(context);

        CharSequence[] items = new CharSequence[widgets.size() + 1];
        String[] values = new String[widgets.size() + 1];

        items[0] = context.getString(R.string.dock_search_widget_default);
        values[0] = "";

        for (int i = 0; i < widgets.size(); i++) {
            items[i + 1] = DockSearchWidgetHelper.getWidgetLabel(context, widgets.get(i));
            values[i + 1] = widgets.get(i).provider.flattenToString();
        }

        String currentValue = DockSearchWidgetHelper.getSelectedProviderFlattened(context);
        int selectedIndex = 0;
        for (int i = 0; i < values.length; i++) {
            if (values[i].equals(currentValue)) {
                selectedIndex = i;
                break;
            }
        }

        new AlertDialog.Builder(context)
                .setTitle(R.string.dock_search_widget_picker_title)
                .setSingleChoiceItems(items, selectedIndex, (dialog, which) -> {
                    String selected = values[which];
                    if (selected.isEmpty()) {
                        DockSearchWidgetHelper.setSelectedProvider(context, null);
                    } else {
                        ComponentName provider = ComponentName.unflattenFromString(selected);
                        AppWidgetProviderInfo info =
                                DockSearchWidgetHelper.getProviderInfo(context, provider);
                        if (DockSearchWidgetHelper.supportsConfiguration(info)) {
                            DockSearchWidgetHelper.setPendingConfiguration(context, true);
                        }
                        DockSearchWidgetHelper.setSelectedProvider(context, provider);
                    }
                    updateSummary();
                    dialog.dismiss();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }
}
