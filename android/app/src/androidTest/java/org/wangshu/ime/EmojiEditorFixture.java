package org.wangshu.ime;

import android.app.Activity;
import android.os.Build;
import android.os.Bundle;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;

/** Ephemeral test-only editors. Never accesses existing documents or clipboard. */
public final class EmojiEditorFixture extends Activity {
    private boolean initialShowRequested;
    @Override public void onCreate(Bundle state){
        // The test APK does not inherit the target app's NoActionBar theme.
        setTheme(android.R.style.Theme_Material_Light_NoActionBar);
        super.onCreate(state);
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        LinearLayout column=new LinearLayout(this);column.setOrientation(LinearLayout.VERTICAL);
        column.setPadding(dp(16),dp(12),dp(16),dp(12));
        EditText first=null;
        for(int i=1;i<=2;i++){
            EditText editor=new EditText(this);editor.setSingleLine(true);
            editor.setContentDescription("表情测试编辑器 "+i);editor.setMinHeight(dp(64));
            column.addView(editor,new LinearLayout.LayoutParams(-1,-2));
            if(i==1)first=editor;
        }
        ScrollView scroll=new ScrollView(this);scroll.setFillViewport(true);scroll.addView(column);
        FrameLayout viewport=new FrameLayout(this);
        viewport.addView(scroll,new FrameLayout.LayoutParams(-1,-1));
        if(Build.VERSION.SDK_INT>=30){
            getWindow().setDecorFitsSystemWindows(false);
            viewport.setOnApplyWindowInsetsListener((v,insets)->{
                android.graphics.Insets safe=insets.getInsets(WindowInsets.Type.systemBars()|WindowInsets.Type.displayCutout()|WindowInsets.Type.ime());
                v.setPadding(safe.left,safe.top,safe.right,safe.bottom);return insets;
            });
        }
        setContentView(viewport);viewport.requestApplyInsets();
        if(first!=null)first.requestFocus();
    }
    @Override public void onWindowFocusChanged(boolean focused){
        super.onWindowFocusChanged(focused);
        if(!focused||initialShowRequested)return;
        initialShowRequested=true;
        // Request once after window focus; never steal a later user's field focus.
        getWindow().getDecorView().post(()->{
            android.view.View editor=getCurrentFocus();
            if(editor instanceof EditText&&editor.hasFocus()&&editor.hasWindowFocus())
                ((InputMethodManager)getSystemService(INPUT_METHOD_SERVICE)).showSoftInput(editor,0);
        });
    }
    private int dp(int value){return Math.round(value*getResources().getDisplayMetrics().density);}
}
