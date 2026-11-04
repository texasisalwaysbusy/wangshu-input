package org.wangshu.ime;

import android.accessibilityservice.AccessibilityServiceInfo;
import android.app.*;
import android.content.*;
import android.os.*;
import android.view.accessibility.*;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import java.io.File;
import java.util.*;

/** Actual registered keyboard, owned editor fixtures, visible native suggestion clicks. No microphone. */
final class MathAssociationUiChecks {
    private static int count,nativeActions;private static StringBuilder log;private static Instrumentation test;
    private interface Condition{boolean yes();}
    private static void check(boolean ok,String message){if(!ok)throw new AssertionError(message);count++;log.append("PASS ").append(message).append('\n');}
    private static void await(Condition value,String label)throws Exception{if(LayoutChecks.auto!=null)StrictImeWindowQuery.beginObservation(LayoutChecks.auto);long end=SystemClock.elapsedRealtime()+20000;while(SystemClock.elapsedRealtime()<end){if(value.yes())return;SystemClock.sleep(100);}throw new AssertionError("native math UI wait: "+label+"; "+lastQuery);}
    private static String text(EditText editor){String[] out={""};test.runOnMainSync(()->out[0]=editor.getText().toString());return out[0];}
    private static String lastQuery="no query yet";
    private static AccessibilityNodeInfo node(String description)throws Exception{
        StrictImeWindowQuery.Result result=StrictImeWindowQuery.find(LayoutChecks.auto,AccessibilityWindowInfo.TYPE_INPUT_METHOD,"org.wangshu.ime",description,true);
        lastQuery=result.toString();return result.node;
    }
    private static AccessibilityNodeInfo nodeText(String text)throws Exception{
        StrictImeWindowQuery.Result result=StrictImeWindowQuery.find(LayoutChecks.auto,AccessibilityWindowInfo.TYPE_INPUT_METHOD,"org.wangshu.ime",text,false);
        lastQuery=result.toString();return result.node;
    }
    private static void click(String description)throws Exception{
        AccessibilityNodeInfo[] found={null};await(()->{try{return (found[0]=node(description))!=null;}catch(Exception error){throw new IllegalStateException(error);}},description);
        performBoundClick(found[0],description,true);
    }
    private static void clickText(String text)throws Exception{
        AccessibilityNodeInfo[] found={null};await(()->{try{return (found[0]=nodeText(text))!=null;}catch(Exception error){throw new IllegalStateException(error);}},text);
        performBoundClick(found[0],text,false);
    }
    private static void performBoundClick(AccessibilityNodeInfo target,String label,boolean description){
        int expectedWindow=target.getWindowId();boolean fresh=target.refresh();CharSequence value=description?target.getContentDescription():target.getText();
        android.graphics.Rect bounds=new android.graphics.Rect();target.getBoundsInScreen(bounds);boolean action=(target.getActions()&AccessibilityNodeInfo.ACTION_CLICK)!=0;
        log.append("TARGET ").append(label).append(" match=").append(description?"description":"text").append(" expectedWindow=").append(expectedWindow).append(" actualWindow=").append(target.getWindowId()).append(" pkg=").append(target.getPackageName()).append(" class=").append(target.getClassName()).append(" refreshed=").append(fresh).append(" visible=").append(target.isVisibleToUser()).append(" enabled=").append(target.isEnabled()).append(" clickable=").append(target.isClickable()).append(" clickAction=").append(action).append(" bounds=").append(bounds.toShortString()).append(" query=").append(lastQuery).append('\n');
        if(!fresh||target.getWindowId()!=expectedWindow||target.getPackageName()==null||!"org.wangshu.ime".contentEquals(target.getPackageName())||value==null||!label.contentEquals(value)||!target.isVisibleToUser()||!target.isEnabled()||!target.isClickable()||!action)throw new AssertionError("Native target changed before action; no action sent: "+label);
        long start=SystemClock.elapsedRealtime();boolean ack;nativeActions++;try{ack=target.performAction(AccessibilityNodeInfo.ACTION_CLICK);}catch(RuntimeException|AssertionError uncertain){log.append("ACTION ").append(label).append(" ack=THREW elapsedMs=").append(SystemClock.elapsedRealtime()-start).append(" once; no retry\n");throw uncertain;}
        log.append("ACTION ").append(label).append(" ack=").append(ack).append(" elapsedMs=").append(SystemClock.elapsedRealtime()-start).append(" once; outcome checked separately\n");
        if(!ack)throw new AssertionError("Native target action not acknowledged; no retry: "+label);
    }
    private static void fixture(EditText editor,String value,int caret){test.runOnMainSync(()->{editor.setText(value);editor.setSelection(caret);});}
    private static void finishOwned(Activity activity)throws Exception{
        if(activity==null)return;java.util.concurrent.CountDownLatch done=new java.util.concurrent.CountDownLatch(1);Throwable[] error={null};
        boolean posted=new Handler(Looper.getMainLooper()).post(()->{try{activity.finish();}catch(Throwable failure){error[0]=failure;}finally{done.countDown();}});
        if(!posted||!done.await(5,java.util.concurrent.TimeUnit.SECONDS))throw new AssertionError("Owned math Activity finish request not acknowledged; cleanup continues");
        if(error[0]!=null)throw new AssertionError("Owned math Activity finish failed",error[0]);
    }
    static String run(Instrumentation instrumentation)throws Exception{
        test=instrumentation;count=0;nativeActions=0;log=new StringBuilder();Context context=test.getTargetContext();LayoutChecks.auto=test.getUiAutomation();AccessibilityServiceInfo service=LayoutChecks.auto.getServiceInfo();service.flags|=AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS;LayoutChecks.auto.setServiceInfo(service);
        SharedPreferences prefs=AppPreferences.open(context,"associations");File evidence=new File(context.getFilesDir(),"test-evidence/math-ui-"+UUID.randomUUID());if(!evidence.mkdirs())throw new AssertionError("Cannot create math preference checkpoint directory");
        TestBooleanPreferenceCheckpoint checkpoint=new TestBooleanPreferenceCheckpoint(prefs,new File(evidence,"preferences-before.tsv"),"associations","math",null);log.append("EVIDENCE ").append(evidence.getName()).append("/preferences-before.tsv\n");Activity activity=null;Throwable primary=null;
        try{
            check(prefs.edit().putBoolean("math",true).commit(),"temporary test math setting saved without touching other preferences");
            activity=test.startActivitySync(new Intent(context,SettingsActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));EditText editor=activity.findViewById(R.id.test_editor);Activity owner=activity;
            MathImePrecondition.ensure(test,owner,editor,LayoutChecks.auto,log,()->nativeActions);
            check(true,"actual registered Wangshu keyboard visible before fixture events");
            // Use normal keyboard routing while the visible numeric panel is selected.
            clickText("123");await(()->{try{return node("数字面板换行或编辑器动作")!=null;}catch(Exception error){throw new IllegalStateException(error);}},"numeric panel visible after the sole acknowledged action");fixture(editor,"",0);test.sendStringSync("1+2=");await(()->text(editor).equals("1+2="),"OS numeric input");
            await(()->{try{return node("数学联想 精确结果 3")!=null;}catch(Exception e){throw new IllegalStateException(e);}},"exact arithmetic candidate");check(text(editor).equals("1+2="),"numeric candidate appears without automatic insertion");LayoutChecks.screenshot(context,evidence.getName()+"/native-math-association-numeric.png");click("数学联想 精确结果 3");await(()->text(editor).equals("1+2=3"),"clicked exact result");check(true,"visible numeric association inserts once via the real InputConnection");
            String assignment="a=j\nb=1\nc=h\na²+b²−c=";fixture(editor,assignment,assignment.length());await(()->{try{return node("数学联想 代入 j²+1²−h")!=null;}catch(Exception e){throw new IllegalStateException(e);}},"symbol substitution candidate");check(text(editor).equals(assignment),"context substitution is labelled substitution and leaves text intact until chosen");LayoutChecks.screenshot(context,evidence.getName()+"/native-math-association-substitution.png");click("数学联想 代入 j²+1²−h");await(()->text(editor).equals(assignment+"j²+1²−h"),"selected substitution");check(true,"symbolic substitution preserves undefined variables and inserts only on user choice");
            String prefix="1/3+1/6=",suffix="后文𝕏👩🏽‍💻";fixture(editor,prefix+suffix,prefix.length()+suffix.length());fixture(editor,prefix+suffix,prefix.length());await(()->{try{return node("数学联想 精确结果 1/2")!=null;}catch(Exception e){throw new IllegalStateException(e);}},"cursor moved before suffix");check(text(editor).equals(prefix+suffix),"moving the caret offers context math without changing either side");click("数学联想 精确结果 1/2");await(()->text(editor).equals(prefix+"1/2"+suffix),"fraction before suffix");check(true,"cursor-position association preserves mathematical/emoji suffix exactly");
            check(prefs.edit().putBoolean("math",false).commit(),"test temporarily disables the actual math provider setting");fixture(editor,"77+11=",0);fixture(editor,"77+11=","77+11=".length());SystemClock.sleep(1400);check(node("数学联想 精确结果 88")==null&&text(editor).equals("77+11="),"disabled provider produces no candidate or text change");
            return "PASS "+count+" actual registered-IME native math UI checks\n"+log+"Generated owned editor only. Numeric OS key routing, cursor move and visible suggestion selection; no real phone, user-reference pixel match, universal language prediction, CAS, voice or Windows acceptance.\n";
        }catch(Throwable failure){primary=failure;TestCleanupSteps.run(checkpoint::restore).attachOrThrow(failure);log.append("FAILURE_DIAGNOSTICS_AFTER_SCALAR_RESTORE; not the original fixture preference state\n");try{log.append(ImeSelectorDiagnostics.snapshot(LayoutChecks.auto));}catch(Exception|AssertionError metadataFailure){if(metadataFailure!=failure)failure.addSuppressed(metadataFailure);log.append("IME_SELECTOR_DIAGNOSTIC unavailable=").append(metadataFailure.getClass().getSimpleName()).append('\n');}try{LayoutChecks.screenshot(context,evidence.getName()+"/native-math-association-failure.png");}catch(Exception|AssertionError unavailable){if(unavailable!=failure)failure.addSuppressed(unavailable);log.append("Failure screenshot unavailable: ").append(unavailable.getClass().getSimpleName()).append('\n');}AssertionError report=new AssertionError("Math UI partial="+count+"\n"+log,failure);primary=report;throw report;}
        finally{Activity owned=activity;TestCleanupSteps.Outcome cleanup=TestCleanupSteps.run(checkpoint::restore,()->finishOwned(owned),checkpoint::restore,()->{if(!checkpoint.matches())throw new AssertionError("Math scalar differs after cleanup");},checkpoint::verifyUnrelated,checkpoint::recordRestored);cleanup.attachOrThrow(primary);}
    }
}
