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

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.text.TextPaint;
import android.util.AttributeSet;
import android.view.View;
import com.android.launcher3.R;
import java.util.Locale;

public class VerticalWordmarkView extends View {
  public static final float TRACKING_EM = 0.10f;
  private final TextPaint mPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG);
  private final Rect mInkBounds = new Rect();
  private String mText = "";
  private float mTextSizePx;
  private boolean mUseStackedGlyphs;
  private String[] mGlyphs = new String[0];
  private float mShadowRadius;
  private float mShadowDx;
  private float mShadowDy;
  private int mShadowColor;

  public VerticalWordmarkView(Context context) {
    this(context, null);
  }

  public VerticalWordmarkView(Context context, AttributeSet attrs) {
    this(context, attrs, 0);
  }

  public VerticalWordmarkView(Context context, AttributeSet attrs, int defStyleAttr) {
    super(context, attrs, defStyleAttr);
    mPaint.setTypeface(Typeface.DEFAULT_BOLD);
    mPaint.setLetterSpacing(TRACKING_EM);
    mPaint.setColor(Color.WHITE);
  }

  public void setText(String text) {
    String safeText = text == null ? "" : text;
    if (!safeText.equals(mText)) {
      mText = safeText;
      mUseStackedGlyphs = containsCjk(mText);
      mGlyphs = buildGlyphs(mText);
      requestLayout();
      invalidate();
    }
  }

  public void setTextColor(int color) {
    if (mPaint.getColor() != color) {
      mPaint.setColor(color);
      invalidate();
    }
  }

  public void setTextLocale(Locale locale) {
    if (locale != null && !locale.equals(mPaint.getTextLocale())) {
      mPaint.setTextLocale(locale);
      requestLayout();
      invalidate();
    }
  }

  public void setTypeface(Typeface typeface) {
    if (typeface != null && !typeface.equals(mPaint.getTypeface())) {
      mPaint.setTypeface(typeface);
      requestLayout();
      invalidate();
    }
  }

  public void setWordmarkShadow(int color, float radiusPx, float dxPx, float dyPx) {
    boolean geometryChanged = mShadowRadius != radiusPx
        || mShadowDx != dxPx || mShadowDy != dyPx;
    if (!geometryChanged && mShadowColor == color) return;

    mShadowColor = color;
    mShadowRadius = radiusPx;
    mShadowDx = dxPx;
    mShadowDy = dyPx;
    mPaint.setShadowLayer(radiusPx, dxPx, dyPx, color);
    if (geometryChanged) requestLayout();
    invalidate();
  }

  private int getShadowPadding() {
    return (int) Math.ceil(mShadowRadius
        + Math.max(Math.abs(mShadowDx), Math.abs(mShadowDy)));
  }

  public int getInkTop() {
    return getPaddingTop() + getShadowPadding();
  }

  public int getInkBottom() {
    return getInkTop() + (int) Math.ceil(measureInkHeight());
  }

  private float maxGlyphHeight() {
    float height = 0;
    for (String glyph : mGlyphs) {
      mPaint.getTextBounds(glyph, 0, glyph.length(), mInkBounds);
      height = Math.max(height, mInkBounds.height());
    }
    return height;
  }

  private float measureInkHeight() {
    if (mText.isEmpty()) return 0;
    if (!mUseStackedGlyphs) {
      mPaint.getTextBounds(mText, 0, mText.length(), mInkBounds);
      return mInkBounds.width();
    }
    float step = maxGlyphHeight() + TRACKING_EM * mPaint.getTextSize();
    String last = mGlyphs[mGlyphs.length - 1];
    mPaint.getTextBounds(last, 0, last.length(), mInkBounds);
    return step * (mGlyphs.length - 1) + mInkBounds.height();
  }

  @Override
  protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
    int heightMode = MeasureSpec.getMode(heightMeasureSpec);
    int heightSize = MeasureSpec.getSize(heightMeasureSpec);
    int shadowPadding = getShadowPadding();
    int verticalPadding = getPaddingTop() + getPaddingBottom() + 2 * shadowPadding;
    int availableHeight = heightMode == MeasureSpec.UNSPECIFIED
        ? Integer.MAX_VALUE / 4
        : Math.max(0, heightSize - verticalPadding);
    updateTextSize(availableHeight);

    float fittedSize = mPaint.getTextSize();
    mPaint.setTextSize(getResources().getDimension(
        R.dimen.quickspace_vertical_wordmark_max_size));
    Paint.FontMetrics metrics = mPaint.getFontMetrics();
    float laneWidth = metrics.descent - metrics.ascent;
    if (mUseStackedGlyphs) {
      laneWidth = Math.max(laneWidth, maxGlyphWidth());
    }
    mPaint.setTextSize(fittedSize);
    int wantedWidth = (int) Math.ceil(laneWidth)
        + getPaddingLeft() + getPaddingRight() + 2 * shadowPadding;
    int wantedHeight = (int) Math.ceil(measureInkHeight()) + verticalPadding;
    setMeasuredDimension(resolveSize(wantedWidth, widthMeasureSpec),
        resolveSize(wantedHeight, heightMeasureSpec));
  }

  private void updateTextSize(int availableHeight) {
    float min = getResources().getDimension(R.dimen.quickspace_vertical_wordmark_min_size);
    float max = getResources().getDimension(R.dimen.quickspace_vertical_wordmark_max_size);
    mTextSizePx = max;
    mPaint.setTextSize(max);
    if (mText.isEmpty()) return;
    if (availableHeight <= 0) {
      mTextSizePx = 0;
      mPaint.setTextSize(mTextSizePx);
      return;
    }
    if (measureInkHeight() <= availableHeight) return;

    mPaint.setTextSize(min);
    float low = measureInkHeight() <= availableHeight ? min : 0f;
    float high = max;
    for (int i = 0; i < 12; i++) {
      float mid = (low + high) / 2f;
      mPaint.setTextSize(mid);
      if (measureInkHeight() <= availableHeight) low = mid;
      else high = mid;
    }
    mTextSizePx = low;
    mPaint.setTextSize(mTextSizePx);
  }

  @Override
  protected void onDraw(Canvas canvas) {
    super.onDraw(canvas);
    if (mText.isEmpty() || mTextSizePx <= 0) return;
    float y = getInkTop();
    int shadowPadding = getShadowPadding();
    float left = getPaddingLeft() + shadowPadding;
    float innerWidth = Math.max(0, getWidth() - getPaddingLeft()
        - getPaddingRight() - 2 * shadowPadding);
    if (mUseStackedGlyphs) {
      float step = maxGlyphHeight() + TRACKING_EM * mPaint.getTextSize();
      for (String glyph : mGlyphs) {
        mPaint.getTextBounds(glyph, 0, glyph.length(), mInkBounds);
        float x = left + (innerWidth - mInkBounds.width()) / 2f
            - mInkBounds.left;
        canvas.drawText(glyph, x, y - mInkBounds.top, mPaint);
        y += step;
      }
      return;
    }

    mPaint.getTextBounds(mText, 0, mText.length(), mInkBounds);
    float x = left + (innerWidth - mInkBounds.height()) / 2f;
    canvas.save();
    if (getLayoutDirection() == LAYOUT_DIRECTION_RTL) {
      canvas.translate(x + mInkBounds.bottom, y - mInkBounds.left);
      canvas.rotate(90f);
    } else {
      canvas.translate(x - mInkBounds.top, y + mInkBounds.right);
      canvas.rotate(-90f);
    }
    canvas.drawText(mText, 0, 0, mPaint);
    canvas.restore();
  }

  private float maxGlyphWidth() {
    float maxWidth = 0;
    for (String glyph : mGlyphs) {
      mPaint.getTextBounds(glyph, 0, glyph.length(), mInkBounds);
      maxWidth = Math.max(maxWidth, mInkBounds.width());
    }
    return maxWidth;
  }

  private static String[] buildGlyphs(String text) {
    String[] glyphs = new String[text.codePointCount(0, text.length())];
    int glyphIndex = 0;
    for (int offset = 0; offset < text.length();) {
      int codePoint = text.codePointAt(offset);
      glyphs[glyphIndex++] = new String(Character.toChars(codePoint));
      offset += Character.charCount(codePoint);
    }
    return glyphs;
  }

  private static boolean containsCjk(String text) {
    for (int offset = 0; offset < text.length();) {
      int codePoint = text.codePointAt(offset);
      Character.UnicodeScript script = Character.UnicodeScript.of(codePoint);
      if (script == Character.UnicodeScript.HAN || script == Character.UnicodeScript.HIRAGANA
          || script == Character.UnicodeScript.KATAKANA || script == Character.UnicodeScript.HANGUL) {
        return true;
      }
      offset += Character.charCount(codePoint);
    }
    return false;
  }
}
