/*
 * Copyright (C) 2020-2025 crDroid Android Project
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
package com.android.launcher3.quickspace;

import android.app.PendingIntent;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Context;
import android.content.BroadcastReceiver;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Resources;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.drawable.Drawable;
import android.icu.text.DateFormat;
import android.icu.text.DisplayContext;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSession;
import android.media.session.MediaSessionManager;
import android.net.Uri;
import android.os.SystemClock;
import android.provider.AlarmClock;
import android.provider.Settings;
import android.text.TextUtils;
import android.widget.Toast;
import android.view.View;
import android.view.View.OnClickListener;

import androidx.core.content.ContextCompat;

import com.android.launcher3.Launcher;
import com.android.launcher3.LauncherFiles;
import com.android.launcher3.LauncherPrefs;
import com.android.launcher3.R;

import java.util.Calendar;
import java.util.concurrent.ThreadLocalRandom;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.android.launcher3.util.MSMHProxy;

public class QuickEventsController {

    private final Context mContext;
    private final Resources mResources;

    private String mEventTitle;
    private String mEventTitleSub;
    private String mGreetings;
    private String mClockExt;
    private OnClickListener mEventTitleSubAction = null;
    private Drawable mEventSubIcon = null;

    private boolean mIsQuickEvent = false;

    private final Map<Integer, String[]> mCachedPSAMap = new HashMap<>();

    // PSA + Personality
    private String[] mPSAStr;
    
    // Cache for PSA messages to prevent random switching
    private String mCachedPSAMessage = null;
    private int mCachedPSAHour = -1;
    private boolean mCachedPSAIsRandom = false;

    public static final int CONTEXT_EVENT_NONE = 0;
    public static final int CONTEXT_EVENT_CHARGING = 1;
    public static final int CONTEXT_EVENT_BATTERY_FULL = 2;
    public static final int CONTEXT_EVENT_BATTERY_LOW = 3;
    public static final int CONTEXT_EVENT_BT_BATTERY = 4;

    public static final String SOURCE_PHONE = "device_phone";

    public static class ContextualEvent {
        public final int type;
        public final String message;
        public final long timestamp;
        public final long durationMs;
        public final Drawable icon;
        public final OnClickListener clickAction;
        public final String sourceKey;
        public final boolean isSticky;
        public final int argLevel;
        public final String argName;

        public ContextualEvent(int type, String message, long timestamp, long durationMs, Drawable icon, OnClickListener clickAction, String sourceKey) {
            this(type, message, timestamp, durationMs, icon, clickAction, sourceKey, false, -1, null);
        }

        public ContextualEvent(int type, String message, long timestamp, long durationMs, Drawable icon, OnClickListener clickAction, String sourceKey, boolean isSticky, int argLevel, String argName) {
            this.type = type;
            this.message = message;
            this.timestamp = timestamp;
            this.durationMs = durationMs;
            this.icon = icon;
            this.clickAction = clickAction;
            this.sourceKey = sourceKey;
            this.isSticky = isSticky;
            this.argLevel = argLevel;
            this.argName = argName;
        }

        public boolean isExpired() {
            if (isSticky) return false;
            return (SystemClock.elapsedRealtime() - timestamp) > durationMs;
        }
    }

    private ContextualEvent mActiveContextualEvent = null;

    // NowPlaying
    private boolean mEventNowPlaying = false;
    private String mNowPlayingTitle;
    private String mNowPlayingArtist;
    private boolean mPlayingActive = false;

    private DateFormat mDateFormat;
    private String mLastDateFormatSkeleton;

    private final OnClickListener mBatteryAction = new View.OnClickListener() {
        @Override
        public void onClick(View view) {
            try {
                Intent intent = new Intent(Intent.ACTION_POWER_USAGE_SUMMARY);
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                mContext.startActivity(intent);
            } catch (Exception e) {
                try {
                    Intent intent = new Intent(Settings.ACTION_SETTINGS);
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    mContext.startActivity(intent);
                } catch (Exception e2) {}
            }
        }
    };

    private final OnClickListener mBluetoothAction = new View.OnClickListener() {
        @Override
        public void onClick(View view) {
            try {
                Intent intent = new Intent(Settings.ACTION_BLUETOOTH_SETTINGS);
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                mContext.startActivity(intent);
            } catch (Exception e) {}
        }
    };

    private final OnClickListener mPSAAction = new View.OnClickListener() {
        @Override
        public void onClick(View view) {
            Intent calendarIntent = new Intent(Intent.ACTION_MAIN);
            calendarIntent.addCategory(Intent.CATEGORY_APP_CALENDAR);

            Intent clockIntent = new Intent(AlarmClock.ACTION_SHOW_ALARMS);

            PackageManager packageManager = mContext.getPackageManager();
            List<ResolveInfo> calendarApps = packageManager.queryIntentActivities(calendarIntent, PackageManager.MATCH_DEFAULT_ONLY);
            List<ResolveInfo> clockApps = packageManager.queryIntentActivities(clockIntent, PackageManager.MATCH_DEFAULT_ONLY);

            if (!calendarApps.isEmpty()) {
                calendarIntent.setPackage(calendarApps.get(0).activityInfo.packageName);
               try {
                    mContext.startActivity(calendarIntent);
                } catch (ActivityNotFoundException e) {
                }
            } else if (!clockApps.isEmpty()) {
                clockIntent.setPackage(clockApps.get(0).activityInfo.packageName);
                try {
                    mContext.startActivity(clockIntent);
                } catch (ActivityNotFoundException e) {
                }
            } else {
                Toast.makeText(mContext, R.string.intent_no_app_clock_found, Toast.LENGTH_SHORT).show();
            }
        }
    };

    public QuickEventsController(Context context) {
        mContext = context;
        mResources = context.getResources();
    }

    public void initQuickEvents() {
        updateQuickEvents();
    }

    public void updateQuickEvents() {
        nowPlayingEvent();
        initNowPlayingEvent();
        psonalityEvent();
    }

    public void forceUpdatePsonality() {
        if (mActiveContextualEvent != null) {
            if (mActiveContextualEvent.isExpired()) {
                mActiveContextualEvent = null;
            } else if (mActiveContextualEvent.isSticky) {
                refreshActiveContextualEvent();
                return;
            }
        }
        // Force clear cache and regenerate PSA - used for scheduled updates
        clearCachedPSA();
        psonalityEvent();
    }

    public void updatePsonality() {
        if (mActiveContextualEvent != null) {
            if (mActiveContextualEvent.isExpired()) {
                mActiveContextualEvent = null;
            } else if (mActiveContextualEvent.isSticky) {
                refreshActiveContextualEvent();
                return;
            } else {
                psonalityEvent();
                return;
            }
        }
        forceUpdatePsonality();
    }

    public int getActiveContextualType() {
        if (mActiveContextualEvent != null) {
            if (mActiveContextualEvent.isExpired()) {
                mActiveContextualEvent = null;
                return CONTEXT_EVENT_NONE;
            }
            return mActiveContextualEvent.type;
        }
        return CONTEXT_EVENT_NONE;
    }

    public int getActiveContextualTypeForSource(String sourceKey) {
        if (mActiveContextualEvent != null && sourceKey != null
                && sourceKey.equals(mActiveContextualEvent.sourceKey)) {
            if (mActiveContextualEvent.isExpired()) {
                mActiveContextualEvent = null;
                return CONTEXT_EVENT_NONE;
            }
            return mActiveContextualEvent.type;
        }
        return CONTEXT_EVENT_NONE;
    }

    public boolean hasActiveContextualEvent() {
        return getActiveContextualType() != CONTEXT_EVENT_NONE;
    }

    private String pickRandomFromArray(int resId) {
        String[] arr = getCachedArray(resId);
        if (arr == null || arr.length == 0) return null;
        return arr[getLuckyNumber(0, arr.length - 1)];
    }

    private String formatLowTemplate(String template, int level) {
        try {
            return String.format(Locale.getDefault(), template, level);
        } catch (Exception e) {
            return template.replace("%1$d", String.valueOf(level)).replace("%%", "%");
        }
    }

    private String formatBtTemplate(String template, String devName, int level) {
        String name = TextUtils.isEmpty(devName) ? "Device" : devName;
        try {
            return String.format(Locale.getDefault(), template, name, level);
        } catch (Exception e) {
            return template.replace("%1$s", name).replace("%2$d", String.valueOf(level)).replace("%%", "%");
        }
    }

    private void refreshActiveContextualEvent() {
        if (mActiveContextualEvent == null || mActiveContextualEvent.isExpired()) return;
        ContextualEvent old = mActiveContextualEvent;
        String newMsg = null;
        switch (old.type) {
            case CONTEXT_EVENT_CHARGING:
                newMsg = pickRandomFromArray(R.array.quickspace_psa_charging);
                break;
            case CONTEXT_EVENT_BATTERY_FULL:
                newMsg = pickRandomFromArray(R.array.quickspace_psa_battery_full);
                break;
            case CONTEXT_EVENT_BATTERY_LOW:
                newMsg = pickRandomFromArray(R.array.quickspace_psa_battery_low);
                if (newMsg != null) newMsg = formatLowTemplate(newMsg, old.argLevel);
                break;
            case CONTEXT_EVENT_BT_BATTERY:
                newMsg = pickRandomFromArray(R.array.quickspace_psa_bt_battery);
                if (newMsg != null) newMsg = formatBtTemplate(newMsg, old.argName, old.argLevel);
                break;
            default:
                return;
        }
        if (newMsg == null) return;
        mActiveContextualEvent = new ContextualEvent(
                old.type,
                newMsg,
                SystemClock.elapsedRealtime(),
                old.durationMs,
                old.icon,
                old.clickAction,
                old.sourceKey,
                old.isSticky,
                old.argLevel,
                old.argName);
        updateQuickEvents();
    }

    private void clearCachedPSA() {
        mCachedPSAMessage = null;
        mCachedPSAHour = -1;
        mCachedPSAIsRandom = false;
    }

    private boolean shouldUseCachedPSA(int currentHour) {
        if (mCachedPSAMessage == null) return false;
        
        // For random messages, use cache until next scheduled update
        if (mCachedPSAIsRandom) return true;
        
        // For time-based messages, only use cache if same hour
        return mCachedPSAHour == currentHour;
    }

    private void nowPlayingEvent() {
        if (mEventNowPlaying && !mPlayingActive) {
            mIsQuickEvent = false;
            mEventNowPlaying = false;
        }
    }

    private void initNowPlayingEvent() {
        if (!LauncherPrefs.SHOW_QUICKSPACE_NOWPLAYING.get(mContext)) return;

        if (!mPlayingActive) return;

        if (mNowPlayingTitle == null) return;

        mEventTitle = mNowPlayingTitle;
        mGreetings = mResources.getString(R.string.qe_now_playing_ext_one);
        mClockExt = "";
        if (mNowPlayingArtist == null ) {
            mEventTitleSub = mResources.getString(R.string.qe_now_playing_unknown_artist);
        } else {
            mEventTitleSub = mNowPlayingArtist;
        }
        mEventSubIcon = MSMHProxy.INSTANCE(mContext).getMediaAppIcon();
        mIsQuickEvent = true;
        mEventNowPlaying = true;

        mEventTitleSubAction = view -> MSMHProxy.INSTANCE(mContext).launchMediaApp();
    }

    public static String getDayOfWeek(Context context) {
        DateFormat format = DateFormat.getInstanceForSkeleton("EEEE", Locale.getDefault());
        format.setContext(DisplayContext.CAPITALIZATION_FOR_STANDALONE);
        return format.format(System.currentTimeMillis());
    }

    public static String getShortDate(Context context) {
        DateFormat format = DateFormat.getInstanceForSkeleton("dMMMM", Locale.getDefault());
        format.setContext(DisplayContext.CAPITALIZATION_FOR_STANDALONE);
        return format.format(System.currentTimeMillis());
    }

    public static String getFullDateLine(Context context) {
        android.text.format.DateFormat.getBestDateTimePattern(Locale.getDefault(), "EEEEdMMMM");
        DateFormat format = DateFormat.getInstanceForSkeleton("EEEE, d MMMM", Locale.getDefault());
        format.setContext(DisplayContext.CAPITALIZATION_FOR_STANDALONE);
        return format.format(System.currentTimeMillis());
    }

    private String formatDateTime(Context context, int style) {
        String styleText;
        if (style == 1) { // Extended
            styleText = context.getString(R.string.quickspace_date_format_minimalistic);
        } else {
            styleText = context.getString(R.string.quickspace_date_format);
        }

        if (mDateFormat == null || !styleText.equals(mLastDateFormatSkeleton)) {
            mDateFormat = DateFormat.getInstanceForSkeleton(styleText, Locale.getDefault());
            mDateFormat.setContext(DisplayContext.CAPITALIZATION_FOR_STANDALONE);
            mLastDateFormatSkeleton = styleText;
        }

        return mDateFormat.format(System.currentTimeMillis());
    }

    private void updatePSACache(int hourOfDay) {
        if (shouldUseCachedPSA(hourOfDay)) return;

        int luckNumber = getLuckyNumber(6); // 0..6
        boolean useRandom = (luckNumber == 0);

        mPSAStr = null;
        if (!useRandom) {
            mPSAStr = getPSAStr(hourOfDay);
        }

        if (mPSAStr == null) {
            mPSAStr = mResources.getStringArray(R.array.quickspace_psa_random);
            useRandom = true;
        }

        mCachedPSAMessage = mPSAStr[getLuckyNumber(0, mPSAStr.length - 1)];
        mCachedPSAHour = hourOfDay;
        mCachedPSAIsRandom = useRandom;
    }

    private void psonalityEvent() {
        int hourOfDay = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
        if (LauncherPrefs.SHOW_QUICKSPACE_PSONALITY.get(mContext)) {
            updatePSACache(hourOfDay);
        }

        if (mEventNowPlaying) return;

        mEventTitle = formatDateTime(mContext, Integer.parseInt(LauncherPrefs.QUICKSPACE_UI_STYLE.get(mContext)));

        if (hourOfDay >= 5 && hourOfDay <= 9) {
            mGreetings = mResources.getString(R.string.quickspace_grt_morning);
            mClockExt = mResources.getString(R.string.quickspace_ext_one);
        } else if (hourOfDay >= 12 && hourOfDay <= 15) {
            mGreetings = mResources.getString(R.string.quickspace_grt_afternoon);
            mClockExt = mResources.getString(R.string.quickspace_ext_two);
        } else if (hourOfDay >= 16 && hourOfDay <= 20) {
            mGreetings = mResources.getString(R.string.quickspace_grt_evening);
            mClockExt = mResources.getString(R.string.quickspace_ext_two);
        } else if (hourOfDay >= 21 && hourOfDay <= 23) {
            mGreetings = mResources.getString(R.string.quickspace_grt_night);
            mClockExt = mResources.getString(R.string.quickspace_ext_two);
        } else if (hourOfDay >= 0 && hourOfDay <= 3) {
            mGreetings = mResources.getString(R.string.quickspace_grt_night);
            mClockExt = mResources.getString(R.string.quickspace_ext_two);
        } else {
            mGreetings = mResources.getString(R.string.quickspace_grt_general);
            mClockExt = mResources.getString(R.string.quickspace_ext_two);
        }

        if (!LauncherPrefs.SHOW_QUICKSPACE_PSONALITY.get(mContext)) {
            mIsQuickEvent = false;
            return;
        }

        if (mActiveContextualEvent != null) {
            if (!mActiveContextualEvent.isExpired()) {
                mEventTitleSub = mActiveContextualEvent.message;
                mEventTitleSubAction = mActiveContextualEvent.clickAction != null ? mActiveContextualEvent.clickAction : mPSAAction;
                mEventSubIcon = mActiveContextualEvent.icon;
                mIsQuickEvent = true;
                return;
            } else {
                mActiveContextualEvent = null;
            }
        }

        mEventTitleSub = mCachedPSAMessage;
        mEventTitleSubAction = mPSAAction;
        mIsQuickEvent = true;
        mEventSubIcon = null;
    }

    public void triggerChargingEvent() {
        String msg = pickRandomFromArray(R.array.quickspace_psa_charging);
        if (msg != null) {
            mActiveContextualEvent = new ContextualEvent(
                CONTEXT_EVENT_CHARGING,
                msg,
                SystemClock.elapsedRealtime(),
                10 * 60 * 1000,
                null,
                mBatteryAction,
                SOURCE_PHONE,
                true,
                -1,
                null
            );
            updateQuickEvents();
        }
    }

    public void triggerBatteryFullEvent() {
        String msg = pickRandomFromArray(R.array.quickspace_psa_battery_full);
        if (msg != null) {
            mActiveContextualEvent = new ContextualEvent(
                CONTEXT_EVENT_BATTERY_FULL,
                msg,
                SystemClock.elapsedRealtime(),
                10 * 60 * 1000,
                null,
                mBatteryAction,
                SOURCE_PHONE,
                true,
                100,
                null
            );
            updateQuickEvents();
        }
    }

    public void triggerBatteryLowEvent(int level) {
        String template = pickRandomFromArray(R.array.quickspace_psa_battery_low);
        if (template != null) {
            String msg = formatLowTemplate(template, level);
            mActiveContextualEvent = new ContextualEvent(
                CONTEXT_EVENT_BATTERY_LOW,
                msg,
                SystemClock.elapsedRealtime(),
                10 * 60 * 1000,
                null,
                mBatteryAction,
                SOURCE_PHONE,
                true,
                level,
                null
            );
            updateQuickEvents();
        }
    }

    public void triggerBtBatteryEvent(String deviceName, String address, int level) {
        String template = pickRandomFromArray(R.array.quickspace_psa_bt_battery);
        if (template != null) {
            String devName = TextUtils.isEmpty(deviceName) ? "Device" : deviceName;
            String msg = formatBtTemplate(template, devName, level);
            boolean sticky = level <= 20;
            mActiveContextualEvent = new ContextualEvent(
                CONTEXT_EVENT_BT_BATTERY,
                msg,
                SystemClock.elapsedRealtime(),
                5 * 60 * 1000,
                null,
                mBluetoothAction,
                address,
                sticky,
                level,
                devName
            );
            updateQuickEvents();
        }
    }

    public void updateBtBatteryLevel(String address, int level) {
        if (mActiveContextualEvent != null
                && mActiveContextualEvent.type == CONTEXT_EVENT_BT_BATTERY
                && address != null && address.equals(mActiveContextualEvent.sourceKey)
                && !mActiveContextualEvent.isExpired()
                && mActiveContextualEvent.argLevel != level) {
            triggerBtBatteryEvent(mActiveContextualEvent.argName, address, level);
        }
    }

    public void updateBatteryLowLevel(int level) {
        if (mActiveContextualEvent != null
                && mActiveContextualEvent.type == CONTEXT_EVENT_BATTERY_LOW
                && SOURCE_PHONE.equals(mActiveContextualEvent.sourceKey)
                && !mActiveContextualEvent.isExpired()
                && mActiveContextualEvent.argLevel != level) {
            triggerBatteryLowEvent(level);
        }
    }

    public void clearActiveContextualEvent(int type) {
        if (mActiveContextualEvent != null && (type == CONTEXT_EVENT_NONE || mActiveContextualEvent.type == type)) {
            mActiveContextualEvent = null;
            updateQuickEvents();
        }
    }

    public void clearActiveContextualEventForSource(String sourceKey) {
        if (mActiveContextualEvent != null && sourceKey != null && sourceKey.equals(mActiveContextualEvent.sourceKey)) {
            mActiveContextualEvent = null;
            updateQuickEvents();
        }
    }

    private String[] getPSAStr(int hour) {
        if (hour >= 0 && hour <= 3) {
            return getCachedArray(R.array.quickspace_psa_midnight);
        } else if (hour >= 5 && hour <= 9) {
            return getCachedArray(R.array.quickspace_psa_morning);
        } else if (hour >= 12 && hour <= 15) {
            return getCachedArray(R.array.quickspace_psa_noon);
        } else if (hour >= 16 && hour <= 18) {
            return getCachedArray(R.array.quickspace_psa_early_evening);
        } else if (hour >= 19 && hour <= 21) {
            return getCachedArray(R.array.quickspace_psa_evening);
        } else {
            return null;
        }
    }

    public boolean isQuickEvent() {
        return mIsQuickEvent;
    }

    public String getTitle() {
        return mEventTitle;
    }

    public String getActionTitle() {
        return mEventTitleSub;
    }

    public String getClockExt() {
        return mClockExt;
    }

    public String getGreetings() {
        return mGreetings;
    }

    public OnClickListener getAction() {
        return mEventTitleSubAction;
    }

    public Drawable getActionIcon() {
        return mEventSubIcon;
    }

    public String getPSAMessage() {
        if (!LauncherPrefs.SHOW_QUICKSPACE_PSONALITY.get(mContext)) return null;
        if (mActiveContextualEvent != null && !mActiveContextualEvent.isExpired()) {
            return mActiveContextualEvent.message;
        }
        return mCachedPSAMessage;
    }

    public OnClickListener getPSAAction() {
        if (mActiveContextualEvent != null && !mActiveContextualEvent.isExpired() && mActiveContextualEvent.clickAction != null) {
            return mActiveContextualEvent.clickAction;
        }
        return mPSAAction;
    }

    public int getLuckyNumber(int max) {
        return getLuckyNumber(0, max);
    }

    public int getLuckyNumber(int min, int max) {
        return ThreadLocalRandom.current().nextInt(min, max + 1);
    }

    public void setMediaInfo(String title, String artist, boolean activePlayback) {
        mNowPlayingTitle = title;
        mNowPlayingArtist = artist;
        mPlayingActive = activePlayback;
    }

    public boolean isNowPlaying() {
        return mPlayingActive;
    }

    public String getNowPlayingTitle() {
        return mNowPlayingTitle;
    }

    public String getNowPlayingArtist() {
        return mNowPlayingArtist;
    }

    public void onResume() {
        mDateFormat = null;
        mLastDateFormatSkeleton = null;
    }

    private String[] getCachedArray(int resId) {
        if (!mCachedPSAMap.containsKey(resId)) {
            mCachedPSAMap.put(resId, mResources.getStringArray(resId));
        }
        return mCachedPSAMap.get(resId);
    }
}
