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
package com.android.launcher3.quickspace.views;

import android.animation.ValueAnimator;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.ColorStateList;
import android.icu.text.DateFormat;
import android.icu.text.DisplayContext;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.text.TextUtils;
import android.transition.ChangeBounds;
import android.transition.Fade;
import android.transition.TransitionManager;
import android.transition.TransitionSet;
import android.util.AttributeSet;
import android.view.View;
import android.view.ViewGroup;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.animation.DecelerateInterpolator;
import android.widget.LinearLayout;
import android.widget.TextView;
import com.android.launcher3.LauncherPrefs;
import com.android.launcher3.R;
import com.android.launcher3.quickspace.QuickBatteryController;
import com.android.launcher3.quickspace.QuickCalendarController;
import com.android.launcher3.quickspace.QuickEventsController;
import com.android.launcher3.quickspace.QuickSpaceStyleBinder;
import com.android.launcher3.quickspace.QuickspaceController;
import com.android.launcher3.quickspace.receivers.QuickSpaceActionReceiver;
import com.android.launcher3.util.Themes;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Locale;

public class VoltageVerticalView extends LinearLayout implements QuickSpaceStyleBinder {
  private static final long REFLOW_DURATION_MS = 200;

  private VerticalWordmarkView mWordmark;
  private TextView mDate;
  private View mDetails;
  private final Rect mHeaderInkBounds = new Rect();
  private View mRule;
  private View mWeatherRow;
  private TextView mWeatherLabel;
  private TextView mWeatherValue;
  private View mCalendarRow;
  private TextView mCalendarLabel;
  private TextView mCalendarValue;
  private View mEventRow;
  private TextView mEventLabel;
  private TextView mEventValue;
  private View mBatteryRow;
  private TextView mBatteryLabel;
  private TextView mBatteryValue;
  private View[] mMarkers;
  private View[] mDividers;
  private QuickspaceController mController;
  private QuickSpaceActionReceiver mActionReceiver;
  private DateFormat mDayFormat;
  private DateFormat mShortDayFormat;
  private DateFormat mDateFormat;
  private DateFormat mTimeFormat;
  private Locale mFormatLocale;
  private String mFormatTimeZone;
  private boolean mFormat24Hour;
  private boolean mFirstBind = true;
  private boolean mCompact;
  private boolean mSizeRebindPosted;
  private boolean mLastNowPlaying;
  private Object[] mLastValues;
  private Runnable mMidnightRefresh;
  private long mNextMidnightRefresh;
  private View.OnClickListener mEventClickListener;
  private View.OnClickListener mCalendarClickListener;
  private boolean mClockReceiverRegistered;
  private final Runnable mSizeRebind = () -> {
    mSizeRebindPosted = false;
    bind(false);
  };
  private final BroadcastReceiver mClockReceiver = new BroadcastReceiver() {
    @Override
    public void onReceive(Context context, Intent intent) {
      if (mMidnightRefresh != null) removeCallbacks(mMidnightRefresh);
      mNextMidnightRefresh = 0;
      if (mController != null && mController.getCalendarController() != null) {
        mController.getCalendarController().refresh();
      }
      bind(false);
    }
  };

  public VoltageVerticalView(Context context) { this(context, null); }
  public VoltageVerticalView(Context context, AttributeSet attrs) { this(context, attrs, 0); }
  public VoltageVerticalView(Context context, AttributeSet attrs, int defStyleAttr) {
    super(context, attrs, defStyleAttr);
  }

  @Override
  protected void onFinishInflate() {
    super.onFinishInflate();
    mWordmark = findViewById(R.id.quickspace_vertical_wordmark);
    mDate = findViewById(R.id.quickspace_vertical_date);
    mDetails = findViewById(R.id.quickspace_vertical_details);
    mDate.setTypeface(Typeface.create(mDate.getTypeface(), Typeface.NORMAL));
    mRule = findViewById(R.id.quickspace_vertical_rule);
    mWeatherRow = findViewById(R.id.quickspace_vertical_weather_row);
    mWeatherLabel = findViewById(R.id.quickspace_vertical_weather_label);
    mWeatherValue = findViewById(R.id.quickspace_vertical_weather_value);
    mCalendarRow = findViewById(R.id.quickspace_vertical_calendar_row);
    mCalendarLabel = findViewById(R.id.quickspace_vertical_calendar_label);
    mCalendarValue = findViewById(R.id.quickspace_vertical_calendar_value);
    mEventRow = findViewById(R.id.quickspace_vertical_event_row);
    mEventLabel = findViewById(R.id.quickspace_vertical_event_label);
    mEventValue = findViewById(R.id.quickspace_vertical_event_value);
    mBatteryRow = findViewById(R.id.quickspace_vertical_battery_row);
    mBatteryLabel = findViewById(R.id.quickspace_vertical_battery_label);
    mBatteryValue = findViewById(R.id.quickspace_vertical_battery_value);
    mWordmark.setTypeface(Typeface.create("sans-serif-condensed", Typeface.BOLD));
    mDate.setLetterSpacing(-0.015f);
    for (TextView text : new TextView[] {mDate, mWeatherLabel, mWeatherValue,
        mCalendarLabel, mCalendarValue, mEventLabel, mEventValue,
        mBatteryLabel, mBatteryValue}) {
      text.setIncludeFontPadding(false);
    }

    for (TextView label : new TextView[] {mWeatherLabel, mCalendarLabel,
        mEventLabel, mBatteryLabel}) {
      label.setLetterSpacing(0.12f);
    }
    mMarkers = new View[] { findViewById(R.id.quickspace_vertical_weather_marker),
        findViewById(R.id.quickspace_vertical_calendar_marker),
        findViewById(R.id.quickspace_vertical_event_marker),
        findViewById(R.id.quickspace_vertical_battery_marker) };
    mDividers = new View[] { findViewById(R.id.quickspace_vertical_weather_divider),
        findViewById(R.id.quickspace_vertical_calendar_divider),
        findViewById(R.id.quickspace_vertical_event_divider) };
    mActionReceiver = new QuickSpaceActionReceiver(getContext());
    View.OnClickListener calendarAction = mActionReceiver.getCalendarAction();
    mWordmark.setOnClickListener(calendarAction);
    mDate.setOnClickListener(calendarAction);
    mWeatherRow.setOnClickListener(mActionReceiver.getWeatherAction(hasGoogleApp()));
    mCalendarClickListener = v -> {
      if (mController == null || mActionReceiver == null) return;
      QuickCalendarController calendar = mController.getCalendarController();
      QuickCalendarController.CalendarEvent event = calendar == null ? null : calendar.getEvent();
      if (event != null) mActionReceiver.getCalendarEventAction(event.getId()).onClick(v);
    };
    mEventClickListener = v -> {
      if (mController == null || mController.getEventController() == null) return;
      View.OnClickListener action = mController.getEventController().getAction();
      if (action != null) action.onClick(v);
    };
    mBatteryRow.setOnClickListener(v -> {
      if (mController != null && mController.getBatteryController() != null) {
        mController.getBatteryController().launchBatterySettings();
      }
    });
    setFocusable(true);
    setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);
    setChildrenAccessibility(this);
  }

  @Override
  public void onBind(QuickspaceController controller) {
    if (controller != null) mController = controller;
    bind(false);
  }

  @Override
  public void onDataUpdated(boolean allowAnimation) {
    Object[] values = captureValues();
    if (!mFirstBind && Arrays.equals(mLastValues, values)) return;
    bind(allowAnimation && !mFirstBind, values);
  }

  @Override
  protected void onAttachedToWindow() {
    super.onAttachedToWindow();
    if (!mClockReceiverRegistered) {
      IntentFilter filter = new IntentFilter();
      filter.addAction(Intent.ACTION_DATE_CHANGED);
      filter.addAction(Intent.ACTION_TIME_CHANGED);
      filter.addAction(Intent.ACTION_TIMEZONE_CHANGED);
      filter.addAction(Intent.ACTION_LOCALE_CHANGED);
      getContext().registerReceiver(mClockReceiver, filter);
      mClockReceiverRegistered = true;
    }
    bind(false);
  }

  @Override
  protected void onDetachedFromWindow() {
    unregisterClockReceiver();
    removeCallbacks(mSizeRebind);
    mSizeRebindPosted = false;
    if (mMidnightRefresh != null) removeCallbacks(mMidnightRefresh);
    mNextMidnightRefresh = 0;
    super.onDetachedFromWindow();
  }

  private void unregisterClockReceiver() {
    if (!mClockReceiverRegistered) return;
    getContext().unregisterReceiver(mClockReceiver);
    mClockReceiverRegistered = false;
  }

  @Override
  protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
    super.onMeasure(widthMeasureSpec, heightMeasureSpec);
    if (mWordmark == null || mDate == null) return;

    LayoutParams lp = (LayoutParams) mWordmark.getLayoutParams();
    int topMargin = getDateInkTop() - mWordmark.getInkTop();
    if (lp.topMargin != topMargin) {
      lp.topMargin = topMargin;
      super.onMeasure(widthMeasureSpec, heightMeasureSpec);
    }
  }

  @Override
  protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
    super.onLayout(changed, left, top, right, bottom);
    if (mRule == null || mDetails == null || mWordmark == null || mDate == null) return;

    int ruleTop = mDetails.getTop() + mDate.getTop() + getDateInkTop();
    int ruleBottom = Math.max(mDetails.getBottom(),
        mWordmark.getTop() + mWordmark.getInkBottom());
    mRule.layout(mRule.getLeft(), ruleTop, mRule.getRight(), Math.max(ruleTop, ruleBottom));
  }

  private int getDateInkTop() {
    String text = mDate.getText().toString();
    if (text.isEmpty() || mDate.getBaseline() < 0) return 0;
    mDate.getPaint().getTextBounds(text, 0, text.length(), mHeaderInkBounds);
    return mDate.getBaseline() + mHeaderInkBounds.top;
  }

  @Override
  protected void onSizeChanged(int w, int h, int oldw, int oldh) {
    super.onSizeChanged(w, h, oldw, oldh);
    float fontScale = Math.max(1f, getResources().getConfiguration().fontScale);
    boolean compact = h < getResources().getDimension(
        R.dimen.quickspace_vertical_compact_height) * fontScale;
    if (mCompact != compact) {
      mCompact = compact;
      if (!mSizeRebindPosted) {
        mSizeRebindPosted = true;
        post(mSizeRebind);
      }
    }
  }

  private void bind(boolean animate) {
    bind(animate, captureValues());
  }

  private void bind(boolean animate, Object[] values) {
    if (mController == null || mWordmark == null) return;
    if (animate && shouldAnimateReflow()) {
      TransitionSet transition = new TransitionSet().setOrdering(TransitionSet.ORDERING_TOGETHER)
          .addTransition(new ChangeBounds()).addTransition(new Fade())
          .setDuration(REFLOW_DURATION_MS).setInterpolator(new DecelerateInterpolator());
      TransitionManager.beginDelayedTransition(this, transition);
    }
    Locale locale = getResources().getConfiguration().getLocales().get(0);
    String day = formatDay(locale, mCompact);
    String date = formatDate(locale);
    mWordmark.setTextLocale(locale);
    mWordmark.setText(day);
    mDate.setText(date);
    QuickEventsController events = mController.getEventController();
    boolean nowPlayingEnabled = LauncherPrefs.SHOW_QUICKSPACE_NOWPLAYING.get(getContext());
    boolean psaEnabled = LauncherPrefs.SHOW_QUICKSPACE_PSONALITY.get(getContext());
    boolean nowPlaying = nowPlayingEnabled && events != null && events.isNowPlaying();
    String temperature = LauncherPrefs.SHOW_QUICKSPACE_WEATHER.get(getContext())
        && mController.isWeatherAvailable() ? mController.getWeatherTemperature() : null;
    String condition = LauncherPrefs.SHOW_QUICKSPACE_WEATHER_TEXT.get(getContext())
        ? mController.getWeatherCondition() : null;
    boolean showWeather = !TextUtils.isEmpty(temperature);
    QuickCalendarController calendar = mController.getCalendarController();
    QuickCalendarController.CalendarEvent calendarEvent = calendar == null ? null : calendar.getEvent();
    String calendarText = getCalendarText(calendarEvent, locale);
    boolean showCalendar = !TextUtils.isEmpty(calendarText);
    String event = getEventText(events, nowPlaying, psaEnabled);
    boolean showEvent = !TextUtils.isEmpty(event);
    QuickBatteryController battery = mController.getBatteryController();
    boolean showBattery = LauncherPrefs.SHOW_QUICKSPACE_BATTERY.get(getContext())
        && battery != null && battery.getBatteryLevel() >= 0;
    String batteryText = showBattery ? getResources().getString(R.string.quickspace_vertical_battery_value,
        battery.getDeviceName(), battery.getBatteryLevel()) : null;
    updateRowVisibility(showWeather, showCalendar, showEvent, showBattery, nowPlaying);
    mWeatherValue.setText(joinWeather(temperature, condition));
    mCalendarValue.setText(calendarText);
    mCalendarRow.setOnClickListener(showCalendar ? mCalendarClickListener : null);
    mCalendarRow.setClickable(showCalendar);
    mEventLabel.setText(nowPlaying ? R.string.quickspace_vertical_now_playing : R.string.quickspace_vertical_note);
    mEventValue.setText(event);
    mEventValue.setMaxLines(mCompact ? 1 : 2);
    boolean canOpenEvent = showEvent && events != null && events.getAction() != null;
    mEventRow.setOnClickListener(canOpenEvent ? mEventClickListener : null);
    mEventRow.setClickable(canOpenEvent);
    mBatteryValue.setText(batteryText);
    updateContentDescription(day, date,
        mWeatherRow.getVisibility() == View.VISIBLE, temperature, condition,
        mCalendarRow.getVisibility() == View.VISIBLE, calendarText,
        mEventRow.getVisibility() == View.VISIBLE, event,
        mBatteryRow.getVisibility() == View.VISIBLE, batteryText);
    mLastNowPlaying = nowPlaying;
    mLastValues = values;
    mFirstBind = false;
    scheduleMidnightRefresh();
  }

  private void updateRowVisibility(boolean weather, boolean calendar, boolean event, boolean battery,
      boolean nowPlaying) {
    if (mCompact) {
      boolean useEvent = nowPlaying && event;
      boolean useCalendar = !useEvent && calendar;
      boolean useWeather = !useEvent && !useCalendar && weather;
      boolean useBattery = !useEvent && !useCalendar && !useWeather && battery;
      setRowVisible(mWeatherRow, useWeather);
      setRowVisible(mCalendarRow, useCalendar);
      setRowVisible(mEventRow, useEvent);
      setRowVisible(mBatteryRow, useBattery);
      mRule.setVisibility(View.VISIBLE);
      setMarkersVisible(false);
      setDividersVisible(false);
      return;
    }
    setRowVisible(mWeatherRow, weather);
    setRowVisible(mCalendarRow, calendar);
    setRowVisible(mEventRow, event);
    setRowVisible(mBatteryRow, battery);
    mRule.setVisibility(View.VISIBLE);
    setMarkerVisible(0, weather);
    setMarkerVisible(1, calendar);
    setMarkerVisible(2, event);
    setMarkerVisible(3, battery);
    setDividerVisible(0, weather && (calendar || event || battery));
    setDividerVisible(1, calendar && (event || battery));
    setDividerVisible(2, event && battery);
  }

  private String getEventText(QuickEventsController events, boolean nowPlaying,
      boolean psaEnabled) {
    if (events == null) return null;
    if (!nowPlaying) {
      if (!psaEnabled) return null;
      return mCompact ? null : events.getPSAMessage();
    }
    String title = events.getNowPlayingTitle();
    String artist = events.getNowPlayingArtist();
    return TextUtils.isEmpty(artist) ? title : TextUtils.isEmpty(title) ? artist : title + " — " + artist;
  }

  private String getCalendarText(QuickCalendarController.CalendarEvent event, Locale locale) {
    if (event == null || TextUtils.isEmpty(event.getTitle())) return null;
    ensureFormats(locale);
    DateFormat eventDateFormat = DateFormat.getInstanceForSkeleton("MMMd", locale);
    String today = eventDateFormat.format(System.currentTimeMillis());
    if (event.isAllDay()) {
      eventDateFormat.setTimeZone(android.icu.util.TimeZone.getTimeZone("UTC"));
    }
    String eventDate = eventDateFormat.format(event.getStartTime());
    String prefix = eventDate.equals(today) ? "" : eventDate + " · ";
    if (!event.isAllDay()) {
      prefix += mTimeFormat.format(event.getStartTime()) + " · ";
    }
    return prefix + event.getTitle();
  }

  private String formatDay(Locale locale, boolean abbreviated) {
    ensureFormats(locale);
    String day = (abbreviated ? mShortDayFormat : mDayFormat).format(System.currentTimeMillis())
        .toUpperCase(locale);
    return abbreviated && day.codePointCount(0, day.length()) > 4
        ? day.substring(0, day.offsetByCodePoints(0, 4)) : day;
  }

  private String formatDate(Locale locale) { ensureFormats(locale); return mDateFormat.format(System.currentTimeMillis()); }

  private void ensureFormats(Locale locale) {
    android.icu.util.TimeZone zone = android.icu.util.TimeZone.getDefault();
    boolean use24Hour = android.text.format.DateFormat.is24HourFormat(getContext());
    if (locale.equals(mFormatLocale) && mDayFormat != null
        && zone.getID().equals(mFormatTimeZone)
        && use24Hour == mFormat24Hour) return;
    mFormatLocale = locale;
    mFormatTimeZone = zone.getID();
    mFormat24Hour = use24Hour;
    mDayFormat = DateFormat.getInstanceForSkeleton("EEEE", locale);
    mShortDayFormat = DateFormat.getInstanceForSkeleton("EEE", locale);
    mDateFormat = DateFormat.getInstanceForSkeleton("dMMMM", locale);
    mTimeFormat = DateFormat.getInstanceForSkeleton(use24Hour ? "Hm" : "hm", locale);
    mDayFormat.setTimeZone(zone);
    mShortDayFormat.setTimeZone(zone);
    mDateFormat.setTimeZone(zone);
    mTimeFormat.setTimeZone(zone);
    mDayFormat.setContext(DisplayContext.CAPITALIZATION_FOR_STANDALONE);
    mShortDayFormat.setContext(DisplayContext.CAPITALIZATION_FOR_STANDALONE);
    mDateFormat.setContext(DisplayContext.CAPITALIZATION_FOR_STANDALONE);
  }

  private Object[] captureValues() {
    Calendar today = Calendar.getInstance();
    Locale locale = getResources().getConfiguration().getLocales().get(0);
    QuickEventsController events = mController == null ? null : mController.getEventController();
    boolean nowPlayingEnabled = LauncherPrefs.SHOW_QUICKSPACE_NOWPLAYING.get(getContext());
    boolean psaEnabled = LauncherPrefs.SHOW_QUICKSPACE_PSONALITY.get(getContext());
    boolean nowPlaying = nowPlayingEnabled && events != null && events.isNowPlaying();
    QuickCalendarController calendar = mController == null ? null : mController.getCalendarController();
    QuickCalendarController.CalendarEvent calendarEvent = calendar == null ? null : calendar.getEvent();
    QuickBatteryController battery = mController == null ? null : mController.getBatteryController();
    boolean weatherAvailable = mController != null && mController.isWeatherAvailable();

    return new Object[] {
        locale,
        today.getTimeZone().getID(),
        today.get(Calendar.YEAR),
        today.get(Calendar.DAY_OF_YEAR),
        android.text.format.DateFormat.is24HourFormat(getContext()),
        mCompact,
        nowPlaying,
        nowPlayingEnabled,
        psaEnabled,
        getEventText(events, nowPlaying, psaEnabled),
        events != null && events.getAction() != null,
        LauncherPrefs.SHOW_QUICKSPACE_WEATHER.get(getContext()),
        LauncherPrefs.SHOW_QUICKSPACE_WEATHER_TEXT.get(getContext()),
        weatherAvailable ? mController.getWeatherTemperature() : null,
        weatherAvailable ? mController.getWeatherCondition() : null,
        LauncherPrefs.SHOW_QUICKSPACE_BATTERY.get(getContext()),
        battery == null ? null : battery.getDeviceName(),
        battery == null ? -1 : battery.getBatteryLevel(),
        calendarEvent == null ? -1L : calendarEvent.getId(),
        calendarEvent == null ? null : calendarEvent.getTitle(),
        calendarEvent == null ? 0L : calendarEvent.getStartTime(),
        calendarEvent == null ? 0L : calendarEvent.getEndTime(),
        calendarEvent != null && calendarEvent.isAllDay()
    };
  }

  private boolean shouldAnimateReflow() { return isLaidOut() && ValueAnimator.areAnimatorsEnabled(); }

  private void scheduleMidnightRefresh() {
    if (mMidnightRefresh == null) mMidnightRefresh = () -> {
      mNextMidnightRefresh = 0;
      bind(false);
    };
    if (mNextMidnightRefresh > System.currentTimeMillis()) return;
    Calendar next = Calendar.getInstance();
    next.add(Calendar.DAY_OF_YEAR, 1);
    next.set(Calendar.HOUR_OF_DAY, 0);
    next.set(Calendar.MINUTE, 0);
    next.set(Calendar.SECOND, 1);
    next.set(Calendar.MILLISECOND, 0);
    mNextMidnightRefresh = next.getTimeInMillis();
    postDelayed(mMidnightRefresh, Math.max(1000, mNextMidnightRefresh - System.currentTimeMillis()));
  }

  private void updateContentDescription(String day, String date, boolean weather, String temperature,
      String condition, boolean calendar, String calendarText, boolean event, String eventText,
      boolean battery, String batteryText) {
    StringBuilder description = new StringBuilder(
        getResources().getString(R.string.quickspace_vertical_content_description)).append(": ")
        .append(day).append(", ").append(date);
    if (weather) description.append(", ").append(joinWeather(temperature, condition));
    if (calendar) description.append(", ").append(calendarText);
    if (event) description.append(", ").append(eventText);
    if (battery) description.append(", ").append(batteryText);
    setContentDescription(description);
  }

  private boolean hasGoogleApp() {
    try { return getContext().getPackageManager().getApplicationInfo("com.google.android.googlequicksearchbox", 0).enabled; }
    catch (Exception e) { return false; }
  }

  private static String joinWeather(String temperature, String condition) {
    return TextUtils.isEmpty(temperature) ? "" : TextUtils.isEmpty(condition) ? temperature
        : temperature + "  " + condition;
  }
  private static void setRowVisible(View row, boolean visible) { if (row != null) row.setVisibility(visible ? View.VISIBLE : View.GONE); }
  private void setMarkersVisible(boolean visible) { for (View v : mMarkers) if (v != null) v.setVisibility(visible ? View.VISIBLE : View.GONE); }
  private void setMarkerVisible(int index, boolean visible) { if (mMarkers[index] != null) mMarkers[index].setVisibility(visible ? View.VISIBLE : View.GONE); }
  private void setDividersVisible(boolean visible) { for (View v : mDividers) if (v != null) v.setVisibility(visible ? View.VISIBLE : View.GONE); }
  private void setDividerVisible(int index, boolean visible) { if (mDividers[index] != null) mDividers[index].setVisibility(visible ? View.VISIBLE : View.GONE); }

  @Override
  public void applyColors(ColorStateList colors, boolean useBlackText) {
    if (colors == null || mWordmark == null) return;
    int color = colors.getDefaultColor();
    int muted = Color.argb(Math.round(Color.alpha(color) * 0.82f),
        Color.red(color), Color.green(color), Color.blue(color));
    int shadow = useBlackText ? Color.TRANSPARENT : Color.argb(96, 0, 0, 0);
    float radius = getResources().getDimension(R.dimen.quickspace_vertical_shadow_radius);
    float shadowDy = 0.5f * getResources().getDisplayMetrics().density;
    mWordmark.setTextColor(color);
    mWordmark.setWordmarkShadow(shadow, radius, 0, shadowDy);
    applyTextColorAndShadow(mDate, color, shadow, radius);
    applyTextColorAndShadow(mWeatherLabel, muted, shadow, radius);
    applyTextColorAndShadow(mWeatherValue, color, shadow, radius);
    applyTextColorAndShadow(mCalendarLabel, muted, shadow, radius);
    applyTextColorAndShadow(mCalendarValue, color, shadow, radius);
    applyTextColorAndShadow(mEventLabel, muted, shadow, radius);
    applyTextColorAndShadow(mEventValue, color, shadow, radius);
    applyTextColorAndShadow(mBatteryLabel, muted, shadow, radius);
    applyTextColorAndShadow(mBatteryValue, color, shadow, radius);
    if (mRule != null) {
      mRule.setBackgroundColor(Color.argb(Math.round(Color.alpha(color) * 0.34f),
          Color.red(color), Color.green(color), Color.blue(color)));
    }
    int accent = Themes.getAttrColor(getContext(), R.attr.workspaceAccentColor);
    for (View marker : mMarkers) if (marker != null) marker.setBackgroundColor(accent);
    for (View divider : mDividers) {
      if (divider != null) divider.setBackgroundColor(Color.argb(
          Math.round(Color.alpha(color) * 0.20f),
          Color.red(color), Color.green(color), Color.blue(color)));
    }
  }

  private static void applyTextColorAndShadow(TextView view, int color, int shadow, float radius) {
    if (view == null) return;
    view.setTextColor(color);
    float dy = 0.5f * view.getResources().getDisplayMetrics().density;
    view.setShadowLayer(radius, 0, dy, shadow);
  }
  private static void setChildrenAccessibility(ViewGroup parent) {
    for (int i = 0; i < parent.getChildCount(); i++) {
      View child = parent.getChildAt(i);
      child.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
      if (child instanceof ViewGroup) setChildrenAccessibility((ViewGroup) child);
    }
  }

  @Override
  public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info) {
    super.onInitializeAccessibilityNodeInfo(info);
    info.addAction(new AccessibilityNodeInfo.AccessibilityAction(
        R.id.quickspace_vertical_a11y_calendar,
        getResources().getString(R.string.quickspace_vertical_a11y_calendar)));
    if (mWeatherRow != null && mWeatherRow.getVisibility() == View.VISIBLE) {
      info.addAction(new AccessibilityNodeInfo.AccessibilityAction(
          R.id.quickspace_vertical_a11y_weather,
          getResources().getString(R.string.quickspace_vertical_a11y_weather)));
    }
    if (mCalendarRow != null && mCalendarRow.isClickable()
        && mCalendarRow.getVisibility() == View.VISIBLE) {
      info.addAction(new AccessibilityNodeInfo.AccessibilityAction(
          R.id.quickspace_vertical_a11y_calendar_event,
          getResources().getString(R.string.quickspace_vertical_a11y_calendar_event)));
    }
    if (mEventRow != null && mEventRow.isClickable() && mEventRow.getVisibility() == View.VISIBLE) {
      info.addAction(new AccessibilityNodeInfo.AccessibilityAction(
          R.id.quickspace_vertical_a11y_event,
          getResources().getString(mLastNowPlaying
              ? R.string.quickspace_vertical_a11y_event
              : R.string.quickspace_vertical_a11y_note)));
    }
    if (mBatteryRow != null && mBatteryRow.getVisibility() == View.VISIBLE) {
      info.addAction(new AccessibilityNodeInfo.AccessibilityAction(
          R.id.quickspace_vertical_a11y_battery,
          getResources().getString(R.string.quickspace_vertical_a11y_battery)));
    }
  }

  @Override
  public boolean performAccessibilityAction(int action, android.os.Bundle arguments) {
    if (action == R.id.quickspace_vertical_a11y_calendar) return mDate.performClick();
    if (action == R.id.quickspace_vertical_a11y_weather) return mWeatherRow.performClick();
    if (action == R.id.quickspace_vertical_a11y_calendar_event) return mCalendarRow.performClick();
    if (action == R.id.quickspace_vertical_a11y_event) return mEventRow.performClick();
    if (action == R.id.quickspace_vertical_a11y_battery) return mBatteryRow.performClick();
    return super.performAccessibilityAction(action, arguments);
  }

  @Override
  public void onDestroy() {
    unregisterClockReceiver();
    removeCallbacks(mSizeRebind);
    mSizeRebindPosted = false;
    if (mMidnightRefresh != null) removeCallbacks(mMidnightRefresh);
    mController = null;
    mActionReceiver = null;
    mDayFormat = null;
    mShortDayFormat = null;
    mDateFormat = null;
    mTimeFormat = null;
    mFormatLocale = null;
    mFormatTimeZone = null;
    mFirstBind = true;
    mLastNowPlaying = false;
    mLastValues = null;
    mNextMidnightRefresh = 0;
    mEventClickListener = null;
    mCalendarClickListener = null;
  }
}
