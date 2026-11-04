package org.wangshu.ime;

import android.app.*;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.content.*;
import android.os.*;
import android.text.InputType;
import android.view.accessibility.*;
import android.view.inputmethod.*;
import android.widget.EditText;
import org.json.*;
import java.util.*;

/** Synthetic actual OS clipboard events, explicit app opt-in and real keyboard panel. */
final class ClipboardUiChecks {
    static int count;static StringBuilder log;
    private static void check(boolean value,String label){if(!value)throw new AssertionError(label);count++;log.append("PASS ").append(label).append('\n');System.out.println("CLIPBOARD_UI_PASS "+label);}
    private static AccessibilityNodeInfo scrollable(AccessibilityNodeInfo node){if(node==null||!node.refresh()||!node.isVisibleToUser())return null;if(node.isScrollable())return node;for(int i=0;i<node.getChildCount();i++){AccessibilityNodeInfo found=scrollable(node.getChild(i));if(found!=null)return found;}return null;}
    private static void click(String label,boolean description)throws Exception {UiAutomation auto=LayoutChecks.auto;for(int attempt=0;attempt<16;attempt++){for(AccessibilityWindowInfo window:auto.getWindows())if(LayoutChecks.find(window.getRoot(),label,description)!=null){CaseModeChecks.click(label,description);return;}if(attempt>=4){for(AccessibilityWindowInfo window:auto.getWindows()){AccessibilityNodeInfo scroll=scrollable(window.getRoot());if(scroll!=null){boolean accepted=scroll.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD);CaseModeChecks.actions.append("SCROLL to actual native target ").append(label).append(" acknowledged=").append(accepted).append('\n');break;}}}SystemClock.sleep(300);}throw new AssertionError("Native target unavailable after bounded scroll: "+label);}
    private static void setClip(Instrumentation test,ClipboardManager manager,ClipData clip){test.runOnMainSync(()->manager.setPrimaryClip(clip));SystemClock.sleep(500);}
    private static JSONObject find(ClipboardStore store,String text)throws Exception {JSONArray rows=store.list(System.currentTimeMillis(),7);for(int i=0;i<rows.length();i++)if(text.equals(rows.getJSONObject(i).getString("text")))return rows.getJSONObject(i);return null;}
    private static void recorded(ClipboardStore store,String text)throws Exception {long until=SystemClock.elapsedRealtime()+15000;while(SystemClock.elapsedRealtime()<until){if(find(store,text)!=null)return;SystemClock.sleep(200);}throw new AssertionError("Synthetic OS clipboard event not persisted");}
    private static void serve(Instrumentation test,Activity activity,EditText editor,InputMethodManager imm)throws Exception {long until=SystemClock.elapsedRealtime()+30000;while(SystemClock.elapsedRealtime()<until){boolean[] ok={false};test.runOnMainSync(()->{editor.requestFocus();ok[0]=editor.hasWindowFocus()&&imm.isActive(editor)&&imm.isAcceptingText();imm.showSoftInput(editor,InputMethodManager.SHOW_IMPLICIT);});if(ok[0]){HideChecks.await(test.getUiAutomation(),true);SystemClock.sleep(600);return;}SystemClock.sleep(200);}throw new AssertionError("OS editor not ready; no clipboard operation dispatched");}
    private static void readySettings(Instrumentation test,Activity settings)throws Exception {
        long until=SystemClock.elapsedRealtime()+60000;
        while(SystemClock.elapsedRealtime()<until){
            boolean[] focus={false};test.runOnMainSync(()->focus[0]=settings.hasWindowFocus());
            for(AccessibilityWindowInfo window:LayoutChecks.auto.getWindows()){
                AccessibilityNodeInfo root=window.getRoot();
                if(LayoutChecks.find(root,"System UI isn't responding",false)!=null||LayoutChecks.find(root,"Quickstep isn't responding",false)!=null)throw new AssertionError("System ANR blocks clipboard settings; no clipboard or settings operation dispatched");
                if(focus[0]&&LayoutChecks.find(root,"记录剪贴板历史",true)!=null)return;
            }
            SystemClock.sleep(300);
        }
        throw new AssertionError("Clipboard settings window/actual toggle not ready within60s; no clipboard operation dispatched");
    }
    static String run(Instrumentation test)throws Exception {
        count=0;log=new StringBuilder();CaseModeChecks.actions=new StringBuilder();Context c=test.getTargetContext();SharedPreferences prefs=AppPreferences.open(c,"clipboard");Map<String,?> before=prefs.getAll();ClipboardStore store=ClipboardStore.get(c);
        check(!prefs.getBoolean("enabled",false)&&store.list(System.currentTimeMillis(),7).length()==0,"test precondition has disabled recording and no existing history; no user history reset");
        DataFiles.initialize(c);UiAutomation auto=test.getUiAutomation();AccessibilityServiceInfo config=auto.getServiceInfo();config.flags|=AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS;auto.setServiceInfo(config);LayoutChecks.auto=auto;
        String token=UUID.randomUUID().toString();String plain="甲DFS乙，👩‍🔬"+token.replace("-",""),sensitive="SYNTHETIC-SENSITIVE-"+token,privateText="SYNTHETIC-PRIVATE-"+token,edited=plain+"\n修订";String label="wangshu-clipboard-test-"+token;
        ClipboardManager[] manager={null};ClipData[] oldClip={null};boolean[] touched={false};
        try{
            Activity settings=test.startActivitySync(new Intent(c,ClipboardSettingsActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));readySettings(test,settings);test.runOnMainSync(()->{manager[0]=(ClipboardManager)c.getSystemService(Context.CLIPBOARD_SERVICE);oldClip[0]=manager[0].getPrimaryClip();});
            setClip(test,manager[0],ClipData.newPlainText(label,plain));touched[0]=true;check(find(store,plain)==null,"disabled app does not record an actual OS clipboard change");
            click("记录剪贴板历史",true);LayoutChecks.await("开启本机剪贴板历史？",false);check(!prefs.getBoolean("enabled",false),"enable toggle waits for explicit confirmation");click("取消",false);check(!prefs.getBoolean("enabled",false),"cancel opt-in leaves recording disabled");click("记录剪贴板历史",true);click("开启",false);check(prefs.getBoolean("enabled",false),"native confirmation enables local history");
            Activity activity=test.startActivitySync(new Intent(c,SettingsActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));EditText editor=activity.findViewById(R.id.test_editor);InputMethodManager imm=(InputMethodManager)c.getSystemService(Context.INPUT_METHOD_SERVICE);serve(test,activity,editor,imm);
            setClip(test,manager[0],ClipData.newPlainText(label,plain));recorded(store,plain);check(true,"actual plain OS clipboard event reaches opted-in IME history");
            ClipData protectedClip=ClipData.newPlainText(label,sensitive);PersistableBundle extras=new PersistableBundle();extras.putBoolean("android.content.extra.IS_SENSITIVE",true);protectedClip.getDescription().setExtras(extras);setClip(test,manager[0],protectedClip);check(find(store,sensitive)==null,"actual flagged-sensitive OS clip is excluded");
            test.runOnMainSync(()->{editor.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_PASSWORD);imm.restartInput(editor);});SystemClock.sleep(700);setClip(test,manager[0],ClipData.newPlainText(label,privateText));check(find(store,privateText)==null,"actual password-field clipboard change is excluded");
            test.runOnMainSync(()->{editor.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_FLAG_MULTI_LINE);imm.restartInput(editor);});serve(test,activity,editor,imm);SystemClock.sleep(700);check(find(store,privateText)==null,"leaving password field does not import its stale current clip");
            setClip(test,manager[0],ClipData.newPlainText(label,plain));recorded(store,plain);test.runOnMainSync(()->{editor.setText("KEEP|");editor.setSelection(5);});SystemClock.sleep(400);click("剪贴板与常用语",true);LayoutChecks.await("剪贴板标签",true);click("插入剪贴板条目 1",true);CaseModeChecks.awaitText(test,editor,"KEEP|"+plain);check(true,"native history item inserts once through OS connection preserving prefix");
            click("剪贴板更多操作 1",true);SystemClock.sleep(300);click("置顶",false);check(find(store,plain).getBoolean("pinned"),"native expanded row pins its stable history identity");
            click("剪贴板更多操作 1",true);SystemClock.sleep(300);click("编辑",false);LayoutChecks.await("剪贴板历史内容",true);Bundle edit=new Bundle();edit.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,edited);check(LayoutChecks.await("剪贴板历史内容",true).performAction(AccessibilityNodeInfo.ACTION_SET_TEXT,edit),"separate native history editor accepts replacement text");click("保存条目",false);recorded(store,edited);check(CandidatePanelChecks.value(test,editor).equals("KEEP|"+plain),"editing history does not edit the previous host text");
            serve(test,activity,editor,imm);click("剪贴板与常用语",true);check(store.list(System.currentTimeMillis(),7).length()==1,"reopening panel does not reimport unchanged system clipboard after editing history");click("剪贴板更多操作 1",true);SystemClock.sleep(300);PersonalUiChecks.settledScreenshot(test,c,"native-clipboard-row-actions.png");click("＋ 常用语",false);check(Arrays.stream(PersonalStore.get(c).words("notashortcut")).count()==0,"adding phrase leaves unrelated shorthand candidates unchanged");
            JSONArray phrases=PersonalStore.get(c).snapshot().getJSONArray("phrases");boolean added=false;for(int i=0;i<phrases.length();i++)if(edited.equals(phrases.getJSONObject(i).getString("text")))added=true;check(added,"native row action saves exact multiline item to phrases");
            click("常用语标签",true);LayoutChecks.await("插入常用语 1",true);click("返回",false);click("剪贴板与常用语",true);LayoutChecks.await("插入剪贴板条目 1",true);check(true,"every new panel opening resets from phrases to Clipboard tab");
            test.runOnMainSync(()->{editor.setText("BASE|end");editor.setSelection(5);});SystemClock.sleep(400); // Re-enter panel if the host restarted input after replacing its test text.
            boolean panel=false;for(AccessibilityWindowInfo window:auto.getWindows())if(LayoutChecks.find(window.getRoot(),"剪贴板标签",true)!=null)panel=true;if(!panel)click("剪贴板与常用语",true);
            click("剪贴板更多操作 1",true);SystemClock.sleep(300);click("拆词",false);click("拆词块 2 乙",true);click("拆词块 0 甲",true);CaseModeChecks.awaitText(test,editor,"BASE|乙甲end");check(true,"actual split panel inserts non-contiguous blocks in click order");click("拆词块 2 乙",true);CaseModeChecks.awaitText(test,editor,"BASE|甲end");check(true,"actual deselection withdraws only that block and preserves host suffix");PersonalUiChecks.settledScreenshot(test,c,"native-clipboard-split-selection.png");
            click("返回",false);click("清空",false);LayoutChecks.await("确认清空",false);click("取消",false);check(find(store,edited)!=null,"cancel clear preserves pinned history");click("清空",false);click("确认清空",false);check(store.list(System.currentTimeMillis(),7).length()==0,"explicit native clear removes only this test-owned history including pin");
            return "PASS "+count+" actual OS clipboard and native panel checks\n"+log+CaseModeChecks.actions+"Synthetic cloud clipboard only; real phone/Windows NOT_RUN\nDISPLAY "+(c.getResources().getConfiguration().orientation==android.content.res.Configuration.ORIENTATION_LANDSCAPE?"landscape":"portrait")+" fontScale="+c.getResources().getConfiguration().fontScale+" nightMask="+(c.getResources().getConfiguration().uiMode&48)+"\n";
        }finally{
            test.runOnMainSync(()->{SharedPreferences.Editor restore=prefs.edit();for(String key:new String[]{"enabled","days"}){Object value=before.get(key);if(value instanceof Boolean)restore.putBoolean(key,(Boolean)value);else if(value instanceof Integer)restore.putInt(key,(Integer)value);else restore.remove(key);}if(!restore.commit())throw new AssertionError("Clipboard preferences restoration failed");if(touched[0]&&manager[0]!=null){ClipDescription current=manager[0].getPrimaryClipDescription();if(current!=null&&current.getLabel()!=null&&label.contentEquals(current.getLabel())){if(oldClip[0]!=null)manager[0].setPrimaryClip(oldClip[0]);else if(Build.VERSION.SDK_INT>=28)manager[0].clearPrimaryClip();else manager[0].setPrimaryClip(ClipData.newPlainText("", ""));}}});
            JSONArray rows=store.list(System.currentTimeMillis(),7);for(int i=0;i<rows.length();i++){JSONObject row=rows.getJSONObject(i);if(row.getString("text").contains(token)||row.getString("text").contains(token.replace("-","")))store.remove(row.getString("id"),row.getString("text"));}
            JSONArray phrases=PersonalStore.get(c).snapshot().getJSONArray("phrases");for(int i=phrases.length()-1;i>=0;i--)if(edited.equals(phrases.getJSONObject(i).getString("text")))PersonalStore.get(c).remove("phrases",i);
        }
    }
}
