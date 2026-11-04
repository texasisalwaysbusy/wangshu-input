package org.wangshu.ime;

import android.accessibilityservice.AccessibilityServiceInfo;
import android.app.*;
import android.content.*;
import android.graphics.Rect;
import android.os.*;
import android.provider.Settings;
import android.view.*;
import android.view.accessibility.*;
import java.util.*;

/** Independent generated-only native UI check. Caller must register EmojiEditorFixture in the test APK. */
final class EmojiUiChecks {
    private static final String IME="org.wangshu.ime";
    private final Instrumentation instrument;private final UiAutomation auto;private final String host;private final StringBuilder log=new StringBuilder();private int window=-1,imeWindow=-1;
    private EmojiUiChecks(Instrumentation test){instrument=test;auto=test.getUiAutomation();host=test.getContext().getPackageName();}
    private interface Condition{boolean yes()throws Exception;}
    private void await(Condition condition,String label)throws Exception{long deadline=SystemClock.elapsedRealtime()+15000;do{if(condition.yes())return;SystemClock.sleep(150);}while(SystemClock.elapsedRealtime()<deadline);throw new AssertionError("Unobservable "+label+"; no guessed coordinates or replay");}
    private AccessibilityNodeInfo lookup(int type,String owner,String name,boolean description){StrictImeWindowQuery.Result result=StrictImeWindowQuery.find(auto,type,owner,name,description);if(!result.systemGuardComplete)return null;return result.node;}
    private AccessibilityNodeInfo waitNode(int type,String owner,String name,boolean desc)throws Exception{AccessibilityNodeInfo[] out={null};StrictImeWindowQuery.beginObservation(auto);await(()->(out[0]=lookup(type,owner,name,desc))!=null,name);return out[0];}
    private AccessibilityNodeInfo editor(int id,boolean focus){AccessibilityNodeInfo node=lookup(AccessibilityWindowInfo.TYPE_APPLICATION,host,"表情测试编辑器 "+id,true);if(node==null||!node.refresh()||!"android.widget.EditText".contentEquals(node.getClassName())||!node.isEditable()||node.isPassword()||!node.isVisibleToUser()||!node.isEnabled()||(focus&&!node.isFocused()))throw new AssertionError("Fixture identity/focus unavailable; no action");if(window<0)window=node.getWindowId();if(node.getWindowId()!=window)throw new AssertionError("Fixture window changed; no action");boolean foreground=false;for(AccessibilityWindowInfo w:auto.getWindows())if(w.getId()==window&&w.getType()==AccessibilityWindowInfo.TYPE_APPLICATION&&w.isFocused())foreground=true;if(!foreground)throw new AssertionError("Fixture application not foreground; no action");return node;}
    private void state(int id,String text,int start,int end){AccessibilityNodeInfo n=editor(id,true);CharSequence value=n.getText();if(n.isShowingHintText()||!text.contentEquals(value==null?"":value)||n.getTextSelectionStart()!=start||n.getTextSelectionEnd()!=end)throw new AssertionError("Fixture text or selection not observable/matching; id="+id+" expectedLength="+text.length()+" actualSelection="+n.getTextSelectionStart()+","+n.getTextSelectionEnd()+"; no inference or retry");}
    private void awaitState(int id,String text,int caret)throws Exception{await(()->{AccessibilityNodeInfo n=editor(id,true);CharSequence t=n.getText();return !n.isShowingHintText()&&text.contentEquals(t==null?"":t)&&n.getTextSelectionStart()==caret&&n.getTextSelectionEnd()==caret;},"fixture text and caret");state(id,text,caret,caret);}
    private AccessibilityNodeInfo ime(String name,boolean description)throws Exception{AccessibilityNodeInfo node=waitNode(AccessibilityWindowInfo.TYPE_INPUT_METHOD,IME,name,description);if(imeWindow<0)imeWindow=node.getWindowId();if(node.getWindowId()!=imeWindow)throw new AssertionError("IME window changed; no action");return node;}
    private void clickIme(String name,boolean description,int id,String expected,int caret)throws Exception{
        state(id,expected,caret,caret);AccessibilityNodeInfo n=ime(name,description);int targetWindow=n.getWindowId();StrictImeWindowQuery.Result fresh=StrictImeWindowQuery.find(auto,AccessibilityWindowInfo.TYPE_INPUT_METHOD,IME,name,description);n=fresh.node;
        if(!fresh.systemGuardComplete||n==null||!n.refresh()||n.getWindowId()!=targetWindow||!n.isClickable()||(n.getActions()&AccessibilityNodeInfo.ACTION_CLICK)==0)throw new AssertionError("Stale/nonclickable IME target; no pointer");
        Rect bounds=new Rect(),keyboard=new Rect();n.getBoundsInScreen(bounds);boolean found=false;for(AccessibilityWindowInfo w:auto.getWindows())if(w.getId()==targetWindow&&w.getType()==AccessibilityWindowInfo.TYPE_INPUT_METHOD){w.getBoundsInScreen(keyboard);found=true;}if(!found||bounds.width()<1||bounds.height()<1||!keyboard.contains(bounds))throw new AssertionError("IME bounds invalid; no pointer");state(id,expected,caret,caret);
        long down=SystemClock.uptimeMillis();MotionEvent d=MotionEvent.obtain(down,down,MotionEvent.ACTION_DOWN,bounds.centerX(),bounds.centerY(),0);MotionEvent u=MotionEvent.obtain(down,SystemClock.uptimeMillis(),MotionEvent.ACTION_UP,bounds.centerX(),bounds.centerY(),0);d.setSource(InputDevice.SOURCE_TOUCHSCREEN);u.setSource(InputDevice.SOURCE_TOUCHSCREEN);boolean a=false,b=false;try{a=auto.injectInputEvent(d,false);}finally{try{b=auto.injectInputEvent(u,false);}finally{d.recycle();u.recycle();}}if(!a||!b)throw new AssertionError("Pointer not acknowledged; outcome uncertain; no retry");log.append("POINTER ").append(name).append(" bounds=").append(bounds.toShortString()).append(" one stream\n");
    }
    private void selectRange(int id,String text,int start,int end)throws Exception{
        state(id,text,text.length(),text.length());AccessibilityNodeInfo n=editor(id,true);if((n.getActions()&AccessibilityNodeInfo.ACTION_SET_SELECTION)==0)throw new AssertionError("Fixture selection action unavailable; no action");Bundle args=new Bundle();args.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT,start);args.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT,end);if(!n.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION,args))throw new AssertionError("Fixture selection unacknowledged; no retry");await(()->{try{state(id,text,start,end);return true;}catch(AssertionError unobservable){return false;}},"selected range");
    }
    private void clickImeRange(String name,int id,String text,int start,int end)throws Exception{
        state(id,text,start,end);AccessibilityNodeInfo n=ime(name,true);int bound=n.getWindowId();StrictImeWindowQuery.Result fresh=StrictImeWindowQuery.find(auto,AccessibilityWindowInfo.TYPE_INPUT_METHOD,IME,name,true);n=fresh.node;if(!fresh.systemGuardComplete||n==null||!n.refresh()||n.getWindowId()!=bound||!n.isClickable()||(n.getActions()&AccessibilityNodeInfo.ACTION_CLICK)==0)throw new AssertionError("Range target unavailable; no action");state(id,text,start,end);if(!n.performAction(AccessibilityNodeInfo.ACTION_CLICK))throw new AssertionError("Range click unacknowledged; outcome uncertain; no retry");
    }
    private void scrollFor(String target,String knownChild,String scrollDescription,int id,String expected)throws Exception{
        for(int attempt=0;attempt<8;attempt++){
            if(lookup(AccessibilityWindowInfo.TYPE_INPUT_METHOD,IME,target,false)!=null)return;
            AccessibilityNodeInfo child=ime(knownChild,scrollDescription!=null);AccessibilityNodeInfo scroll=scrollDescription==null?child.getParent():child;
            for(int depth=0;scroll!=null&&depth<8&&!scroll.isScrollable();depth++)scroll=scroll.getParent();
            state(id,expected,expected.length(),expected.length());if(scroll==null||!scroll.refresh()||scroll.getWindowId()!=imeWindow||!IME.contentEquals(scroll.getPackageName()==null?"":scroll.getPackageName())||!scroll.isScrollable()||(scroll.getActions()&AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)==0)throw new AssertionError("Owned scroll container unavailable; no action");
            if(!scroll.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD))throw new AssertionError("Scroll unacknowledged; no retry");SystemClock.sleep(200);
        }throw new AssertionError("Target remains outside observed scroll range: "+target);
    }
    private void scrollToDescription(String target,String container,int id,String expected)throws Exception{
        for(int attempt=0;attempt<12;attempt++){
            if(lookup(AccessibilityWindowInfo.TYPE_INPUT_METHOD,IME,target,true)!=null)return;
            AccessibilityNodeInfo list=ime(container,true);state(id,expected,expected.length(),expected.length());
            if(!list.refresh()||!list.isScrollable()||(list.getActions()&AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)==0||!list.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD))throw new AssertionError("Emoji list scroll unavailable/unacknowledged; no retry");SystemClock.sleep(200);
        }throw new AssertionError("Emoji list target not observable after bounded scrolling");
    }
    private void runChecks()throws Exception{
        String chosen=Settings.Secure.getString(instrument.getTargetContext().getContentResolver(),Settings.Secure.DEFAULT_INPUT_METHOD);if(chosen==null||!chosen.startsWith(IME+"/"))throw new AssertionError("Wangshu IME is not already selected; no settings changed");
        AccessibilityServiceInfo config=auto.getServiceInfo();config.flags|=AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS;auto.setServiceInfo(config);
        Intent intent=new Intent().setComponent(new ComponentName(host,EmojiEditorFixture.class.getName())).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);instrument.getContext().startActivity(intent);
        await(()->{try{editor(1,true);return true;}catch(AssertionError notReady){return false;}},"fresh fixture focus");awaitState(1,"",0);
        clickIme("数学",true,1,"",0);ime("希腊",false);scrollFor("表情","希腊",null,1,"");clickIme("表情",false,1,"",0);
        ime("表情选择面板",true);ime("表情分类 笑脸",true);AccessibilityNodeInfo happy=ime("表情 开心",true);Rect hit=new Rect();happy.getBoundsInScreen(hit);float density=instrument.getTargetContext().getResources().getDisplayMetrics().density;if(hit.height()+1<48*density)throw new AssertionError("Emoji target height under 48dp: "+hit);
        clickIme("表情 开心",true,1,"",0);awaitState(1,"😀",2);clickIme("表情 开心",true,1,"😀",2);awaitState(1,"😀😀",4);log.append("PASS two independent taps insert exactly two complete Unicode emoji\n");
        scrollToDescription("表情 融化","表情列表，可上下滚动",1,"😀😀");log.append("PASS vertical scroll reaches final smile item with accessible name\n");
        clickIme("表情分类 手势",true,1,"😀😀",4);clickIme("表情 赞 中肤色",true,1,"😀😀",4);awaitState(1,"😀😀👍🏽",8);log.append("PASS skin-tone sequence inserted intact\n");
        scrollFor("颜文字","希腊",null,1,"😀😀👍🏽");clickIme("颜文字",false,1,"😀😀👍🏽",8);ime("颜文字选择面板",true);clickIme("颜文字 猫咪",true,1,"😀😀👍🏽",8);String text="😀😀👍🏽( 'ω' )";awaitState(1,text,text.length());log.append("PASS kaomoji exact whitespace and punctuation\n");
        selectRange(1,text,0,2);clickImeRange("颜文字 猫咪",1,text,0,2);String replacement="( 'ω' )"+text.substring(2);await(()->{try{state(1,replacement,7,7);return true;}catch(AssertionError notYet){return false;}},"replacement with complete visible selection");log.append("PASS selected emoji replaced with exact kaomoji, remaining text preserved\n");
        clickIme("ABC",false,1,replacement,7);ime("q",false);state(1,replacement,7,7);log.append("PASS leave emoji panel without altering editor\n");
        AccessibilityNodeInfo second=editor(2,false);if(!second.isFocusable()||(second.getActions()&AccessibilityNodeInfo.ACTION_FOCUS)==0)throw new AssertionError("Second owned editor cannot be focused; no action");if(!second.performAction(AccessibilityNodeInfo.ACTION_FOCUS))throw new AssertionError("Fixture focus unacknowledged; no retry");awaitState(2,"",0);AccessibilityNodeInfo first=editor(1,false);if(!replacement.contentEquals(first.getText()==null?"":first.getText()))throw new AssertionError("First fixture changed while switching session");log.append("PASS new test editor session leaves prior generated text untouched\n");
        log.append("CONFIG orientation=").append(instrument.getTargetContext().getResources().getConfiguration().orientation).append(" fontScale=").append(instrument.getTargetContext().getResources().getConfiguration().fontScale).append(" density=").append(density).append("; only this configuration exercised\n");
    }
    static String run(Instrumentation instrument)throws Exception{EmojiUiChecks checks=new EmojiUiChecks(instrument);checks.runChecks();return "PASS generated test fixture: emoji UI\n"+checks.log+"No real phone or microphone tested. Selection/session/panel stale callbacks, landscape and enlarged fonts require separate scenarios; no pass claimed.\n";}
}
