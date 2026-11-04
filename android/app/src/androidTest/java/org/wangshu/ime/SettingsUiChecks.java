package org.wangshu.ime;
import android.app.*;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.content.*;
import android.graphics.Rect;
import android.os.*;
import android.view.accessibility.*;
import android.view.inputmethod.*;
import android.widget.EditText;

/** Actual settings save/validation and keyboard viewport on owned cloud text. */
final class SettingsUiChecks {
 static Instrumentation test;static int count;static StringBuilder log;
 static void check(boolean ok,String text){if(!ok)throw new AssertionError(text);count++;log.append("PASS ").append(text).append('\n');Bundle b=new Bundle();b.putString("stream","SETTINGS_PASS "+text+"\n");test.sendStatus(0,b);}
 static void click(String text)throws Exception{CaseModeChecks.click(text,false);}
 static void input(String text)throws Exception{AccessibilityNodeInfo n=LayoutChecks.await("每行一个常用符号",true);Bundle args=new Bundle();args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,text);if(!n.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT,args))throw new AssertionError("Owned settings text rejected OS set-text");SystemClock.sleep(250);}
 static String run(Instrumentation t)throws Exception{
  test=t;count=0;log=new StringBuilder();CaseModeChecks.actions=new StringBuilder();Context c=t.getTargetContext();DataFiles.initialize(c);UiAutomation auto=t.getUiAutomation();AccessibilityServiceInfo info=auto.getServiceInfo();info.flags|=AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS;auto.setServiceInfo(info);LayoutChecks.auto=auto;
  android.content.SharedPreferences prefs=AppPreferences.open(c,"keyboard");boolean had=prefs.contains("numberSymbols");String previous=prefs.getString("numberSymbols",MathSidebar.DEFAULTS);
  try{
   Activity a=t.startActivitySync(new Intent(c,SettingsActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));click("数字面板常用符号");LayoutChecks.await("每行一个常用符号",true);check(true,"actual symbol settings opens an editable native dialog");
   input("");click("保存");LayoutChecks.await("每行一个常用符号",true);check(prefs.getString("numberSymbols",MathSidebar.DEFAULTS).equals(previous),"invalid empty save keeps dialog open and stored symbols intact");
   input("+\n⊗\n+");click("保存");long until=SystemClock.elapsedRealtime()+10000;while(!prefs.getString("numberSymbols","").equals("+\n⊗")&&SystemClock.elapsedRealtime()<until)SystemClock.sleep(150);check(prefs.getString("numberSymbols","").equals("+\n⊗"),"actual Save persists canonical deduplicated custom symbols");
   click("数字面板常用符号");AccessibilityNodeInfo field=LayoutChecks.await("每行一个常用符号",true);check(String.valueOf(field.getText()).equals("+\n⊗"),"reopened settings displays saved entries rather than unsaved duplicate input");click("取消");
   EditText editor=a.findViewById(R.id.test_editor);InputMethodManager imm=(InputMethodManager)c.getSystemService(Context.INPUT_METHOD_SERVICE);t.runOnMainSync(()->editor.setText("Viewport Alpha · 测试输入"));boolean[] served={false};until=SystemClock.elapsedRealtime()+30000;while(SystemClock.elapsedRealtime()<until){t.runOnMainSync(()->{editor.requestFocus();served[0]=editor.hasWindowFocus()&&imm.isActive(editor)&&imm.isAcceptingText();if(editor.hasWindowFocus())imm.showSoftInput(editor,InputMethodManager.SHOW_IMPLICIT);});if(served[0])break;SystemClock.sleep(250);}if(!served[0])throw new AssertionError("Main editor has not regained window focus and OS input connection after dialog dismissal; no typing sent");HideChecks.await(auto,true);check(true,"OS keyboard is actually visible for owned settings editor");
   boolean visible=false;Rect fieldRect=new Rect(),keyboard=new Rect();until=SystemClock.elapsedRealtime()+15000;while(SystemClock.elapsedRealtime()<until){t.runOnMainSync(()->editor.getGlobalVisibleRect(fieldRect));for(AccessibilityWindowInfo w:auto.getWindows())if(w.getType()==AccessibilityWindowInfo.TYPE_INPUT_METHOD)w.getBoundsInScreen(keyboard);if(fieldRect.height()>0&&keyboard.height()>0&&fieldRect.bottom<=keyboard.top){visible=true;break;}SystemClock.sleep(250);}check(visible,"focused test editor remains visible above actual OS IME bounds");LayoutChecks.screenshot(c,"native-settings-editor-visible.png");check(true,"actual viewport screenshot saved for pixel review");
   return "PASS "+count+" native settings/save/viewport checks\n"+log+CaseModeChecks.actions+"Owned synthetic text only; disk-full/restart and other host editors are separate.\n";
  }finally{if(had)prefs.edit().putString("numberSymbols",previous).commit();else prefs.edit().remove("numberSymbols").commit();}
 }
}
