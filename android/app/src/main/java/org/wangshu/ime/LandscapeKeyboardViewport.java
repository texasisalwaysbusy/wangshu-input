package org.wangshu.ime;

import android.content.Context;
import android.graphics.Insets;
import android.os.Build;
import android.view.View;
import android.view.WindowInsets;
import android.widget.FrameLayout;
import android.widget.ScrollView;

/** A bounded landscape surface, with scrolling keys and a separate fitted voice layer. */
final class LandscapeKeyboardViewport extends FrameLayout {
    private final ScrollView keyboard;
    private int verticalSystemInsets;
    private View voiceLayer;

    LandscapeKeyboardViewport(Context context) {
        super(context);
        keyboard = new ScrollView(context);
        keyboard.setOverScrollMode(OVER_SCROLL_NEVER);
        keyboard.setVerticalScrollBarEnabled(true);
        addView(keyboard, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));
    }

    void setKeyboard(View rows) {
        keyboard.addView(rows, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));
    }

    void setVoiceLayer(View layer) {
        if (layer == voiceLayer) return;
        if (voiceLayer != null) removeView(voiceLayer);
        voiceLayer = layer;
        keyboard.setVisibility(layer == null ? VISIBLE : GONE);
        if (layer != null) addView(layer, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
        requestApplyInsets();
    }

    static boolean configurationIncludesInsets(int sdk, int targetSdk) {
        return sdk >= 35 && targetSdk >= 35;
    }

    static int usableHeight(int configuredHeight, int displayHeight, int systemInsets, boolean includesInsets) {
        if (configuredHeight > 0) return Math.max(0, configuredHeight - (includesInsets ? systemInsets : 0));
        return Math.max(0, displayHeight - systemInsets);
    }

    static int maximumHeight(int usable, int minimumEditorHeight) {
        int editorHeight = Math.max(usable - (int) (usable * 3L / 5),
                Math.min(minimumEditorHeight, usable / 2));
        return usable - editorHeight;
    }

    @Override public WindowInsets onApplyWindowInsets(WindowInsets insets) {
        int reserved;
        if (Build.VERSION.SDK_INT >= 30) {
            Insets bars = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
            Insets edges = insets.getInsets(WindowInsets.Type.navigationBars() | WindowInsets.Type.displayCutout());
            reserved = bars.top + bars.bottom;
            if (getPaddingLeft() != edges.left || getPaddingRight() != edges.right || getPaddingBottom() != edges.bottom)
                setPadding(edges.left, 0, edges.right, edges.bottom);
        } else {
            reserved = insets.getSystemWindowInsetTop() + insets.getSystemWindowInsetBottom();
        }
        if (reserved != verticalSystemInsets) {
            verticalSystemInsets = reserved;
            requestLayout();
        }
        return super.onApplyWindowInsets(insets);
    }

    @Override protected void onMeasure(int widthSpec, int heightSpec) {
        int screenDp = getResources().getConfiguration().screenHeightDp;
        float density = getResources().getDisplayMetrics().density;
        int usable = usableHeight(Math.round(screenDp * density), getResources().getDisplayMetrics().heightPixels,
                verticalSystemInsets, configurationIncludesInsets(Build.VERSION.SDK_INT,
                getContext().getApplicationInfo().targetSdkVersion));
        int limit = maximumHeight(usable, Math.round(96 * density));
        if (MeasureSpec.getMode(heightSpec) != MeasureSpec.UNSPECIFIED) {
            limit = Math.min(limit, MeasureSpec.getSize(heightSpec));
        }
        int mode = voiceLayer != null ? MeasureSpec.EXACTLY : MeasureSpec.AT_MOST;
        super.onMeasure(widthSpec, MeasureSpec.makeMeasureSpec(limit, mode));
    }
}
