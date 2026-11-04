package org.wangshu.ime;

import android.content.Context;
import android.widget.ImageButton;

/** Cursor drag control whose short tap and accessibility click share one action. */
final class CursorControlButton extends ImageButton {
    public CursorControlButton(Context context){super(context);}
    @Override public boolean performClick(){
        // Keep the framework click event and registered listener, exactly once.
        return super.performClick();
    }
}
