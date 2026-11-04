package org.wangshu.ime;
import android.app.*;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.content.*;
import android.graphics.Rect;
import android.os.*;
import android.view.accessibility.*;
import android.view.inputmethod.*;
import android.widget.EditText;

/** Actual numeric grid, scrollable symbols and local customization, not AI math evaluation. */
final class NumericPanelChecks {
 static int checks;static Instrumentation test;static StringBuilder log;
 static void check(boolean ok,String text){if(!ok)throw new AssertionError(text);checks++;log.append("PASS ").append(text).append('\n');Bundle b=new Bundle();b.putString("stream","NUMBER_PASS "+text+"\n");test.sendStatus(0,b);}
 static void click(String text)throws Exception{CaseModeChecks.click(text,false);}
 static String run(Instrumentation t)throws Exception{
  test=t;checks=0;log=new StringBuilder();CaseModeChecks.actions=new StringBuilder();Context c=t.getTargetContext();DataFiles.initialize(c);
  check(MathSidebar.parse(MathSidebar.DEFAULTS).length==18,"default sidebar contains18 symbols, not four fixed keys");check(MathSidebar.parse("+\n+\n  ×  ").length==2,"explicit custom entries are trimmed and deduplicated in original order");boolean rejected=false;try{MathSidebar.parse("bad\tcontrol");}catch(IllegalArgumentException e){rejected=true;}check(rejected,"control characters rejected before persistence");
  android.content.SharedPreferences prefs=AppPreferences.open(c,"keyboard");boolean had=prefs.contains("numberSymbols");String old=prefs.getString("numberSymbols","");
  UiAutomation auto=t.getUiAutomation();AccessibilityServiceInfo config=auto.getServiceInfo();config.flags|=AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS;auto.setServiceInfo(config);LayoutChecks.auto=auto;
  try{
   prefs.edit().putString("numberSymbols",MathSidebar.DEFAULTS+"\n⊗").commit();
   Activity a=t.startActivitySync(new Intent(c,SettingsActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));EditText editor=a.findViewById(R.id.test_editor);InputMethodManager imm=(InputMethodManager)c.getSystemService(Context.INPUT_METHOD_SERVICE);boolean[] ready={false};long until=SystemClock.elapsedRealtime()+30000;while(SystemClock.elapsedRealtime()<until){t.runOnMainSync(()->{editor.requestFocus();ready[0]=editor.hasWindowFocus()&&imm.isActive(editor)&&imm.isAcceptingText();imm.showSoftInput(editor,InputMethodManager.SHOW_IMPLICIT);});if(ready[0])break;SystemClock.sleep(250);}if(!ready[0])throw new AssertionError("owned editor not served");HideChecks.await(auto,true);click("123");
   Rect one=LayoutChecks.bounds("1"),two=LayoutChecks.bounds("2"),three=LayoutChecks.bounds("3"),four=LayoutChecks.bounds("4"),seven=LayoutChecks.bounds("7"),zero=LayoutChecks.bounds("0");check(one.top==two.top&&two.top==three.top&&one.left<two.left&&two.left<three.left,"numeric first row is1/2/3 in three columns");check(one.left==four.left&&four.left==seven.left&&four.top>one.top&&seven.top>four.top&&zero.top>seven.top,"numeric grid continues4–9 with bottom0");
   LayoutChecks.screenshot(c,"native-numeric-panel.png");click("1");click("+");click("2");CaseModeChecks.awaitText(t,editor,"1+2");check(true,"actual numeric/symbol soft keys commit exact1+2 without phantom spaces");
   AccessibilityNodeInfo scroll=LayoutChecks.await("数字面板常用数学符号，可上下滚动",true);boolean found=false;for(int n=0;n<10;n++){for(AccessibilityWindowInfo w:auto.getWindows())if(LayoutChecks.find(w.getRoot(),"数字面板符号 ⊗",true)!=null)found=true;if(found)break;scroll.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD);SystemClock.sleep(250);scroll=LayoutChecks.await("数字面板常用数学符号，可上下滚动",true);}check(found,"custom local symbol can be reached through actual scrollable sidebar");CaseModeChecks.click("数字面板符号 ⊗",true);CaseModeChecks.awaitText(t,editor,"1+2⊗");check(true,"explicit custom symbol commits through real InputConnection");
   click("返回");click("123");LayoutChecks.await("数字面板符号 +",true);check(true,"reopening numeric panel resets sidebar to the default top position");
   return "PASS "+checks+" native numeric panel checks\n"+log+CaseModeChecks.actions+"Cloud synthetic editor only; arithmetic/AI context prediction and real phone NOT_RUN.\n";
  }finally{if(had)prefs.edit().putString("numberSymbols",old).commit();else prefs.edit().remove("numberSymbols").commit();}
 }
}
