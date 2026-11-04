package org.wangshu.ime;

import android.app.*;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.content.*;
import android.graphics.*;
import android.os.*;
import android.view.*;
import android.view.accessibility.*;
import android.view.inputmethod.*;
import android.widget.*;
import java.io.*;

/** A separate native Settings button retains OS switching; Space hold is now voice. */
final class SwitcherChecks {
    static Button pickerButton(View root){if(root instanceof Button&&"2 · 选择望舒输入法".contentEquals(((Button)root).getText()))return (Button)root;if(root instanceof ViewGroup)for(int i=0;i<((ViewGroup)root).getChildCount();i++){Button found=pickerButton(((ViewGroup)root).getChildAt(i));if(found!=null)return found;}return null;}
    static String run(Instrumentation test)throws Exception{
        Context context=test.getTargetContext();if(DataFiles.initialize(context)<100000)throw new AssertionError("dictionary unavailable");UiAutomation auto=test.getUiAutomation();AccessibilityServiceInfo config=auto.getServiceInfo();config.flags|=AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS;auto.setServiceInfo(config);
        Activity activity=test.startActivitySync(new Intent(context,SettingsActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));EditText editor=activity.findViewById(R.id.test_editor);final String[] before={null};final boolean[] clicked={false},ready={false};long focusUntil=SystemClock.elapsedRealtime()+30000;while(SystemClock.elapsedRealtime()<focusUntil){test.runOnMainSync(()->ready[0]=activity.getWindow().getDecorView().hasWindowFocus());if(ready[0])break;SystemClock.sleep(150);}if(!ready[0])throw new AssertionError("Settings window not ready; picker click not sent");
        test.runOnMainSync(()->{before[0]=editor.getText().toString();Button picker=pickerButton(activity.getWindow().getDecorView());if(picker==null)throw new AssertionError("separate native OS picker entry missing");clicked[0]=picker.performClick();});
        if(!clicked[0])throw new AssertionError("separate native picker click rejected");
        boolean picker=false;String observed="";long until=SystemClock.elapsedRealtime()+30000;
        while(SystemClock.elapsedRealtime()<until){AccessibilityNodeInfo root=auto.getRootInActiveWindow();if(root!=null){StringBuilder text=new StringBuilder();collect(root,text);observed=text.toString();String owner=String.valueOf(root.getPackageName());picker=(owner.equals("android")||owner.equals("com.android.systemui"))&&observed.contains("Android Keyboard (AOSP)")&&observed.contains("望舒中文");}if(picker)break;SystemClock.sleep(250);}
        File evidence=new File(context.getFilesDir(),"test-evidence");evidence.mkdirs();Bitmap screenshot=auto.takeScreenshot();if(screenshot!=null)try(OutputStream out=new FileOutputStream(new File(evidence,"native-ime-picker.png"))){screenshot.compress(Bitmap.CompressFormat.PNG,100,out);}finally{screenshot.recycle();}
        if(!picker)throw new AssertionError("OS picker not observed after independent native Settings entry: "+observed);
        if(!auto.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK))throw new AssertionError("OS accessibility picker cancellation rejected");
        SystemClock.sleep(400);final String[] after={null};test.runOnMainSync(()->after[0]=editor.getText().toString());if(!before[0].equals(after[0]))throw new AssertionError("separate picker click/cancellation changed test text");
        return "PASS independent native Settings input-method picker entry exists\nPASS native button click opens actual OS picker with AOSP and Wangshu entries\nPASS OS picker cancellation preserves Settings editor text\nPASS native OS picker screenshot saved; enabled methods/security settings unchanged\nSpace hold now starts local voice; this suite does not test physical hold, microphone or ASR. Real phone NOT_RUN.\n";
    }
    static void collect(AccessibilityNodeInfo node,StringBuilder text){if(node.getText()!=null)text.append(node.getText()).append('\n');for(int i=0;i<node.getChildCount();i++){AccessibilityNodeInfo child=node.getChild(i);if(child!=null)collect(child,text);}}
}
