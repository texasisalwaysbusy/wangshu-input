package org.wangshu.ime;

import android.app.*;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.content.*;
import android.os.*;
import android.view.KeyEvent;
import android.view.accessibility.*;
import android.view.inputmethod.*;
import android.widget.EditText;

/** Actual OS-bound expansion, separate from the idle toolbar's hide action. */
final class CandidatePanelChecks {
    static String value(Instrumentation test,EditText editor){final String[] out={""};test.runOnMainSync(()->out[0]=editor.getText().toString());return out[0];}
    private static String targetPackage;private static ImeTestDiagnostics diagnostics;
    static void click(String label)throws Exception{
        long deadline=SystemClock.elapsedRealtime()+30000;AccessibilityNodeInfo node=null;
        StrictImeWindowQuery.beginObservation(LayoutChecks.auto);
        while(SystemClock.elapsedRealtime()<deadline){node=StrictImeWindowQuery.find(LayoutChecks.auto,AccessibilityWindowInfo.TYPE_INPUT_METHOD,targetPackage,label,true).node;if(node!=null)break;SystemClock.sleep(200);}
        if(node==null)throw new AssertionError("exact target IME node unavailable; no action sent: "+label);
        diagnostics.beforeAction("candidate-click "+label);int window=node.getWindowId();
        if(!node.refresh()||node.getWindowId()!=window||!targetPackage.contentEquals(node.getPackageName()==null?"":node.getPackageName())||!label.contentEquals(node.getContentDescription()==null?"":node.getContentDescription())||!node.isVisibleToUser()||!node.isEnabled()||!node.isClickable())throw new AssertionError("target changed; no action sent");
        diagnostics.markInputAttempt();if(!node.performAction(AccessibilityNodeInfo.ACTION_CLICK))throw new AssertionError("native action rejected: "+label);
        SystemClock.sleep(400);
    }
    static String run(Instrumentation test,ImeTestDiagnostics diagnostic)throws Exception{
        diagnostics=diagnostic;
        Context c=test.getTargetContext();targetPackage=c.getPackageName();
        String selected=android.provider.Settings.Secure.getString(c.getContentResolver(),android.provider.Settings.Secure.DEFAULT_INPUT_METHOD);
        if(!new ComponentName(c,WangshuIme.class).equals(ComponentName.unflattenFromString(selected==null?"":selected)))throw new AssertionError("exact target IME is not selected; no input sent");
        if(DataFiles.initialize(c)<100000)throw new AssertionError("dictionary unavailable");
        UiAutomation auto=test.getUiAutomation();AccessibilityServiceInfo info=auto.getServiceInfo();info.flags|=AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS;auto.setServiceInfo(info);LayoutChecks.auto=auto;
        Activity activity=test.startActivitySync(new Intent(c,SettingsActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));EditText editor=activity.findViewById(R.id.test_editor);
        diagnostics.awaitReady(activity,editor);diagnostics.awaitImeLabel("收起输入键盘",true);
        diagnostics.sendString("shi");SystemClock.sleep(500);
        diagnostics.awaitImeLabel("shi",false);if(!value(test,editor).isEmpty())throw new AssertionError("unconfirmed local preedit must not enter host text");
        for(AccessibilityWindowInfo window:auto.getWindows())if(LayoutChecks.find(window.getRoot(),"收起输入键盘",true)!=null)throw new AssertionError("idle hide must not remain in composing toolbar");
        click("展开候选框");diagnostics.awaitImeLabel("收回候选框",true);diagnostics.requireReady("candidate-window-visible");
        String second=NativeBridge.lookup("shi",0)[2];diagnostics.awaitImeLabel("展开候选 2 "+second,true);diagnostics.screenshot("expanded-candidates");
        click("展开候选 2 "+second);diagnostics.awaitImeLabel("收起输入键盘",true);
        if(!value(test,editor).equals(second))throw new AssertionError("grid selection did not commit local composition exactly once");
        diagnostics.sendString("ni");SystemClock.sleep(400);click("展开候选框");click("收回候选框");diagnostics.requireReady("candidate-window-visible");diagnostics.awaitImeLabel("q",false);
        diagnostics.awaitImeLabel("ni",false);if(!value(test,editor).equals(second))throw new AssertionError("collapse wrote unconfirmed local preedit to host");
        diagnostics.screenshot("collapsed-candidates");diagnostics.sendKey(KeyEvent.KEYCODE_SPACE);SystemClock.sleep(500);
        if(!value(test,editor).equals(second+"你"))throw new AssertionError("normal keyboard did not resume composition");
        return "PASS native idle toolbar hide control is available\nPASS composing state removes idle hide and exposes candidate expansion\nPASS expansion retains actual OS IME window\nPASS expanded multirow native candidate identified and screenshot saved\nPASS grid selection commits exactly once and returns idle toolbar\nPASS collapse preserves raw composition without hiding the keyboard\nPASS collapse restores actual alphabet keys\nPASS normal Space selection resumes after collapse\nCloud OS accessibility integration only; reference-image visual parity and real phone NOT_RUN\n";
    }
}
