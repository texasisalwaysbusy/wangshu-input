package org.wangshu.ime;

import android.annotation.TargetApi;
import android.graphics.Insets;
import android.view.View;
import android.view.WindowInsets;

/** WindowInsets.Builder is used only on API 30+ in the synthetic view suite. */
@TargetApi(30)
final class LandscapeViewportModernInsets {
    private LandscapeViewportModernInsets() { }

    static void check(LandscapeKeyboardViewport viewport, View surface, View overlay,
                      int widthSpec, int parentHeight, float density) {
        int side = Math.round(18 * density);
        int bottom = Math.round(12 * density);
        int type = WindowInsets.Type.navigationBars();
        viewport.dispatchApplyWindowInsets(new WindowInsets.Builder()
                .setInsets(type, Insets.of(0, 0, side, bottom)).setVisible(type, true).build());
        LandscapeViewportChecks.measure(surface, widthSpec, parentHeight, View.MeasureSpec.AT_MOST);
        LandscapeViewportChecks.require(overlay.getWidth() == surface.getWidth() - side
                && overlay.getHeight() == surface.getHeight() - bottom, "voice respects side and bottom navigation");
        viewport.dispatchApplyWindowInsets(new WindowInsets.Builder()
                .setInsets(type, Insets.NONE).setVisible(type, false).build());
        LandscapeViewportChecks.measure(surface, widthSpec, parentHeight, View.MeasureSpec.AT_MOST);
        LandscapeViewportChecks.require(overlay.getWidth() == surface.getWidth()
                && overlay.getHeight() == surface.getHeight(), "removed navigation padding is cleared");
    }
}
