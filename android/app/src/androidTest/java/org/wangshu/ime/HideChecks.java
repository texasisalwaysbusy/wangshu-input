package org.wangshu.ime;

import android.app.*;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.content.*;
import android.os.*;
import android.view.KeyEvent;
import android.view.accessibility.*;
import android.view.inputmethod.*;
import android.widget.EditText;

/** The actual native hide button changes the OS window, preserving the editor. */
final class HideChecks {
    static boolean visible(UiAutomation auto){
        for(AccessibilityWindowInfo window:auto.getWindows())
            if(window.getType()==AccessibilityWindowInfo.TYPE_INPUT_METHOD&&window.getRoot()!=null)return true;
        return false;
    }
    static void await(UiAutomation auto,boolean wanted){
        long until=SystemClock.elapsedRealtime()+30000;
        while(SystemClock.elapsedRealtime()<until){if(visible(auto)==wanted&&auto.getRootInActiveWindow()!=null)return;SystemClock.sleep(200);}
        throw new AssertionError("actual IME window visibility did not become "+wanted);
    }
    static String run(Instrumentation test)throws Exception{
        Context c=test.getTargetContext();if(DataFiles.initialize(c)<100000)throw new AssertionError("dictionary unavailable");
        UiAutomation auto=test.getUiAutomation();AccessibilityServiceInfo info=auto.getServiceInfo();
        info.flags|=AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS;auto.setServiceInfo(info);LayoutChecks.auto=auto;
        Activity activity=test.startActivitySync(new Intent(c,SettingsActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        EditText editor=activity.findViewById(R.id.test_editor);InputMethodManager imm=(InputMethodManager)c.getSystemService(Context.INPUT_METHOD_SERVICE);
        long until=SystemClock.elapsedRealtime()+30000;final boolean[] ready={false};
        while(SystemClock.elapsedRealtime()<until){test.runOnMainSync(()->{editor.requestFocus();ready[0]=editor.hasWindowFocus()&&imm.isActive(editor)&&imm.isAcceptingText();imm.showSoftInput(editor,InputMethodManager.SHOW_IMPLICIT);});if(ready[0])break;SystemClock.sleep(200);}
        if(!ready[0])throw new AssertionError("OS editor not ready; no input sent");await(auto,true);
        AccessibilityNodeInfo button=LayoutChecks.await("收起输入键盘",true);
        if(!button.performAction(AccessibilityNodeInfo.ACTION_CLICK))throw new AssertionError("native hide button rejected click");
        await(auto,false);final String[] text={""};test.runOnMainSync(()->text[0]=editor.getText().toString());
        if(!text[0].isEmpty())throw new AssertionError("idle hide inserted or changed editor text: "+text[0]);
        LayoutChecks.screenshot(c,"native-hide-keyboard.png");
        test.runOnMainSync(()->imm.showSoftInput(editor,InputMethodManager.SHOW_IMPLICIT));await(auto,true);
        test.sendStringSync("ni");SystemClock.sleep(500);
        for(AccessibilityWindowInfo window:auto.getWindows())if(LayoutChecks.find(window.getRoot(),"收起输入键盘",true)!=null)throw new AssertionError("idle hide control must not masquerade as composition candidate expansion");
        test.sendKeyDownUpSync(KeyEvent.KEYCODE_SPACE);SystemClock.sleep(500);test.runOnMainSync(()->text[0]=editor.getText().toString());
        if(!text[0].equals("你"))throw new AssertionError("reopen failed ordinary composition or inserted extra space: "+text[0]);
        return "PASS real OS IME window shown for served editor\nPASS native rightmost hide button found by explicit accessibility description\nPASS actual hide click removes the OS IME window\nPASS idle hiding leaves editor text unchanged\nPASS native hidden-window screenshot saved\nPASS reopening supports ordinary composition and Space selection; idle hide absent in composing state\nCloud emulator only; physical phone NOT RUN\n";
    }
}
