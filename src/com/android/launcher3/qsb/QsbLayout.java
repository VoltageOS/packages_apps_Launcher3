package com.android.launcher3.qsb;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.LauncherActivityInfo;
import android.content.pm.LauncherApps;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.drawable.AdaptiveIconDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.LayerDrawable;
import android.graphics.drawable.PaintDrawable;
import android.net.Uri;
import android.os.Process;
import android.util.AttributeSet;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;

import com.android.launcher3.LauncherPrefs;
import com.android.launcher3.R;
import com.android.launcher3.Reorderable;
import com.android.launcher3.Utilities;
import com.android.launcher3.graphics.ThemeManager;
import com.android.launcher3.icons.IconThemeController;
import com.android.launcher3.icons.LauncherIcons;
import com.android.launcher3.util.MultiTranslateDelegate;
import com.android.launcher3.util.Themes;

import java.util.List;

public class QsbLayout extends FrameLayout implements Reorderable {

    private static final String TAG = "QsbLayout";

    private ImageView micIcon;
    private ImageView gIcon;
    private ImageView lensIcon;
    private ImageView geminiIcon;
    private FrameLayout inner;

    private final MultiTranslateDelegate mTranslateDelegate = new MultiTranslateDelegate(this);
    private float mScaleForReorderBounce = 1f;

    private boolean mIsThemed;

    public QsbLayout(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public QsbLayout(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();
        micIcon = findViewById(R.id.mic_icon);
        gIcon = findViewById(R.id.g_icon);
        lensIcon = findViewById(R.id.lens_icon);
        geminiIcon = findViewById(R.id.gemini_icon);
        inner = findViewById(R.id.inner);

        setUpMainSearch();
        setUpBackground();
        clipIconRipples();

        mIsThemed = LauncherPrefs.DOCK_THEME.get(getContext());

        setupGIcon();
        setupLensIcon();
        setupMicIcon();
        setupGeminiIcon();
    }

    private void clipIconRipples() {
        float cornerRadius = getCornerRadius();
        PaintDrawable pd = new PaintDrawable(Color.TRANSPARENT);
        pd.setCornerRadius(cornerRadius);
        micIcon.setClipToOutline(cornerRadius > 0);
        micIcon.setBackground(pd);
        lensIcon.setClipToOutline(cornerRadius > 0);
        lensIcon.setBackground(pd);
        gIcon.setClipToOutline(cornerRadius > 0);
        gIcon.setBackground(pd);
        geminiIcon.setClipToOutline(cornerRadius > 0);
        geminiIcon.setBackground(pd);
    }

    private void setUpBackground() {
        float cornerRadius = getCornerRadius();
        int alphaValue = (LauncherPrefs.HOTSEAT_QSB_OPACITY.get(getContext()) * 255) / 100;
        int baseColor = Themes.getAttrColor(getContext(), R.attr.qsbFillColor);
        if (LauncherPrefs.DOCK_THEME.get(getContext()))
            baseColor = Themes.getAttrColor(getContext(), R.attr.qsbFillColorThemed);
        int color = Color.argb(alphaValue, Color.red(baseColor), Color.green(baseColor), Color.blue(baseColor));
        float strokeWidth = LauncherPrefs.HOTSEAT_QSB_STROKE_WIDTH.get(getContext());

        PaintDrawable backgroundDrawable = new PaintDrawable(color);
        backgroundDrawable.setCornerRadius(cornerRadius);

        if (strokeWidth != 0f) {
            PaintDrawable strokeDrawable = new PaintDrawable(Themes.getColorAccent(getContext()));
            strokeDrawable.getPaint().setStyle(Paint.Style.STROKE);
            strokeDrawable.getPaint().setStrokeWidth(strokeWidth);
            strokeDrawable.setCornerRadius(cornerRadius);
            LayerDrawable combinedDrawable = new LayerDrawable(new Drawable[]{backgroundDrawable, strokeDrawable});
            inner.setClipToOutline(cornerRadius > 0);
            inner.setBackground(combinedDrawable);
        } else {
            inner.setClipToOutline(cornerRadius > 0);
            inner.setBackground(backgroundDrawable);
        }
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int width = MeasureSpec.getSize(widthMeasureSpec);
        int height = MeasureSpec.getSize(heightMeasureSpec);

        setMeasuredDimension(width, height);

        for (int i = 0; i < getChildCount(); i++) {
            final View child = getChildAt(i);
            if (child != null) {
                measureChildWithMargins(child, widthMeasureSpec, 0, heightMeasureSpec, 0);
            }
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        setOnClickListener(null);
        if (gIcon != null) gIcon.setOnClickListener(null);
        if (lensIcon != null) lensIcon.setOnClickListener(null);
        if (micIcon != null) micIcon.setOnClickListener(null);
        if (geminiIcon != null) geminiIcon.setOnClickListener(null);
        if (inner != null) inner.setBackground(null);
    }

    private void setUpMainSearch() {
        setOnClickListener(view -> {
            String pkg = QsbContainerView.getSearchWidgetPackageName(view.getContext());
            if (pkg == null) return;
            String[] actionsToTry = {
                "android.search.action.GLOBAL_SEARCH",
                "android.intent.action.WEB_SEARCH",
                Intent.ACTION_WEB_SEARCH,
            };
            for (String action : actionsToTry) {
                Intent intent = new Intent(action)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK)
                        .setPackage(pkg);
                if (view.getContext().getPackageManager().resolveActivity(intent, 0) != null) {
                    view.getContext().startActivity(intent);
                    return;
                }
            }
        });
    }

    private void setupGIcon() {
        String searchPackage = QsbContainerView.getSearchWidgetPackageName(getContext());
        if (searchPackage == null) {
            gIcon.setVisibility(View.GONE);
            return;
        }
        if (Utilities.GSA_PACKAGE.equals(searchPackage)) {
            gIcon.setImageResource(mIsThemed
                    ? R.drawable.ic_super_g_themed
                    : R.drawable.ic_super_g_color);
        } else {
            try {
                LauncherApps launcherApps = (LauncherApps)
                        getContext().getSystemService(Context.LAUNCHER_APPS_SERVICE);
                List<LauncherActivityInfo> activities =
                        launcherApps.getActivityList(searchPackage, Process.myUserHandle());

                Drawable appIcon;
                if (!activities.isEmpty()) {
                    appIcon = activities.get(0).getIcon(0);
                } else {
                    appIcon = getContext().getPackageManager().getApplicationIcon(searchPackage);
                }

                if (mIsThemed) {
                    IconThemeController themeController =
                            ThemeManager.INSTANCE.get(getContext()).getThemeController();
                    if (themeController != null) {
                        AdaptiveIconDrawable aid;
                        if (appIcon instanceof AdaptiveIconDrawable) {
                            aid = (AdaptiveIconDrawable) appIcon;
                        } else {
                            try (LauncherIcons li = LauncherIcons.obtain(getContext())) {
                                aid = li.wrapToAdaptiveIcon(appIcon);
                            }
                        }
                        AdaptiveIconDrawable themedIcon =
                                themeController.createThemedAdaptiveIcon(getContext(), aid, null);
                        if (themedIcon != null) appIcon = themedIcon;
                    }
                }

                int sizePx = (int) (24 * getResources().getDisplayMetrics().density);
                Bitmap bmp = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888);
                Canvas canvas = new Canvas(bmp);
                appIcon.setBounds(0, 0, sizePx, sizePx);
                appIcon.draw(canvas);
                gIcon.setColorFilter(null);
                gIcon.setImageBitmap(bmp);

                ViewGroup.MarginLayoutParams lp =
                        (ViewGroup.MarginLayoutParams) gIcon.getLayoutParams();
                int dp8 = (int) (8 * getResources().getDisplayMetrics().density);
                lp.setMarginStart(dp8);
                gIcon.setLayoutParams(lp);
                gIcon.setPadding(dp8, dp8, dp8, dp8);

            } catch (Exception e) {
                gIcon.setVisibility(View.GONE);
            }
        }
        gIcon.setOnClickListener(view -> {
            String pkg = QsbContainerView.getSearchWidgetPackageName(view.getContext());
            if (pkg == null) return;
            Intent intent = view.getContext().getPackageManager().getLaunchIntentForPackage(pkg);
            if (intent != null) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
                view.getContext().startActivity(intent);
            }
        });
    }

    private void setupLensIcon() {
        try {
            String searchPackage = QsbContainerView.getSearchWidgetPackageName(getContext());
            if (!Utilities.GSA_PACKAGE.equals(searchPackage)) {
                lensIcon.setVisibility(View.GONE);
                return;
            }
            lensIcon.setImageResource(mIsThemed ? R.drawable.ic_lens_themed : R.drawable.ic_lens_color);
            lensIcon.setOnClickListener(view -> {
                Intent intent = new Intent();
                intent.setAction(Intent.ACTION_VIEW);
                intent.setComponent(new ComponentName(Utilities.GSA_PACKAGE, Utilities.LENS_ACTIVITY));
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
                intent.setData(Uri.parse(Utilities.LENS_URI));
                intent.putExtra("LensHomescreenShortcut", true);
                view.getContext().startActivity(intent);
            });
        } catch (Exception e) {
            lensIcon.setVisibility(View.GONE);
        }
    }

    private void setupMicIcon() {
        try {
            String searchPackage = QsbContainerView.getSearchWidgetPackageName(getContext());
            boolean isGSA = Utilities.GSA_PACKAGE.equals(searchPackage);
            boolean isMusicSearch = isGSA && Utilities.isMusicSearchEnabled(getContext());

            if (isMusicSearch) {
                micIcon.setImageResource(mIsThemed ? R.drawable.ic_music_themed : R.drawable.ic_music_color);
            } else {
                micIcon.setImageResource(mIsThemed ? R.drawable.ic_mic_themed : R.drawable.ic_mic_color);
            }

            final Intent micIntent = new Intent()
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
            if (isMusicSearch) {
                micIntent.setAction("com.google.android.googlequicksearchbox.MUSIC_SEARCH")
                         .setPackage(searchPackage);
            } else if (isGSA) {
                micIntent.setAction(Intent.ACTION_VOICE_COMMAND);
            } else {
                micIntent.setAction(Intent.ACTION_WEB_SEARCH)
                         .setPackage(searchPackage);
            }

            if (getContext().getPackageManager().resolveActivity(micIntent, 0) != null) {
                micIcon.setOnClickListener(view -> view.getContext().startActivity(micIntent));
            } else {
                micIcon.setVisibility(View.GONE);
            }
        } catch (Exception e) {
            micIcon.setVisibility(View.GONE);
        }
    }

    private void setupGeminiIcon() {
        if (geminiIcon == null) return;

        if (!Utilities.isPackageInstalled(getContext(), Utilities.GEMINI_PACKAGE)) {
            geminiIcon.setVisibility(View.GONE);
            return;
        }

        geminiIcon.setVisibility(View.VISIBLE);
        geminiIcon.setImageResource(mIsThemed
                ? R.drawable.ic_gemini_themed
                : R.drawable.ic_gemini_color);

        geminiIcon.setOnClickListener(view -> {
            try {
                Intent intent = view.getContext().getPackageManager()
                        .getLaunchIntentForPackage(Utilities.GEMINI_PACKAGE);
                if (intent != null) {
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
                    view.getContext().startActivity(intent);
                }
            } catch (Exception e) {
                geminiIcon.setVisibility(View.GONE);
                Log.e(TAG, "Gemini launch failed", e);
            }
        });
    }

    private float getCornerRadius() {
        Resources res = getContext().getResources();
        float qsbWidgetHeight = res.getDimension(R.dimen.qsb_widget_height);
        float qsbWidgetPadding = res.getDimension(R.dimen.qsb_widget_vertical_padding);
        float innerHeight = qsbWidgetHeight - 2 * qsbWidgetPadding;
        return (innerHeight / 2) * ((float) LauncherPrefs.SEARCH_RADIUS_SIZE.get(getContext()) / 100f);
    }

    @Override
    public MultiTranslateDelegate getTranslateDelegate() {
        return mTranslateDelegate;
    }

    @Override
    public void setReorderBounceScale(float scale) {
        mScaleForReorderBounce = scale;
        super.setScaleX(scale);
        super.setScaleY(scale);
    }

    @Override
    public float getReorderBounceScale() {
        return mScaleForReorderBounce;
    }
}
