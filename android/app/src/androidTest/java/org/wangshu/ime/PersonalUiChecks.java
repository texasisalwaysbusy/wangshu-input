package org.wangshu.ime;

import android.app.*;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.content.*;
import android.os.*;
import android.view.*;
import android.view.accessibility.*;
import android.view.inputmethod.*;
import android.widget.*;
import org.json.*;
import java.util.*;

/** Real management UI callbacks plus actual OS IME commits; no direct editor commits. */
final class PersonalUiChecks {
    private static int count;private static StringBuilder log;
    private static void check(boolean value,String label){if(!value)throw new AssertionError(label);count++;log.append("PASS ").append(label).append('\n');}
    private static View find(View root,String label,boolean description){if(label.equals(description?root.getContentDescription():root instanceof TextView?((TextView)root).getText().toString():null))return root;if(root instanceof ViewGroup)for(int i=0;i<((ViewGroup)root).getChildCount();i++){View v=find(((ViewGroup)root).getChildAt(i),label,description);if(v!=null)return v;}return null;}
    private static void form(Instrumentation test,Activity activity,String button,String keyLabel,String key,String text)throws Exception {
        test.runOnMainSync(()->{View add=find(activity.getWindow().getDecorView(),button,false);if(add==null)throw new AssertionError("Management add button unavailable");add.performClick();});
        SystemClock.sleep(300);
        CaseModeChecks.click(keyLabel,true); // Identify actual focused dialog through the OS accessibility tree.
        // Fill the focused native dialog via actual OS key events for Latin keys; Unicode value is set on its native widget below.
        test.sendStringSync(key);SystemClock.sleep(200);
        AccessibilityNodeInfo value=LayoutChecks.await("个人条目内容",true);Bundle arguments=new Bundle();arguments.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,text);check(value.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT,arguments),"native management content widget accepts authored text");
        CaseModeChecks.click("保存",false);SystemClock.sleep(400);HideChecks.await(test.getUiAutomation(),false);check(true,"saving authored entry dismisses the input keyboard");
    }
    static void settledScreenshot(Instrumentation test,Context context,String name)throws Exception {
        test.waitForIdleSync();SystemClock.sleep(1600);android.graphics.Bitmap previous=null;int equal=0;long until=SystemClock.elapsedRealtime()+20000;
        try{while(SystemClock.elapsedRealtime()<until){android.graphics.Bitmap current=test.getUiAutomation().takeScreenshot();if(current==null)throw new AssertionError("No compositor frame");boolean same=previous!=null&&current.sameAs(previous);if(previous!=null)previous.recycle();previous=current;equal=same?equal+1:0;if(equal>=2){java.io.File directory=new java.io.File(context.getFilesDir(),"test-evidence");directory.mkdirs();try(java.io.OutputStream out=new java.io.FileOutputStream(new java.io.File(directory,name))){current.compress(android.graphics.Bitmap.CompressFormat.PNG,100,out);}return;}SystemClock.sleep(800);}throw new AssertionError("Native screenshot did not settle; no unstable image accepted");}finally{if(previous!=null)previous.recycle();}
    }
    static String run(Instrumentation test)throws Exception {
        count=0;log=new StringBuilder();CaseModeChecks.actions=new StringBuilder();Context c=test.getTargetContext();DataFiles.initialize(c);UiAutomation auto=test.getUiAutomation();AccessibilityServiceInfo config=auto.getServiceInfo();config.flags|=AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS;auto.setServiceInfo(config);LayoutChecks.auto=auto;
        String token=UUID.randomUUID().toString().replaceAll("[^a-f]","");String key="wstest"+token,word="测试词条"+token,shortcut="phrase"+token,phrase="本地测试常用语"+token+"\nα + 👩‍🔬";PersonalStore store=PersonalStore.get(c);
        try{
            Activity manager=test.startActivitySync(new Intent(c,PersonalActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            form(test,manager,"添加词条","词条拼音",key,word);check(Arrays.asList(store.words(key)).contains(word),"production manager saves custom word with validated pinyin");
            settledScreenshot(test,c,"native-personal-word-manager.png");
            CaseModeChecks.click("常用语",false);form(test,manager,"添加常用语","常用语简称",shortcut,phrase);check(Arrays.asList(store.words(shortcut)).contains(phrase),"production manager saves multiline phrase and shorthand");
            check(new PersonalStore(new java.io.File(c.getFilesDir(),"personal-v1.json")).words(shortcut)[0].equals(phrase),"fresh disk reopen retains manager-authored phrase");settledScreenshot(test,c,"native-personal-phrase-manager.png");
            Activity activity=test.startActivitySync(new Intent(c,SettingsActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));EditText editor=activity.findViewById(R.id.test_editor);InputMethodManager imm=(InputMethodManager)c.getSystemService(Context.INPUT_METHOD_SERVICE);boolean ready=false;long until=SystemClock.elapsedRealtime()+30000;
            while(SystemClock.elapsedRealtime()<until){final boolean[] active={false};test.runOnMainSync(()->{editor.requestFocus();active[0]=editor.hasWindowFocus()&&imm.isActive(editor)&&imm.isAcceptingText();imm.showSoftInput(editor,InputMethodManager.SHOW_IMPLICIT);});if(active[0]){ready=true;break;}SystemClock.sleep(250);}check(ready,"OS serves actual Settings editor before input");HideChecks.await(auto,true);
            boolean chinese=false;for(AccessibilityWindowInfo window:auto.getWindows())if(LayoutChecks.find(window.getRoot(),"中",false)!=null)chinese=true;if(!chinese)CaseModeChecks.click("英",false);
            test.sendStringSync(key);SystemClock.sleep(500);LayoutChecks.await("候选 1 "+word,true);test.sendKeyDownUpSync(KeyEvent.KEYCODE_SPACE);CaseModeChecks.awaitText(test,editor,word);check(true,"custom dictionary word commits through real OS IME composition");
            test.runOnMainSync(()->editor.setText(""));SystemClock.sleep(300);test.sendStringSync(shortcut);test.sendKeyDownUpSync(KeyEvent.KEYCODE_SPACE);CaseModeChecks.awaitText(test,editor,phrase);check(true,"phrase shorthand commits exact multiline Unicode through OS");
            test.runOnMainSync(()->editor.setText(""));SystemClock.sleep(300);CaseModeChecks.click("剪贴板与常用语",true);CaseModeChecks.click("常用语标签",true);LayoutChecks.await("插入常用语 1",true);JSONArray phrases=store.snapshot().getJSONArray("phrases");int target=-1;for(int i=0;i<phrases.length();i++)if(phrase.equals(phrases.getJSONObject(i).getString("text")))target=i;if(target<0)throw new AssertionError("test-owned phrase vanished");
            LayoutChecks.await("插入常用语 "+(target+1),true).performAction(AccessibilityNodeInfo.ACTION_CLICK);CaseModeChecks.awaitText(test,editor,phrase);check(true,"keyboard phrase panel inserts selected phrase exactly once");settledScreenshot(test,c,"native-personal-phrase-keyboard.png");CaseModeChecks.click("返回",false);LayoutChecks.await("q",false);check(true,"phrase panel returns to native alphabet keyboard");
            if(!chinese)CaseModeChecks.click("中",false);
            return "PASS "+count+" native authored-data and real OS IME integration checks\n"+log+CaseModeChecks.actions+"Real phone and Windows integration NOT_RUN; no clipboard monitoring\n";
        }finally{
            // Remove ONLY exact synthetic records created by this run, preserving every other entry.
            for(String type:new String[]{"words","phrases"}){JSONArray list=store.snapshot().getJSONArray(type);for(int i=list.length()-1;i>=0;i--){String value=list.getJSONObject(i).getString("text");if(value.equals(word)||value.equals(phrase))store.remove(type,i);}}
        }
    }
}
