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
package com.android.launcher3.quickspace;

import static com.android.launcher3.util.Executors.MAIN_EXECUTOR;
import static com.android.launcher3.util.Executors.THREAD_POOL_EXECUTOR;

import android.Manifest;
import android.content.ContentUris;
import android.content.Context;
import android.content.pm.PackageManager;
import android.database.ContentObserver;
import android.database.Cursor;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.provider.CalendarContract;
import android.util.Log;
import com.android.launcher3.LauncherPrefs;
import com.android.launcher3.R;

public final class QuickCalendarController {
  private static final String TAG = "QuickCalendar";
  private static final long LOOKAHEAD_MILLIS = 7L * 24 * 60 * 60 * 1000;
  private static final long EMPTY_RETRY_MILLIS = 2 * 60 * 1000;
  private static final String[] PROJECTION = {
      CalendarContract.Instances.EVENT_ID,
      CalendarContract.Instances.TITLE,
      CalendarContract.Instances.BEGIN,
      CalendarContract.Instances.END,
      CalendarContract.Instances.ALL_DAY
  };

  public static final class CalendarEvent {
    private final long mId;
    private final String mTitle;
    private final long mStartTime;
    private final long mEndTime;
    private final boolean mAllDay;

    CalendarEvent(long id, String title, long startTime, long endTime, boolean allDay) {
      mId = id;
      mTitle = title;
      mStartTime = startTime;
      mEndTime = endTime;
      mAllDay = allDay;
    }

    public long getId() { return mId; }
    public String getTitle() { return mTitle; }
    public long getStartTime() { return mStartTime; }
    public long getEndTime() { return mEndTime; }
    public boolean isAllDay() { return mAllDay; }
  }

  private final Context mContext;
  private final Runnable mChangeListener;
  private final Handler mHandler;
  private final Runnable mTimedRefresh;
  private final ContentObserver mObserver;
  private boolean mQueryInFlight;
  private boolean mRefreshPending;
  private int mGeneration;
  private volatile CalendarEvent mEvent;
  private boolean mObserverRegistered;
  private boolean mStarted;
  private boolean mDestroyed;

  public QuickCalendarController(Context context, Runnable changeListener) {
    mContext = context.getApplicationContext();
    mChangeListener = changeListener;
    mHandler = new Handler(context.getMainLooper());
    mTimedRefresh = this::refresh;
    mObserver = new ContentObserver(mHandler) {
      @Override
      public void onChange(boolean selfChange) {
        refresh();
      }

      @Override
      public void onChange(boolean selfChange, Uri uri) {
        refresh();
      }
    };
  }

  public void onResume() {
    if (Looper.myLooper() != mHandler.getLooper()) {
      mHandler.post(this::onResume);
      return;
    }
    if (mDestroyed) return;
    mStarted = true;
    if (!isEnabled() || !hasPermission()) {
      clearUnavailableEvent();
      return;
    }
    registerObserver();
    refresh();
  }

  public void onPause() {
    if (Looper.myLooper() != mHandler.getLooper()) {
      mHandler.post(this::onPause);
      return;
    }
    mStarted = false;
    mGeneration++;
    mRefreshPending = false;
    mHandler.removeCallbacks(mTimedRefresh);
    unregisterObserver();
  }

  public void onDestroy() {
    if (Looper.myLooper() != mHandler.getLooper()) {
      mHandler.post(this::onDestroy);
      return;
    }
    onPause();
    mDestroyed = true;
    mEvent = null;
  }

  public CalendarEvent getEvent() {
    return isEnabled() && hasPermission() ? mEvent : null;
  }

  public void refresh() {
    if (Looper.myLooper() != mHandler.getLooper()) {
      mHandler.post(this::refresh);
      return;
    }
    if (mDestroyed || !mStarted) return;
    if (!isEnabled() || !hasPermission()) {
      clearUnavailableEvent();
      return;
    }
    if (mQueryInFlight) {
      mRefreshPending = true;
      return;
    }
    registerObserver();
    mHandler.removeCallbacks(mTimedRefresh);
    mQueryInFlight = true;
    final int generation = mGeneration;
    THREAD_POOL_EXECUTOR.execute(() -> {
      CalendarEvent event = queryNextEvent();
      MAIN_EXECUTOR.execute(() -> {
        mQueryInFlight = false;
        if (mDestroyed || !mStarted) return;
        if (generation != mGeneration || mRefreshPending) {
          mRefreshPending = false;
          refresh();
          return;
        }
        if (!isEnabled() || !hasPermission()) {
          clearUnavailableEvent();
          return;
        }
        setEvent(event);
        scheduleRefresh(event);
      });
    });
  }

  private void clearUnavailableEvent() {
    mGeneration++;
    mRefreshPending = false;
    mHandler.removeCallbacks(mTimedRefresh);
    unregisterObserver();
    setEvent(null);
  }

  private boolean isEnabled() {
    return LauncherPrefs.SHOW_QUICKSPACE_CALENDAR.get(mContext);
  }

  private boolean hasPermission() {
    return mContext.checkSelfPermission(Manifest.permission.READ_CALENDAR)
        == PackageManager.PERMISSION_GRANTED;
  }

  private void registerObserver() {
    if (mObserverRegistered) return;
    try {
      mContext.getContentResolver().registerContentObserver(
          CalendarContract.Events.CONTENT_URI, true, mObserver);
      mContext.getContentResolver().registerContentObserver(
          CalendarContract.Instances.CONTENT_URI, true, mObserver);
      mContext.getContentResolver().registerContentObserver(
          CalendarContract.Calendars.CONTENT_URI, true, mObserver);
      mObserverRegistered = true;
    } catch (SecurityException ignored) {
      try {
        mContext.getContentResolver().unregisterContentObserver(mObserver);
      } catch (IllegalArgumentException ignoredAgain) {
      }
      setEvent(null);
    }
  }

  private void unregisterObserver() {
    if (!mObserverRegistered) return;
    mContext.getContentResolver().unregisterContentObserver(mObserver);
    mObserverRegistered = false;
  }

  private CalendarEvent queryNextEvent() {
    long now = System.currentTimeMillis();
    Uri.Builder builder = CalendarContract.Instances.CONTENT_URI.buildUpon();
    ContentUris.appendId(builder, now);
    ContentUris.appendId(builder, now + LOOKAHEAD_MILLIS);
    try (Cursor cursor = mContext.getContentResolver().query(builder.build(), PROJECTION,
        CalendarContract.Calendars.VISIBLE + "=1 AND "
            + CalendarContract.Instances.END + ">?",
        new String[] {Long.toString(now)},
        CalendarContract.Instances.BEGIN + " ASC")) {
      if (cursor == null || !cursor.moveToFirst()) return null;
      String title = cursor.getString(1);
      if (title == null || title.isEmpty()) {
        title = mContext.getString(R.string.quickspace_calendar_untitled);
      }
      return new CalendarEvent(cursor.getLong(0), title, cursor.getLong(2),
          cursor.getLong(3), cursor.getInt(4) != 0);
    } catch (RuntimeException e) {
      Log.w(TAG, "Calendar query failed", e);
      return null;
    }
  }

  private void scheduleRefresh(CalendarEvent event) {
    mHandler.removeCallbacks(mTimedRefresh);
    long now = System.currentTimeMillis();
    long refreshAt = event == null ? now + EMPTY_RETRY_MILLIS
        : event.getStartTime() > now ? event.getStartTime() : event.getEndTime();
    mHandler.postDelayed(mTimedRefresh, Math.max(1000, refreshAt - now));
  }

  private void setEvent(CalendarEvent event) {
    CalendarEvent current = mEvent;
    if (sameEvent(current, event)) return;
    mEvent = event;
    mChangeListener.run();
  }

  private static boolean sameEvent(CalendarEvent first, CalendarEvent second) {
    if (first == second) return true;
    if (first == null || second == null) return false;
    return first.mId == second.mId && first.mStartTime == second.mStartTime
        && first.mEndTime == second.mEndTime && first.mAllDay == second.mAllDay
        && java.util.Objects.equals(first.mTitle, second.mTitle);
  }
}
