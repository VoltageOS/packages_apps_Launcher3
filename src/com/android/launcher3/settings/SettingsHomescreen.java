/*
 * Copyright (C) 2015 The Android Open Source Project
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

import static androidx.preference.PreferenceFragmentCompat.ARG_PREFERENCE_ROOT;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.MenuItem;
import android.view.View;

import com.android.launcher3.dock.DockSuggestionMode;
import com.android.launcher3.dock.DockSuggestionsHelper;

import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;
import androidx.fragment.app.DialogFragment;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;
import androidx.preference.ListPreference;
import androidx.preference.Preference;
import androidx.preference.PreferenceFragmentCompat;
import androidx.preference.PreferenceFragmentCompat.OnPreferenceStartFragmentCallback;
import androidx.preference.PreferenceFragmentCompat.OnPreferenceStartScreenCallback;
import androidx.preference.PreferenceGroup;
import androidx.preference.PreferenceGroup.PreferencePositionCallback;
import androidx.preference.PreferenceScreen;
import androidx.preference.SwitchPreferenceCompat;
import androidx.recyclerview.widget.RecyclerView;

import com.android.launcher3.BuildConfig;
import com.android.launcher3.Flags;
import com.android.launcher3.LauncherAppState;
import com.android.launcher3.LauncherFiles;
import com.android.launcher3.LauncherPrefs;
import com.android.launcher3.SessionCommitReceiver;
import com.android.launcher3.allapps.AppDrawerStyle;
import com.android.launcher3.R;
import com.android.launcher3.Utilities;
import com.android.launcher3.util.SettingsCache;

import com.android.settingslib.collapsingtoolbar.CollapsingToolbarBaseActivity;
import com.android.settingslib.widget.SettingsBasePreferenceFragment;

import java.util.ArrayList;

/**
 * Settings activity for Launcher.
 */
public class SettingsHomescreen extends CollapsingToolbarBaseActivity
        implements OnPreferenceStartFragmentCallback, OnPreferenceStartScreenCallback,
        SharedPreferences.OnSharedPreferenceChangeListener {

    public static final String EXTRA_FRAGMENT_ARGS = ":settings:fragment_args";

    // Intent extra to indicate the pref-key to highlighted when opening the settings activity
    public static final String EXTRA_FRAGMENT_HIGHLIGHT_KEY = ":settings:fragment_args_key";
    // Intent extra to indicate the pref-key of the root screen when opening the settings activity
    public static final String EXTRA_FRAGMENT_ROOT_KEY = ARG_PREFERENCE_ROOT;

    private static final int DELAY_HIGHLIGHT_DURATION_MILLIS = 600;
    public static final String SAVE_HIGHLIGHTED_KEY = "android:preference_highlighted";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.settings_activity);

        Intent intent = getIntent();

        if (savedInstanceState == null) {
            Bundle args = intent.getBundleExtra(EXTRA_FRAGMENT_ARGS);
            if (args == null) {
                args = new Bundle();
            }

            String highlight = intent.getStringExtra(EXTRA_FRAGMENT_HIGHLIGHT_KEY);
            if (!TextUtils.isEmpty(highlight)) {
                args.putString(EXTRA_FRAGMENT_HIGHLIGHT_KEY, highlight);
            }
            String root = intent.getStringExtra(EXTRA_FRAGMENT_ROOT_KEY);
            if (!TextUtils.isEmpty(root)) {
                args.putString(EXTRA_FRAGMENT_ROOT_KEY, root);
            }

            final FragmentManager fm = getSupportFragmentManager();
            final Fragment f = fm.getFragmentFactory().instantiate(getClassLoader(),
                    getString(R.string.home_screen_settings_fragment_name));
            f.setArguments(args);
            // Display the fragment as the main content.
            fm.beginTransaction().replace(com.android.settingslib.collapsingtoolbar.R.id.content_frame, f).commit();
        }
        LauncherPrefs.getPrefs(this).registerOnSharedPreferenceChangeListener(this);
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        LauncherPrefs.getPrefs(this).unregisterOnSharedPreferenceChangeListener(this);
    }

    @Override
    public void onSharedPreferenceChanged(SharedPreferences sharedPreferences, String key) {
        if (LauncherPrefs.SHOW_HOTSEAT_BG.getSharedPrefKey().equals(key) ||
                LauncherPrefs.HOTSEAT_OPACITY.getSharedPrefKey().equals(key) ||
                LauncherPrefs.DOCK_SEARCH.getSharedPrefKey().equals(key) ||
                LauncherPrefs.DOCK_SEARCH_MODE.getSharedPrefKey().equals(key) ||
                LauncherPrefs.DOCK_THEME.getSharedPrefKey().equals(key) ||
                LauncherPrefs.SEARCH_RADIUS_SIZE.getSharedPrefKey().equals(key) ||
                LauncherPrefs.DOCK_MUSIC_SEARCH.getSharedPrefKey().equals(key) ||
                LauncherPrefs.HOTSEAT_QSB_OPACITY.getSharedPrefKey().equals(key) ||
                LauncherPrefs.HOTSEAT_QSB_STROKE_WIDTH.getSharedPrefKey().equals(key) ||
                LauncherPrefs.SHOW_STATUS_BAR.getSharedPrefKey().equals(key) ||
                LauncherPrefs.SHORT_PARALLAX.getSharedPrefKey().equals(key) ||
                LauncherPrefs.SINGLE_PAGE_CENTER.getSharedPrefKey().equals(key) ||
                LauncherPrefs.DARK_STATUS_BAR.getSharedPrefKey().equals(key) ||
                LauncherPrefs.SHOW_QUICKSPACE.getSharedPrefKey().equals(key) ||
                LauncherPrefs.QUICKSPACE_UI_STYLE.getSharedPrefKey().equals(key) ||
                LauncherPrefs.SHOW_QUICKSPACE_PSONALITY.getSharedPrefKey().equals(key) ||
                LauncherPrefs.SHOW_QUICKSPACE_NOWPLAYING.getSharedPrefKey().equals(key) ||
                LauncherPrefs.SHOW_QUICKSPACE_WEATHER.getSharedPrefKey().equals(key) ||
                LauncherPrefs.SHOW_QUICKSPACE_WEATHER_CITY.getSharedPrefKey().equals(key) ||
                LauncherPrefs.SHOW_QUICKSPACE_WEATHER_TEXT.getSharedPrefKey().equals(key)) {
            LauncherAppState.INSTANCE.executeIfCreated(app -> app.setNeedsRestart());
        }
    }

    private boolean startPreference(String fragment, Bundle args, String key) {
        if (getSupportFragmentManager().isStateSaved()) {
            // Sometimes onClick can come after onPause because of being posted on the handler.
            // Skip starting new preferences in that case.
            return false;
        }
        final FragmentManager fm = getSupportFragmentManager();
        final Fragment f = fm.getFragmentFactory().instantiate(getClassLoader(), fragment);
        if (f instanceof DialogFragment) {
            f.setArguments(args);
            ((DialogFragment) f).show(fm, key);
        } else {
            startActivity(new Intent(this, SettingsHomescreen.class)
                    .putExtra(EXTRA_FRAGMENT_ARGS, args));
        }
        return true;
    }

    @Override
    public boolean onPreferenceStartFragment(
            PreferenceFragmentCompat preferenceFragment, Preference pref) {
        return startPreference(pref.getFragment(), pref.getExtras(), pref.getKey());
    }

    @Override
    public boolean onPreferenceStartScreen(PreferenceFragmentCompat caller, PreferenceScreen pref) {
        Bundle args = new Bundle();
        args.putString(ARG_PREFERENCE_ROOT, pref.getKey());
        return startPreference(getString(R.string.home_category_title), args, pref.getKey());
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            onBackPressed();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    /**
     * This fragment shows the launcher preferences.
     */
    public static class HomescreenSettingsFragment extends SettingsBasePreferenceFragment  implements
            SettingsCache.OnChangeListener, SharedPreferences.OnSharedPreferenceChangeListener {

        private boolean mRestartOnResume = false;

        private String mHighLightKey;

        private boolean mPreferenceHighlighted = false;

        private static final String KEY_QUICKSPACE_STYLE = "pref_quickspace_style";
        private static final String KEY_VOLTAGE_ACCENT = "pref_quickspace_voltage_accent";
        private static final String KEY_QUICKSPACE_BATTERY = "pref_quickspace_battery";

        private static final String KEY_CLEAR_HOME_SCREEN = "pref_clear_home_screen";

        private ListPreference mQuickspaceStyle;
        private Preference mVoltageAccent;
        private Preference mQuickspaceBattery;
        private SwitchPreferenceCompat mAutoAddIconsPref;
        private ListPreference mDockSuggestionModePref;
        private ListPreference mDockSearchModePref;

        private static final String KEY_MINUS_ONE = "pref_enable_minus_one";
        private static final String KEY_DOCK_SUGGESTION_MODE = "pref_dock_suggestion_mode";

        private Preference mShowGoogleAppPref;
        private Preference mShowGoogleBarPref;
        private Preference mDockMusicSearchPref;
        private Preference mDockThemePref;
        private Preference mHotseatQsbOpacityPref;
        private Preference mHotseatQsbStrokePref;
        private Preference mSearchRadiusPref;

        @Override
        public void onCreate(@Nullable Bundle savedInstanceState) {
            super.onCreate(savedInstanceState);
        }

        @Override
        public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
            final Bundle args = getArguments();
            mHighLightKey = args == null ? null : args.getString(EXTRA_FRAGMENT_HIGHLIGHT_KEY);

            if (savedInstanceState != null) {
                mPreferenceHighlighted = savedInstanceState.getBoolean(SAVE_HIGHLIGHTED_KEY);
            }

            getPreferenceManager().setSharedPreferencesName(LauncherFiles.SHARED_PREFERENCES_KEY);
            setPreferencesFromResource(R.xml.launcher_home_screen_preferences, rootKey);

            PreferenceScreen screen = getPreferenceScreen();

            mShowGoogleAppPref = screen.findPreference(KEY_MINUS_ONE);
            mShowGoogleBarPref = screen.findPreference(LauncherPrefs.DOCK_SEARCH.getSharedPrefKey());
            mDockSearchModePref = screen.findPreference(LauncherPrefs.DOCK_SEARCH_MODE.getSharedPrefKey());
            mAutoAddIconsPref = screen.findPreference(SessionCommitReceiver.ADD_ICON_PREFERENCE_KEY);
            mDockMusicSearchPref = screen.findPreference(LauncherPrefs.DOCK_MUSIC_SEARCH.getSharedPrefKey());
            mDockThemePref = screen.findPreference(LauncherPrefs.DOCK_THEME.getSharedPrefKey());
            mHotseatQsbOpacityPref = screen.findPreference(LauncherPrefs.HOTSEAT_QSB_OPACITY.getSharedPrefKey());
            mHotseatQsbStrokePref = screen.findPreference(LauncherPrefs.HOTSEAT_QSB_STROKE_WIDTH.getSharedPrefKey());
            mSearchRadiusPref = screen.findPreference(LauncherPrefs.SEARCH_RADIUS_SIZE.getSharedPrefKey());

            mQuickspaceStyle = screen.findPreference(KEY_QUICKSPACE_STYLE);
            mVoltageAccent = screen.findPreference(KEY_VOLTAGE_ACCENT);
            mQuickspaceBattery = screen.findPreference(KEY_QUICKSPACE_BATTERY);
            mDockSuggestionModePref = screen.findPreference(KEY_DOCK_SUGGESTION_MODE);
            DockSuggestionMode dockSuggestionMode =
                    DockSuggestionsHelper.getSuggestionMode(requireContext());
            if (mDockSuggestionModePref != null) {
                mDockSuggestionModePref.setValue(dockSuggestionMode.getPrefValue());
            }

            Preference clearHomeScreenPref = screen.findPreference(KEY_CLEAR_HOME_SCREEN);
            if (clearHomeScreenPref != null) {
                clearHomeScreenPref.setOnPreferenceClickListener(pref -> {
                    new AlertDialog.Builder(getContext())
                            .setMessage(R.string.remove_all_views_from_home_screen_desc)
                            .setPositiveButton(android.R.string.ok, (dialog, which) -> {
                                LauncherAppState.getInstance(getContext())
                                        .clearAllViewsFromHomeScreen();
                            })
                            .setNegativeButton(android.R.string.cancel, null)
                            .show();
                    return true;
                });
            }

            updateVoltageAccentVisibility();

            updateSearchBarPreferences();
            updateAutoAddIconsPreferenceState();

            // If the target preference is not in the current preference screen, find the parent
            // preference screen that contains the target preference and set it as the preference
            // screen.
                if (mHighLightKey != null
                    && !isKeyInPreferenceGroup(mHighLightKey, screen)) {
                final PreferenceScreen parentPreferenceScreen =
                        findParentPreference(screen, mHighLightKey);
                if (parentPreferenceScreen != null && getActivity() != null) {
                    if (!TextUtils.isEmpty(parentPreferenceScreen.getTitle())) {
                        getActivity().setTitle(parentPreferenceScreen.getTitle());
                    }
                    setPreferenceScreen(parentPreferenceScreen);
                    return;
                }
            }

            if (getActivity() != null && !TextUtils.isEmpty(getPreferenceScreen().getTitle())) {
                getActivity().setTitle(getPreferenceScreen().getTitle());
            }
        }

        private boolean isKeyInPreferenceGroup(String targetKey, PreferenceGroup parent) {
            for (int i = 0; i < parent.getPreferenceCount(); i++) {
                Preference pref = parent.getPreference(i);
                if (pref.getKey() != null && pref.getKey().equals(targetKey)) {
                    return true;
                }
            }
            return false;
        }

        /**
         * Finds the parent preference screen for the given target key.
         *
         * @param parent the parent preference screen
         * @param targetKey the key of the preference to find
         * @return the parent preference screen that contains the target preference
         */
        @Nullable
        private PreferenceScreen findParentPreference(PreferenceScreen parent, String targetKey) {
            for (int i = 0; i < parent.getPreferenceCount(); i++) {
                Preference pref = parent.getPreference(i);
                if (pref instanceof PreferenceScreen) {
                    PreferenceScreen foundKey = findParentPreference((PreferenceScreen) pref,
                            targetKey);
                    if (foundKey != null) {
                        return foundKey;
                    }
                } else if (pref.getKey() != null && pref.getKey().equals(targetKey)) {
                    return parent;
                }
            }
            return null;
        }

        @Override
        public void onViewCreated(View view, Bundle savedInstanceState) {
            super.onViewCreated(view, savedInstanceState);
            View listView = getListView();
            final int bottomPadding = listView.getPaddingBottom();
            listView.setOnApplyWindowInsetsListener((v, insets) -> {
                v.setPadding(
                        v.getPaddingLeft(),
                        v.getPaddingTop(),
                        v.getPaddingRight(),
                        bottomPadding + insets.getSystemWindowInsetBottom());
                return insets.consumeSystemWindowInsets();
            });

            // Overriding Text Direction in the Androidx preference library to support RTL
            view.setTextDirection(View.TEXT_DIRECTION_LOCALE);
        }

        @Override
        public void onSaveInstanceState(Bundle outState) {
            super.onSaveInstanceState(outState);
            outState.putBoolean(SAVE_HIGHLIGHTED_KEY, mPreferenceHighlighted);
        }

        private void updateSearchBarPreferences() {
            if (getContext() == null) {
                return;
            }

            boolean hasGoogle = Utilities.hasGoogleQsb(getContext());
            boolean hasWidget = Utilities.hasWidgetQsb(getContext());
            boolean hasAny = hasGoogle || hasWidget;
            boolean searchEnabled = LauncherPrefs.DOCK_SEARCH.get(getContext());

            if (mShowGoogleAppPref != null) {
                mShowGoogleAppPref.setEnabled(hasGoogle);
            }

            if (mShowGoogleBarPref != null) {
                mShowGoogleBarPref.setEnabled(hasAny);
                mShowGoogleBarPref.setSummary(hasAny
                        ? R.string.dock_search_summary
                        : R.string.dock_search_unavailable);
            }

            if (mDockSearchModePref != null) {
                if (!hasAny) {
                    mDockSearchModePref.setEnabled(false);
                    mDockSearchModePref.setSummary(R.string.dock_search_unavailable);
                } else {
                    ArrayList<CharSequence> entries = new ArrayList<>();
                    ArrayList<CharSequence> entryValues = new ArrayList<>();
                    if (hasGoogle) {
                        entries.add(getString(R.string.dock_search_mode_google));
                        entryValues.add(Utilities.DOCK_SEARCH_MODE_GOOGLE);
                    }
                    if (hasWidget) {
                        entries.add(getString(R.string.dock_search_mode_widget));
                        entryValues.add(Utilities.DOCK_SEARCH_MODE_WIDGET);
                    }
                    mDockSearchModePref.setEntries(entries.toArray(new CharSequence[0]));
                    mDockSearchModePref.setEntryValues(entryValues.toArray(new CharSequence[0]));

                    String preferredMode = LauncherPrefs.DOCK_SEARCH_MODE.get(getContext());
                    int preferredIndex = mDockSearchModePref.findIndexOfValue(preferredMode);
                    if (preferredIndex < 0 && !entryValues.isEmpty()) {
                        preferredMode = entryValues.get(0).toString();
                        LauncherPrefs.getPrefs(getContext()).edit()
                                .putString(LauncherPrefs.DOCK_SEARCH_MODE.getSharedPrefKey(),
                                        preferredMode)
                                .apply();
                        preferredIndex = mDockSearchModePref.findIndexOfValue(preferredMode);
                    }
                    mDockSearchModePref.setValue(preferredMode);
                    mDockSearchModePref.setEnabled(searchEnabled && entryValues.size() > 1);
                    if (preferredIndex >= 0 && preferredIndex < entries.size()) {
                        mDockSearchModePref.setSummary(entries.get(preferredIndex));
                    }
                }
            }

            boolean googleQsbActive = searchEnabled && hasGoogle
                    && Utilities.DOCK_SEARCH_MODE_GOOGLE.equals(
                    Utilities.getEffectiveDockSearchMode(getContext()));
            updateGoogleQsbOptionsState(googleQsbActive);
        }

        private void updateGoogleQsbOptionsState(boolean enabled) {
            if (mDockMusicSearchPref != null) {
                mDockMusicSearchPref.setEnabled(enabled);
            }
            if (mDockThemePref != null) {
                mDockThemePref.setEnabled(enabled);
            }
            if (mHotseatQsbOpacityPref != null) {
                mHotseatQsbOpacityPref.setEnabled(enabled);
            }
            if (mHotseatQsbStrokePref != null) {
                mHotseatQsbStrokePref.setEnabled(enabled);
            }
            if (mSearchRadiusPref != null) {
                mSearchRadiusPref.setEnabled(enabled);
            }
        }

        @Override
        public void onResume() {
            super.onResume();

            if (isAdded() && !mPreferenceHighlighted) {
                PreferenceHighlighter highlighter = createHighlighter();
                if (highlighter != null) {
                    getView().postDelayed(highlighter, DELAY_HIGHLIGHT_DURATION_MILLIS);
                    mPreferenceHighlighted = true;
                }
             }
            getPreferenceManager().getSharedPreferences()
                    .registerOnSharedPreferenceChangeListener(this);
            updateSearchBarPreferences();
            updateAutoAddIconsPreferenceState();

            if (mRestartOnResume) {
                recreateActivityNow();
            }
        }

        @Override
        public void onSettingsChanged(boolean isEnabled) {
            // Developer options changed, try recreate
            tryRecreateActivity();
        }

        @Override
        public void onPause() {
            super.onPause();
            getPreferenceManager().getSharedPreferences()
                    .unregisterOnSharedPreferenceChangeListener(this);
        }

        /**
         * Tries to recreate the preference
         */
        protected void tryRecreateActivity() {
            if (isResumed()) {
                recreateActivityNow();
            } else {
                mRestartOnResume = true;
            }
        }

        private void recreateActivityNow() {
            Activity activity = getActivity();
            if (activity != null) {
                activity.recreate();
            }
        }

        private PreferenceHighlighter createHighlighter() {
            if (TextUtils.isEmpty(mHighLightKey)) {
                return null;
            }

            PreferenceScreen screen = getPreferenceScreen();
            if (screen == null) {
                return null;
            }

            RecyclerView list = getListView();
            PreferencePositionCallback callback = (PreferencePositionCallback) list.getAdapter();
            int position = callback.getPreferenceAdapterPosition(mHighLightKey);
            return position >= 0 ? new PreferenceHighlighter(
                    list, position, screen.findPreference(mHighLightKey))
                    : null;
        }

        @Override
        public void onSharedPreferenceChanged(SharedPreferences sharedPreferences, String key) {
            if (KEY_QUICKSPACE_STYLE.equals(key)) {
                updateVoltageAccentVisibility();
            }
            if (LauncherPrefs.APP_DRAWER_STYLE.getSharedPrefKey().equals(key)) {
                updateAutoAddIconsPreferenceState();
            }
            if (LauncherPrefs.DOCK_SEARCH.getSharedPrefKey().equals(key)
                    || LauncherPrefs.DOCK_SEARCH_MODE.getSharedPrefKey().equals(key)) {
                updateSearchBarPreferences();
            }
            if (KEY_DOCK_SUGGESTION_MODE.equals(key)) {
                DockSuggestionMode mode =
                        DockSuggestionMode.fromPrefValue(sharedPreferences.getString(key, null));
                if (mode.isEnabled() && !DockSuggestionsHelper.hasUsageStatsPermission(requireContext())) {
                    showUsageAccessDialog();
                }
            }
        }


        private void showUsageAccessDialog() {
            new AlertDialog.Builder(requireContext())
                .setTitle(R.string.dock_recents_permission_title)
                .setMessage(R.string.dock_recents_permission_message)
                .setPositiveButton(R.string.dock_recents_permission_grant, (d, w) -> {
                    Intent intent = DockSuggestionsHelper.usageAccessSettingsIntent();
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    startActivity(intent);
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
        }

        private void updateAutoAddIconsPreferenceState() {
            if (mAutoAddIconsPref == null || getContext() == null) {
                return;
            }
            boolean iosStyle = AppDrawerStyle.isIos(AppDrawerStyle.get(getContext()));
            if (iosStyle) {
                mAutoAddIconsPref.setChecked(true);
                LauncherPrefs.getPrefs(getContext()).edit()
                        .putBoolean(SessionCommitReceiver.ADD_ICON_PREFERENCE_KEY, true)
                        .apply();
                mAutoAddIconsPref.setEnabled(false);
                mAutoAddIconsPref.setSummary(R.string.auto_add_shortcuts_forced_ios_summary);
            } else {
                mAutoAddIconsPref.setEnabled(true);
                mAutoAddIconsPref.setSummary(R.string.auto_add_shortcuts_description);
            }
        }

        private void updateVoltageAccentVisibility() {
            if (mVoltageAccent == null || mQuickspaceStyle == null) {
                return;
            }
            // The "Voltage" style has a value of "2" in your arrays.xml
            boolean isVoltageStyle = "2".equals(mQuickspaceStyle.getValue());

            if (mVoltageAccent != null) {
                mVoltageAccent.setVisible(isVoltageStyle);
            }

            if (mQuickspaceBattery != null) {
                mQuickspaceBattery.setVisible(isVoltageStyle);
            }
        }
    }
}
