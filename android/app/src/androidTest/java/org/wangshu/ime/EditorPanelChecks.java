package org.wangshu.ime;
import android.app.*;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.content.*;
import android.graphics.Rect;
import android.os.*;
import android.view.*;
import android.view.accessibility.*;
import android.view.inputmethod.*;
import android.widget.EditText;

/** Actual OS IME edits owned fixture text; never calls the production controller directly. */
final class EditorPanelChecks {
    static Instrumentation test;static EditText editor;static int checks;static StringBuilder log;
    static void check(boolean yes,String name){if(!yes)throw new AssertionError(name);checks++;Bundle update=new Bundle();update.putString("stream","EDITOR_PASS "+name+"\n");test.sendStatus(0,update);log.append("PASS ").append(name).append('\n');}
    static void click(String value,boolean description)throws Exception{
        AccessibilityNodeInfo node=LayoutChecks.await(value,description);Rect bounds=new Rect();node.getBoundsInScreen(bounds);
        if(!node.refresh()||!node.isVisibleToUser()||!node.isEnabled()||!node.isClickable()||bounds.width()<1||bounds.height()<1)throw new AssertionError("No actual native button for pointer: "+value);
        boolean inKeyboard=false;for(AccessibilityWindowInfo window:LayoutChecks.auto.getWindows())if(window.getType()==AccessibilityWindowInfo.TYPE_INPUT_METHOD){Rect r=new Rect();window.getBoundsInScreen(r);if(r.contains(bounds))inKeyboard=true;}
        if(!inKeyboard)throw new AssertionError("Button is outside actual IME window: "+value);
        pointer(LayoutChecks.auto,MotionEvent.ACTION_DOWN,bounds.centerX(),bounds.centerY());pointer(LayoutChecks.auto,MotionEvent.ACTION_UP,bounds.centerX(),bounds.centerY());
        CaseModeChecks.actions.append("POINTER ").append(value).append(" bounds=").append(bounds.toShortString()).append(" repeated=false\n");SystemClock.sleep(350);
    }
    static void fixture(String text,int caret)throws Exception{test.runOnMainSync(()->{editor.setText(text);editor.setSelection(caret);});SystemClock.sleep(500);boolean visible=false;for(AccessibilityWindowInfo w:LayoutChecks.auto.getWindows())if(LayoutChecks.find(w.getRoot(),"→",false)!=null)visible=true;if(!visible){click("光标与文本编辑",true);LayoutChecks.await("→",false);}}
    static int[] selection(){int[] p=new int[2];test.runOnMainSync(()->{p[0]=editor.getSelectionStart();p[1]=editor.getSelectionEnd();});return p;}
    static void positions(int start,int end,String name){long until=SystemClock.elapsedRealtime()+10000;int[] p;do{p=selection();if(p[0]==start&&p[1]==end){check(true,name);return;}SystemClock.sleep(150);}while(SystemClock.elapsedRealtime()<until);throw new AssertionError(name+" actual="+p[0]+","+p[1]);}
    static void value(String expected,String name)throws Exception{CaseModeChecks.awaitText(test,editor,expected);check(true,name);}
    static long pointerDown;
    static void pointer(UiAutomation auto,int action,float x,float y){long now=SystemClock.uptimeMillis();if(action==MotionEvent.ACTION_DOWN)pointerDown=now;MotionEvent event=MotionEvent.obtain(pointerDown,now,action,x,y,0);event.setSource(InputDevice.SOURCE_TOUCHSCREEN);try{if(!auto.injectInputEvent(event,true))throw new AssertionError("OS rejected actual pointer event");}finally{event.recycle();}}
    static String run(Instrumentation instrumentation)throws Exception{
        test=instrumentation;checks=0;log=new StringBuilder();CaseModeChecks.actions=new StringBuilder();Context c=test.getTargetContext();DataFiles.initialize(c);UiAutomation auto=test.getUiAutomation();AccessibilityServiceInfo config=auto.getServiceInfo();config.flags|=AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS;auto.setServiceInfo(config);LayoutChecks.auto=auto;
        Activity a=test.startActivitySync(new Intent(c,SettingsActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));editor=a.findViewById(R.id.test_editor);InputMethodManager imm=(InputMethodManager)c.getSystemService(Context.INPUT_METHOD_SERVICE);
        long until=SystemClock.elapsedRealtime()+30000;boolean[] ready={false};while(SystemClock.elapsedRealtime()<until){test.runOnMainSync(()->{editor.requestFocus();ready[0]=editor.hasWindowFocus()&&imm.isActive(editor)&&imm.isAcceptingText();imm.showSoftInput(editor,InputMethodManager.SHOW_IMPLICIT);});if(ready[0])break;SystemClock.sleep(250);}if(!ready[0])throw new AssertionError("editor unavailable");HideChecks.await(auto,true);
        AccessibilityNodeInfo center=LayoutChecks.await("光标与文本编辑",true);Rect r=new Rect();center.getBoundsInScreen(r);Rect window=new Rect();for(AccessibilityWindowInfo w:auto.getWindows())if(w.getType()==AccessibilityWindowInfo.TYPE_INPUT_METHOD)w.getBoundsInScreen(window);check(Math.abs(r.exactCenterX()-window.exactCenterX())<=1,"editing control remains geometrically centered with unequal side actions");
        click("光标与文本编辑",true);LayoutChecks.await("开始选择文字",true);check(true,"single native click opens actual three-row editing panel");
        String text="A👩🏽‍💻e\u0301🇨🇦Z";fixture(text,1);click("→",false);positions(8,8,"right moves across complete emoji skin-tone ZWJ grapheme");click("→",false);positions(10,10,"right moves across combining-accent grapheme");click("→",false);positions(14,14,"right moves across flag pair");
        fixture(text,text.length());click("⌫",false);value("A👩🏽‍💻e\u0301🇨🇦","backspace removes one final visible letter");click("⌫",false);value("A👩🏽‍💻e\u0301","backspace removes complete flag pair");click("⌫",false);value("A👩🏽‍💻","backspace removes base letter with accent");click("⌫",false);value("A","backspace removes complete modified ZWJ emoji");
        fixture(text,1);click("Del",false);value("Ae\u0301🇨🇦Z","forward Del removes only complete next grapheme and preserves both sides");
        fixture("A👩🏽‍💻B",2);click("⌫",false);value("A👩🏽‍💻B","caret inside surrogate/ZWJ cluster refuses partial backspace");click("→",false);positions(8,8,"arrow safely leaves interior caret at next whole-grapheme boundary");click("⌫",false);value("AB","deletion after safe navigation removes entire emoji");
        fixture("Ae\u0301B",2);click("Del",false);value("Ae\u0301B","caret inside combining cluster refuses partial forward deletion");
        fixture("A👩🏽‍💻B",1);test.runOnMainSync(()->editor.setSelection(1,2));SystemClock.sleep(500);click("⌫",false);value("A👩🏽‍💻B","partial surrogate selection is preserved instead of producing a broken emoji");
        fixture("ABC",0);click("开始选择文字",true);LayoutChecks.await("取消选择并收为光标",true);click("→",false);positions(0,1,"first forward selection step keeps fixed anchor");click("→",false);positions(0,2,"forward selection keeps fixed anchor");click("取消选择并收为光标",true);positions(2,2,"second Select immediately collapses to forward active end");LayoutChecks.await("开始选择文字",true);check(true,"editing panel stays open after deselection");
        fixture("ABC",3);click("开始选择文字",true);click("←",false);click("←",false);positions(3,1,"reverse selection preserves active end instead of sorting offsets");click("取消选择并收为光标",true);positions(1,1,"reverse deselection collapses to last moved end, not maximum or anchor");
        fixture("one\ntwo",5);click("开头",false);positions(0,0,"Start targets whole input field");click("末尾",false);positions(7,7,"End targets whole input field");
        fixture("ABC",1);click("Tab",false);value("A\tBC","Tab inserts without overwriting adjacent text");
        LayoutChecks.screenshot(c,"native-editor-panel.png");click("返回",false);LayoutChecks.await("空格",false);check(true,"Return restores native letter keyboard");
        test.runOnMainSync(()->{editor.setText("ABCDE");editor.setSelection(2);});SystemClock.sleep(600);AccessibilityNodeInfo control=LayoutChecks.await("光标与文本编辑",true);Rect touch=new Rect();control.getBoundsInScreen(touch);long hold=android.view.ViewConfiguration.getLongPressTimeout()+150;
        pointer(auto,MotionEvent.ACTION_DOWN,touch.centerX(),touch.centerY());SystemClock.sleep(hold);pointer(auto,MotionEvent.ACTION_UP,touch.centerX(),touch.centerY());positions(2,2,"long hold alone does not move the cursor");LayoutChecks.await("空格",false);check(true,"long hold alone does not open the editor panel");
        pointer(auto,MotionEvent.ACTION_DOWN,touch.centerX(),touch.centerY());SystemClock.sleep(hold);float shift=16*c.getResources().getDisplayMetrics().density+2;pointer(auto,MotionEvent.ACTION_MOVE,touch.centerX()+shift,touch.centerY());pointer(auto,MotionEvent.ACTION_UP,touch.centerX()+shift,touch.centerY());positions(3,3,"long hold plus one configured right-drag step moves one grapheme");
        return "PASS "+checks+" actual OS editing-panel checks\n"+log+CaseModeChecks.actions+"Cloud editor only; stationary hold and one right-drag step checked; multi-step/left-drag/custom sensitivity, other host apps and real phone remain separate acceptance.\n";
    }
}
