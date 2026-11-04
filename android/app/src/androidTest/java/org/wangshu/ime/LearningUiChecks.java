package org.wangshu.ime;

import android.accessibilityservice.AccessibilityServiceInfo;
import android.app.*;
import android.content.*;
import android.os.Build;
import android.os.SystemClock;
import android.view.accessibility.*;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import java.io.File;
import java.lang.reflect.Field;
import java.util.*;

/** Real selected OS keyboard/editor/candidate actions; only store and scalar mode fixtures are injected. */
final class LearningUiChecks {
    private static Instrumentation test;private static StringBuilder log;private static int count;
    private interface Condition {boolean yes()throws Exception;}
    private static void check(boolean ok,String message){if(!ok)throw new AssertionError(message);count++;log.append("PASS ").append(message).append('\n');}
    private static void await(Condition value,String reason)throws Exception{if(LayoutChecks.auto!=null)StrictImeWindowQuery.beginObservation(LayoutChecks.auto);long deadline=SystemClock.elapsedRealtime()+20000;while(SystemClock.elapsedRealtime()<deadline){if(value.yes())return;SystemClock.sleep(150);}throw new AssertionError("Learning UI wait: "+reason+"; "+lastQuery+"; no guessed action");}
    private static String text(EditText editor){String[] value={""};test.runOnMainSync(()->value[0]=editor.getText().toString());return value[0];}
    private static String lastQuery="no query yet";
    private static AccessibilityNodeInfo node(String description)throws Exception{
        StrictImeWindowQuery.Result result=StrictImeWindowQuery.find(LayoutChecks.auto,AccessibilityWindowInfo.TYPE_INPUT_METHOD,"org.wangshu.ime",description,true);
        lastQuery=result.toString();return result.node;
    }
    private static AccessibilityNodeInfo awaitNode(String description)throws Exception{AccessibilityNodeInfo[] found={null};await(()->(found[0]=node(description))!=null,description);return found[0];}
    private static void click(String description)throws Exception{
        AccessibilityNodeInfo[] found={null};await(()->{try{return (found[0]=node(description))!=null;}catch(Exception error){throw new IllegalStateException(error);}},description);
        AccessibilityNodeInfo target=found[0];int window=target.getWindowId();
        if(!target.refresh()||target.getWindowId()!=window||target.getPackageName()==null||!"org.wangshu.ime".contentEquals(target.getPackageName())||target.getContentDescription()==null||!description.contentEquals(target.getContentDescription())||!target.isVisibleToUser()||!target.isEnabled()||!target.isClickable())throw new AssertionError("Native target changed before action; no action sent: "+description);
        boolean ack;try{ack=target.performAction(AccessibilityNodeInfo.ACTION_CLICK);}catch(RuntimeException|AssertionError uncertain){log.append("ACTION ").append(description).append(" ack=THREW once; no retry\n");throw uncertain;}
        log.append("ACTION ").append(description).append(" ack=").append(ack).append(" once; outcome checked separately\n");
        if(!ack)throw new AssertionError("Native target action not acknowledged; no retry: "+description);
    }
    private static void pref(SharedPreferences values,boolean enabled){if(!values.edit().putBoolean(LearningStore.ENABLED,enabled).commit()||!Objects.equals(values.getAll().get(LearningStore.ENABLED),enabled))throw new AssertionError("Fixture scalar setting not saved");}
    private static LearningStore swap(Field field,LearningStore next){LearningStore[] old={null};test.runOnMainSync(()->{synchronized(LearningStore.class){try{old[0]=(LearningStore)field.get(null);field.set(null,next);}catch(IllegalAccessException failure){throw new IllegalStateException(failure);}}});return old[0];}
    private static void finishOwned(Activity activity)throws Exception{
        if(activity==null)return;java.util.concurrent.CountDownLatch done=new java.util.concurrent.CountDownLatch(1);Throwable[] error={null};
        boolean posted=new android.os.Handler(android.os.Looper.getMainLooper()).post(()->{try{activity.finish();}catch(Throwable failure){error[0]=failure;}finally{done.countDown();}});
        if(!posted||!done.await(5,java.util.concurrent.TimeUnit.SECONDS))throw new AssertionError("Owned learning Activity finish request not acknowledged; cleanup continues");
        if(error[0]!=null)throw new AssertionError("Owned learning Activity finish failed",error[0]);
    }
    private static void typeAndChoose(EditText editor)throws Exception{
        test.runOnMainSync(()->{editor.setText("");editor.setSelection(0);editor.requestFocus();});
        await(()->node("中英文切换")!=null,"idle keyboard before generated input");
        test.sendStringSync("ni");awaitNode("候选 1 你");check(text(editor).isEmpty(),"unconfirmed local Pinyin leaves host text unchanged");
        // A known dictionary fixture only: never copy an arbitrary user candidate into evidence.
        awaitNode("候选 1 你");click("候选 1 你");await(()->text(editor).equals("你"),"actual selected candidate visible exactly once");test.runOnMainSync(()->{});
    }
    static String run(Instrumentation instrumentation)throws Exception{
        test=instrumentation;log=new StringBuilder();count=0;Context context=test.getTargetContext();
        SharedPreferences preferences=AppPreferences.open(context,LearningStore.PREFERENCES);boolean pausedBefore=LearningStore.paused();
        File evidence=new File(context.getFilesDir(),"test-evidence/learning-ui-"+UUID.randomUUID());if(!evidence.mkdirs())throw new AssertionError("Cannot create learning preference checkpoint directory");
        TestBooleanPreferenceCheckpoint checkpoint=new TestBooleanPreferenceCheckpoint(preferences,new File(evidence,"preferences-before.tsv"),LearningStore.PREFERENCES,LearningStore.ENABLED,pausedBefore);log.append("EVIDENCE ").append(evidence.getName()).append("/preferences-before.tsv\n");
        Field field=LearningStore.class.getDeclaredField("instance");field.setAccessible(true);
        File directory=new File(context.getCacheDir(),"test-learning-ui-"+UUID.randomUUID());if(!directory.mkdirs())throw new AssertionError("Cannot create UUID test-owned cache store");File modelFile=new File(directory,"candidate-learning-v1.tsv");LearningStore isolated=new LearningStore(modelFile);
        final LearningStore original;synchronized(LearningStore.class){original=(LearningStore)field.get(null);}boolean swapAttempted=false;Activity activity=null;Throwable primary=null;
        try{
            swapAttempted=true;swap(field,isolated);LearningStore.pause(false);pref(preferences,false);
            check(isolated.size()==0,"fresh UUID store injected; original singleton retained, default user model never cleared");
            check(DataFiles.initialize(context)>100000,"existing local dictionary initialized for real OS keyboard");
            LayoutChecks.auto=test.getUiAutomation();AccessibilityServiceInfo service=LayoutChecks.auto.getServiceInfo();service.flags|=AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS;LayoutChecks.auto.setServiceInfo(service);
            activity=test.startActivitySync(new Intent(context,SettingsActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));EditText editor=activity.findViewById(R.id.test_editor);Activity owner=activity;
            test.runOnMainSync(()->editor.requestFocus());await(()->{boolean[] ready={false};test.runOnMainSync(()->ready[0]=editor.hasWindowFocus()&&editor.hasFocus());return ready[0];},"owned editor has actual window focus");
            test.runOnMainSync(()->{editor.requestRectangleOnScreen(new android.graphics.Rect(0,0,editor.getWidth(),editor.getHeight()),false);InputMethodManager manager=(InputMethodManager)owner.getSystemService(Context.INPUT_METHOD_SERVICE);manager.restartInput(editor);boolean ack=manager.showSoftInput(editor,InputMethodManager.SHOW_IMPLICIT);log.append("PRECONDITION focus=").append(editor.hasWindowFocus()).append(" active=").append(manager.isActive(editor)).append(" accepting=").append(manager.isAcceptingText()).append(" show_ack=").append(ack).append('\n');});
            AccessibilityNodeInfo language=awaitNode("中英文切换");String languageText=String.valueOf(language.getText());if(languageText.equals("英"))click("中英文切换");else if(!languageText.equals("中"))throw new AssertionError("Unexpected language state; no key sent");
            await(()->"中".equals(String.valueOf(awaitNode("中英文切换").getText())),"Chinese full Pinyin mode");check(true,"actual TYPE_INPUT_METHOD owned Wangshu keyboard visible before generated keys");
            typeAndChoose(editor);check(isolated.size()==0&&isolated.revision()==0,"disabled automatic learning still commits selected candidate without recording");
            pref(preferences,true);typeAndChoose(editor);await(()->isolated.size()==1&&isolated.revision()>0,"verified native candidate recorded");check(Arrays.equals(isolated.candidates("ni",System.currentTimeMillis()),new String[]{"你"}),"actual visible candidate selection records only the generated Pinyin/word identity");
            LearningStore reloaded=new LearningStore(modelFile);check(Arrays.equals(reloaded.candidates("ni",System.currentTimeMillis()),new String[]{"你"}),"fresh store reads actual test-owned AtomicFile after UI selection");LayoutChecks.screenshot(context,evidence.getName()+"/native-learning-ui-confirmed.png");
            long revision=isolated.revision();LearningStore.pause(true);typeAndChoose(editor);check(isolated.revision()==revision&&isolated.size()==1,"temporary no-learning fixture prevents recording despite real visible candidate commit");
            LearningStore.pause(false);typeAndChoose(editor);await(()->isolated.revision()>revision,"recording resumes after temporary pause");check(isolated.size()==1,"resumed real selection updates existing identity without duplicate stored word");LayoutChecks.screenshot(context,evidence.getName()+"/native-learning-ui-resumed.png");
            return "PASS "+count+" real OS-selected IME learning UI checks\n"+log+"Scope: generated Settings editor, exact native candidate actions, injected UUID test-owned LearningStore using actual AtomicFile; not default user path. Enabled/paused are scalar test setup, not settings-control UI acceptance. Pin/forget menu, password/noLearn runtime, real phone and microphone NOT_RUN.\n";
        }catch(Throwable failure){primary=failure;TestCleanupSteps.run(checkpoint::restore,()->LearningStore.pause(pausedBefore)).attachOrThrow(failure);log.append("FAILURE_DIAGNOSTICS_AFTER_SCALAR_RESTORE; not the original fixture preference state\n");try{if(LayoutChecks.auto!=null)log.append(ImeSelectorDiagnostics.snapshot(LayoutChecks.auto));}catch(Exception|AssertionError unavailable){if(unavailable!=failure)failure.addSuppressed(unavailable);log.append("Metadata unavailable=").append(unavailable.getClass().getSimpleName()).append('\n');}try{if(LayoutChecks.auto!=null)LayoutChecks.screenshot(context,evidence.getName()+"/native-learning-ui-failure.png");}catch(Exception|AssertionError unavailable){if(unavailable!=failure)failure.addSuppressed(unavailable);log.append("Screenshot unavailable=").append(unavailable.getClass().getSimpleName()).append('\n');}AssertionError report=new AssertionError("Learning UI partial="+count+"\n"+log,failure);primary=report;throw report;}
        finally{
            Activity owned=activity;boolean restoreSingleton=swapAttempted;
            TestCleanupSteps.Outcome cleanup=TestCleanupSteps.run(checkpoint::restore,()->LearningStore.pause(pausedBefore),()->finishOwned(owned),checkpoint::restore,()->LearningStore.pause(pausedBefore),()->{if(restoreSingleton)swap(field,original);},()->{if(!checkpoint.matches())throw new AssertionError("Learning scalar differs after cleanup");},checkpoint::verifyUnrelated,checkpoint::recordRestored);
            cleanup.attachOrThrow(primary);
        }
    }
}
