package org.wangshu.ime;

import android.app.*;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.content.*;
import android.os.*;
import android.view.accessibility.*;
import android.view.inputmethod.*;
import android.widget.EditText;

/** Soft-key state cycles and actual OS InputConnection output, not just state-unit tests. */
final class CaseModeChecks {
    static StringBuilder actions=new StringBuilder();
    static void click(String name,boolean description)throws Exception{
        // Shared by suites which never call CaseModeChecks.run(). Prepare the
        // evidence sink before sending an action; logging must not fail after it.
        if(actions==null)actions=new StringBuilder();StringBuilder receipt=actions;
        AccessibilityNodeInfo node=LayoutChecks.await(name,description);long until=SystemClock.elapsedRealtime()+10000;boolean fresh=node.refresh();
        while(!fresh&&SystemClock.elapsedRealtime()<until){SystemClock.sleep(250);node=LayoutChecks.await(name,description);fresh=node.refresh();}
        CharSequence value=description?node.getContentDescription():node.getText();
        if(!fresh||value==null||!name.contentEquals(value)||!node.isVisibleToUser()||!node.isEnabled()){
            receipt.append("NOT_SENT ").append(name).append(" stale/mismatched/hidden/disabled target; repeated=false\n");throw new AssertionError("Actual native click target not current; no action sent: "+name);
        }
        boolean acknowledged;
        try{acknowledged=node.performAction(AccessibilityNodeInfo.ACTION_CLICK);}
        catch(RuntimeException|AssertionError uncertain){receipt.append("ACTION ").append(name).append(" acknowledged=THREW repeated=false; not retried\n");throw uncertain;}
        receipt.append("ACTION ").append(name).append(" acknowledged=").append(acknowledged).append(" repeated=false; outcome checked separately\n");
        if(!acknowledged)throw new AssertionError("Actual native click was not acknowledged; no retry: "+name);
        SystemClock.sleep(350);
    }
    static void awaitText(Instrumentation test,EditText editor,String expected)throws Exception{long until=SystemClock.elapsedRealtime()+20000;while(SystemClock.elapsedRealtime()<until){if(CandidatePanelChecks.value(test,editor).equals(expected))return;SystemClock.sleep(250);}throw new AssertionError("Actual OS editor output mismatch; expected="+expected+" actual="+CandidatePanelChecks.value(test,editor));}
    static String run(Instrumentation test)throws Exception{
        actions=new StringBuilder();Context c=test.getTargetContext();DataFiles.initialize(c);UiAutomation auto=test.getUiAutomation();AccessibilityServiceInfo config=auto.getServiceInfo();config.flags|=AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS;auto.setServiceInfo(config);LayoutChecks.auto=auto;
        Activity activity=test.startActivitySync(new Intent(c,SettingsActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));EditText editor=activity.findViewById(R.id.test_editor);InputMethodManager imm=(InputMethodManager)c.getSystemService(Context.INPUT_METHOD_SERVICE);final boolean[] ready={false};long until=SystemClock.elapsedRealtime()+30000;
        while(SystemClock.elapsedRealtime()<until){test.runOnMainSync(()->{editor.requestFocus();ready[0]=editor.hasWindowFocus()&&imm.isActive(editor)&&imm.isAcceptingText();imm.showSoftInput(editor,InputMethodManager.SHOW_IMPLICIT);});if(ready[0])break;SystemClock.sleep(250);}if(!ready[0])throw new AssertionError("OS editor not ready; no soft key sent");HideChecks.await(auto,true);
        boolean wasChinese=false;for(AccessibilityWindowInfo w:auto.getWindows())if(LayoutChecks.find(w.getRoot(),"中",false)!=null)wasChinese=true;
        if(wasChinese)click("中",false);LayoutChecks.await("英",false);
        try{
            LayoutChecks.await("小写",true);click("a",false);awaitText(test,editor,"a");
            click("小写",true);LayoutChecks.await("下一字母大写",true);click(",",false);LayoutChecks.await("下一字母大写",true);click("B",false);LayoutChecks.await("小写",true);click("c",false);
            awaitText(test,editor,"a,Bc");
            click("小写",true);click("下一字母大写",true);LayoutChecks.await("大写锁定",true);click("D",false);click("E",false);LayoutChecks.await("大写锁定",true);
            awaitText(test,editor,"a,BcDE");LayoutChecks.screenshot(c,"native-case-caps-lock.png");
            click("大写锁定",true);LayoutChecks.await("小写",true);click("f",false);awaitText(test,editor,"a,BcDEf");
            return "PASS native lowercase state commits a lowercase letter\nPASS first click exposes next-letter uppercase state\nPASS punctuation does not consume one-shot case\nPASS one uppercase letter returns to lowercase\nPASS second click exposes caps lock\nPASS caps lock survives two actual soft letters\nPASS third click returns to lowercase\nPASS complete OS output a,BcDEf exact with no duplicate commits\nCloud native integration only; user reference pixels and real phone NOT_RUN\n"+actions;
        }finally{if(wasChinese)click("英",false);}
    }
}
