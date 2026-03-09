package com.android.launcher3.settings;

import android.content.ComponentName;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.AsyncTask;
import android.os.Bundle;
import android.view.MenuItem;

import androidx.annotation.Nullable;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.android.launcher3.LauncherFiles;
import com.android.launcher3.R;
import com.android.settingslib.collapsingtoolbar.CollapsingToolbarBaseActivity;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class PinnedAppsActivity extends CollapsingToolbarBaseActivity {

    public static final String PREF_PINNED_APPS_LIST = "pref_pinned_apps_list";

    private RecyclerView mRecyclerView;
    private PinnedAppsAdapter mAdapter;
    private Set<String> mPinnedAppsStr = new HashSet<>();
    private SharedPreferences mPrefs;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.pinned_apps_activity);

        mPrefs = getSharedPreferences(LauncherFiles.SHARED_PREFERENCES_KEY, MODE_PRIVATE);
        mPinnedAppsStr = new HashSet<>(mPrefs.getStringSet(PREF_PINNED_APPS_LIST, new HashSet<>()));

        mRecyclerView = findViewById(R.id.pinned_apps_list);
        mRecyclerView.setLayoutManager(new LinearLayoutManager(this));

        new LoadAppsTask().execute();
    }

    private void savePinnedApps() {
        mPrefs.edit().putStringSet(PREF_PINNED_APPS_LIST, new HashSet<>(mPinnedAppsStr)).apply();
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            finish();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private class LoadAppsTask extends AsyncTask<Void, Void, List<PinnedAppsAdapter.AppEntry>> {
        @Override
        protected List<PinnedAppsAdapter.AppEntry> doInBackground(Void... voids) {
            PackageManager pm = getPackageManager();
            Intent intent = new Intent(Intent.ACTION_MAIN, null);
            intent.addCategory(Intent.CATEGORY_LAUNCHER);

            List<ResolveInfo> resolveInfos = pm.queryIntentActivities(intent, 0);
            List<PinnedAppsAdapter.AppEntry> entries = new ArrayList<>();

            for (ResolveInfo info : resolveInfos) {
                PinnedAppsAdapter.AppEntry entry = new PinnedAppsAdapter.AppEntry();
                ComponentName cmp = new ComponentName(info.activityInfo.packageName, info.activityInfo.name);
                entry.componentName = cmp.flattenToString();
                entry.label = info.loadLabel(pm).toString();
                entry.icon = info.loadIcon(pm);
                entries.add(entry);
            }

            Collections.sort(entries, (a, b) -> a.label.compareToIgnoreCase(b.label));
            return entries;
        }

        @Override
        protected void onPostExecute(List<PinnedAppsAdapter.AppEntry> appEntries) {
            if (isFinishing() || isDestroyed()) {
                return;
            }
            mAdapter = new PinnedAppsAdapter(PinnedAppsActivity.this, appEntries, mPinnedAppsStr, PinnedAppsActivity.this::savePinnedApps);
            mRecyclerView.setAdapter(mAdapter);
        }
    }
}
