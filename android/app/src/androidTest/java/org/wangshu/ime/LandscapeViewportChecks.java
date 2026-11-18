package org.wangshu.ime;

import android.app.Instrumentation;
import android.content.Context;
import android.content.res.Configuration;
import android.os.Build;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;

/** Measures native views using a synthetic landscape configuration. No host or setting changes. */
final class LandscapeViewportChecks {
    private LandscapeViewportChecks() { }

    static String run(Instrumentation test) {
        test.runOnMainSync(() -> {
            Configuration landscape = new Configuration(test.getTargetContext().getResources().getConfiguration());
            landscape.orientation = Configuration.ORIENTATION_LANDSCAPE;
            landscape.screenHeightDp = 200;
            landscape.screenWidthDp = 560;
            Context context = test.getTargetContext().createConfigurationContext(landscape);
            float density = context.getResources().getDisplayMetrics().density;
            int width = Math.round(560 * density);
            int shortParent = Math.round(80 * density);
            int expected = LandscapeKeyboardViewport.maximumHeight(Math.round(200 * density),
                    Math.round(96 * density));
            VoiceHoldTouchSurface surface = new VoiceHoldTouchSurface(context);
            LandscapeKeyboardViewport viewport = new LandscapeKeyboardViewport(context);
            LinearLayout rows = new LinearLayout(context);
            rows.setOrientation(LinearLayout.VERTICAL);
            View last = null;
            for (int index = 0; index < 7; index++) {
                last = new View(context);
                rows.addView(last, new LinearLayout.LayoutParams(-1, Math.round(30 * density)));
            }
            viewport.setKeyboard(rows);
            surface.addView(viewport, new FrameLayout.LayoutParams(-1, -2));
            int widthSpec = View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY);
            measure(surface, widthSpec, 0, View.MeasureSpec.UNSPECIFIED);
            require(surface.getMeasuredHeight() == expected, "IME surface obeys height cap");
            ScrollView scroll = (ScrollView) rows.getParent();
            require(rows.getHeight() > scroll.getHeight(), "overflow rows remain in scroll content");
            scroll.scrollTo(0, rows.getHeight());
            require(last != null && last.getBottom() - scroll.getScrollY() == scroll.getHeight(),
                    "last row can scroll fully into the viewport");
            measure(surface, widthSpec, shortParent, View.MeasureSpec.AT_MOST);
            require(surface.getMeasuredHeight() == shortParent, "smaller parent constraint is honored");

            VoiceHoldOverlay overlay = new VoiceHoldOverlay(context);
            viewport.setVoiceLayer(overlay);
            measure(surface, widthSpec, 0, View.MeasureSpec.UNSPECIFIED);
            require(overlay.getParent() == viewport && scroll.getVisibility() == View.GONE
                    && overlay.getHeight() == expected, "voice is outside the key scroll and fits current budget");
            measure(surface, widthSpec, shortParent, View.MeasureSpec.AT_MOST);
            require(overlay.getHeight() == shortParent && surface.getHeight() == shortParent,
                    "voice follows a smaller parent without a fixed-height scroll layer");
            if (Build.VERSION.SDK_INT >= 30) LandscapeViewportModernInsets.check(viewport, surface, overlay,
                    widthSpec, shortParent, density);
            viewport.setVoiceLayer(null);
            measure(surface, widthSpec, 0, View.MeasureSpec.UNSPECIFIED);
            require(overlay.getParent() == null && scroll.getVisibility() == View.VISIBLE && scroll.getHeight() > 0,
                    "leaving voice restores the scrolling keys");
        });
        return "PASS synthetic landscape cap, key scrolling, fitted voice including resize, and return to keys\n";
    }

    static void measure(View view, int width, int height, int mode) {
        view.measure(width, View.MeasureSpec.makeMeasureSpec(height, mode));
        view.layout(0, 0, view.getMeasuredWidth(), view.getMeasuredHeight());
    }

    static void require(boolean value, String label) {
        if (!value) throw new AssertionError(label);
    }
}
