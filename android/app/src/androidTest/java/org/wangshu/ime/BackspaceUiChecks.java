package org.wangshu.ime;

import android.accessibilityservice.AccessibilityServiceInfo;
import android.app.Instrumentation;
import android.app.UiAutomation;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.graphics.Rect;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.view.InputDevice;
import android.view.MotionEvent;
import android.view.ViewConfiguration;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;
import java.io.File;
import java.io.FileOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.json.JSONArray;
import org.json.JSONObject;

/** Actual system IME pointers + separate-UID owned EditText. Prepared source, runtime separately recorded. */
public final class BackspaceUiChecks {
    private static final String HOST="org.wangshu.scan.test",IME="org.wangshu.ime";
    private static final String EDITOR="独立应用编辑器";
    private static final String DELETE="退格；按住加速，上滑提示后松手删除光标前内容";
    private static final String HINT="松手删除光标前内容 · 移回取消";
    private static final String UNDO="撤销本次删除光标前内容";
    private final Instrumentation test;private final Context context;private final UiAutomation auto;
    private final String runName="backspace-ui-"+System.currentTimeMillis()+"-"+UUID.randomUUID();
    private final File directory;private final JSONArray events=new JSONArray(),screenshots=new JSONArray();
    private final StringBuilder log=new StringBuilder();private String stage="setup",status="RUNNING";
    private int checks,completedTapCommands;private boolean held,uncertainShellTap;private long downTime;private float lastX,lastY;
    private String failureDetail;
    private final Rect screenBounds=new Rect();
    private final long holdMillis=ViewConfiguration.getLongPressTimeout();
    private static final class Snapshot {
        final String text;final int start,end;
        Snapshot(AccessibilityNodeInfo node){text=node.getText()==null?"":node.getText().toString();start=node.getTextSelectionStart();end=node.getTextSelectionEnd();}
        boolean same(Snapshot other){return other!=null&&text.equals(other.text)&&start==other.start&&end==other.end;}
    }
    private static final class Place {
        final Rect key,window;
        Place(Rect key,Rect window){this.key=key;this.window=window;}
    }
    private BackspaceUiChecks(Instrumentation test)throws Exception{
        this.test=test;context=test.getTargetContext();auto=test.getUiAutomation();directory=new File(context.getFilesDir(),"test-evidence/"+runName);
        if(!directory.mkdirs())throw new IllegalStateException("Cannot create NEW owned UI evidence directory");
        AccessibilityServiceInfo config=auto.getServiceInfo();config.flags|=AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS;auto.setServiceInfo(config);LayoutChecks.auto=auto;CaseModeChecks.actions=new StringBuilder();
        persist(null);
    }
    private int dp(int value){return Math.round(value*context.getResources().getDisplayMetrics().density);}
    private void event(String action,JSONObject detail)throws Exception{detail.put("action",action).put("stage",stage).put("uptime_ms",SystemClock.uptimeMillis());events.put(detail);}
    private JSONObject windows()throws Exception{
        JSONArray rows=new JSONArray();for(AccessibilityWindowInfo w:auto.getWindows()){Rect r=new Rect();w.getBoundsInScreen(r);AccessibilityNodeInfo node=w.getRoot();rows.put(new JSONObject().put("type",w.getType()).put("layer",w.getLayer()).put("focused",w.isFocused()).put("active",w.isActive()).put("bounds",r.flattenToString()).put("package",node==null?"unavailable":String.valueOf(node.getPackageName())));}
        return new JSONObject().put("windows",rows);
    }
    private void persist(String failure)throws Exception{
        if(failure!=null)failureDetail=failure;
        JSONObject report=new JSONObject().put("scope","Actual system pointers/A11y in cloud with generated separate-UID text; not real phone, audio or ASR").put("status",status).put("stage",stage).put("checks_passed",checks).put("hold_timeout_ms",holdMillis).put("evidence_directory",runName).put("events",events).put("screenshots",screenshots).put("check_log",log.toString()).put("case_mode_actions",String.valueOf(CaseModeChecks.actions)).put("completed_normal_tap_commands",completedTapCommands).put("uncertain_shell_tap",uncertainShellTap).put("physical_delivery_span","NOT_OBSERVED_FROM_OUTSIDE_IME; hold event timestamps and tap command submission/completion recorded separately").put("rapid_burst_performance","NOT_RUN; normal complete tap counts only");
        if(failureDetail!=null)report.put("failure",failureDetail);report.put("window_context",windows());
        try(FileOutputStream out=new FileOutputStream(new File(directory,"results.json"))){out.write(report.toString(2).getBytes(StandardCharsets.UTF_8));out.getFD().sync();}
    }
    private void stage(String value)throws Exception{stage=value;Bundle update=new Bundle();update.putString("stream","BACKSPACE_UI_STAGE "+stage+" evidence="+runName+"\n");test.sendStatus(0,update);persist(null);}
    private void check(boolean value,String label)throws Exception{if(!value)throw new AssertionError(label);checks++;log.append("PASS ").append(label).append('\n');persist(null);}
    private boolean anr(AccessibilityNodeInfo node,int depth,int[] budget){
        if(node==null||depth>24||budget[0]--<=0||!node.refresh()||!node.isVisibleToUser())return false;CharSequence value=node.getText();String s=value==null?"":value.toString().toLowerCase(Locale.ROOT);
        if(s.contains("isn't responding")||s.contains("is not responding")||s.contains("not responding")||s.contains("无响应")||s.contains("未响应"))return true;
        for(int i=0;i<node.getChildCount();i++)if(anr(node.getChild(i),depth+1,budget))return true;return false;
    }
    private void noAnr()throws Exception{
        for(AccessibilityWindowInfo window:auto.getWindows()){
            AccessibilityNodeInfo node=window.getRoot();if(node==null)continue;String pkg=String.valueOf(node.getPackageName());
            if((pkg.equals("android")||pkg.equals("com.android.systemui")||pkg.contains("launcher")||pkg.contains("quickstep"))&&anr(node,0,new int[]{1200}))throw new AssertionError("PRECONDITION_ANR: system/launcher dialog; no Wait or policy change attempted");
        }
    }
    private AccessibilityNodeInfo ownedNode(String value,boolean description,String pkg,int windowType)throws Exception{
        noAnr();for(AccessibilityWindowInfo window:auto.getWindows())if(window.getType()==windowType){AccessibilityNodeInfo node=LayoutChecks.find(window.getRoot(),value,description);if(node!=null&&pkg.equals(String.valueOf(node.getPackageName()))&&node.isVisibleToUser()&&node.isEnabled())return node;}return null;
    }
    private AccessibilityNodeInfo awaitNode(String value,boolean description,String pkg,int type,long timeout)throws Exception{
        long until=SystemClock.elapsedRealtime()+timeout;
        do{AccessibilityNodeInfo node=ownedNode(value,description,pkg,type);if(node!=null)return node;SystemClock.sleep(100);}while(SystemClock.elapsedRealtime()<until);
        throw new AssertionError("PRECONDITION_NODE: owned visible control unavailable: "+value+"; no guessed touch");
    }
    private AccessibilityNodeInfo editor()throws Exception{
        long until=SystemClock.elapsedRealtime()+15000;
        do{AccessibilityNodeInfo node=ownedNode(EDITOR,true,HOST,AccessibilityWindowInfo.TYPE_APPLICATION);if(node!=null&&node.isEditable()&&node.isFocused())return node;SystemClock.sleep(100);}while(SystemClock.elapsedRealtime()<until);
        throw new AssertionError("PRECONDITION_FOCUS: independent editable host does not have focus; no edit sent");
    }
    private Snapshot snapshot()throws Exception{return new Snapshot(editor());}
    private Snapshot awaitState(String expected,int caret,long timeout)throws Exception{
        long until=SystemClock.elapsedRealtime()+timeout;Snapshot now;
        do{now=snapshot();if(now.text.equals(expected)&&(caret<0||(now.start==caret&&now.end==caret)))return now;SystemClock.sleep(100);}while(SystemClock.elapsedRealtime()<until);
        event("STATE_MISMATCH",new JSONObject().put("expected_generated_text",expected).put("expected_caret",caret).put("actual_owned_text",now.text).put("actual_start",now.start).put("actual_end",now.end));
        throw new AssertionError("Generated host state mismatch; actual synthetic text/selection retained in events");
    }
    private Place deletePlace()throws Exception{
        editor();AccessibilityNodeInfo key=awaitNode(DELETE,true,IME,AccessibilityWindowInfo.TYPE_INPUT_METHOD,20000);Rect bounds=new Rect();key.getBoundsInScreen(bounds);
        Rect windowBounds=null;for(AccessibilityWindowInfo window:auto.getWindows())if(window.getType()==AccessibilityWindowInfo.TYPE_INPUT_METHOD){Rect r=new Rect();window.getBoundsInScreen(r);AccessibilityNodeInfo root=window.getRoot();if(root!=null&&IME.equals(String.valueOf(root.getPackageName()))&&r.contains(bounds)){windowBounds=r;break;}}
        if(screenBounds.isEmpty()){Bitmap screen=auto.takeScreenshot();if(screen==null)throw new AssertionError("PRECONDITION_SCREEN: screen bounds unavailable");try{screenBounds.set(0,0,screen.getWidth(),screen.getHeight());}finally{screen.recycle();}}
        if(windowBounds==null||bounds.width()<1||bounds.height()<1||!key.isClickable()||!screenBounds.contains(bounds))throw new AssertionError("PRECONDITION_BOUNDS: actual Wangshu delete key not inside visible IME/screen bounds");
        return new Place(bounds,windowBounds);
    }
    private void setup()throws Exception{
        stage("owned-host-and-ime-ready");noAnr();context.startActivity(new Intent().setComponent(new ComponentName(HOST,HOST+".ImeEditorFixture")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TOP));
        editor();deletePlace();AccessibilityNodeInfo chinese=ownedNode("中",false,IME,AccessibilityWindowInfo.TYPE_INPUT_METHOD);
        if(chinese!=null){CaseModeChecks.click("中",false);awaitNode("英",false,IME,AccessibilityWindowInfo.TYPE_INPUT_METHOD,15000);}deletePlace();
        int hostUid=context.getPackageManager().getApplicationInfo(HOST,0).uid,imeUid=context.getApplicationInfo().uid;
        event("OWNED_UIDS",new JSONObject().put("host_uid",hostUid).put("ime_uid",imeUid));
        check(IME.equals(context.getPackageName())&&hostUid!=imeUid,"separate UID fixture focused and actual Wangshu keyboard/delete bounds ready before synthetic text");
    }
    private void selection(int start,int end)throws Exception{
        AccessibilityNodeInfo node=editor();Bundle args=new Bundle();args.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT,start);args.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT,end);
        boolean ack=node.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION,args);event("A11Y_SET_SELECTION",new JSONObject().put("start",start).put("end",end).put("accepted",ack));if(!ack)throw new AssertionError("Host refused owned selection; not retried");
    }
    private void fixture(String value,int caret)throws Exception{
        if(held)throw new AssertionError("Fixture replacement requires no owned pointer hold");deletePlace();AccessibilityNodeInfo node=editor();Bundle args=new Bundle();args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,value);
        boolean ack=node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT,args);event("A11Y_SET_GENERATED_TEXT",new JSONObject().put("utf16_units",value.length()).put("accepted",ack));if(!ack)throw new AssertionError("Host refused owned fixture text; not retried");
        awaitState(value,-1,15000);selection(caret,caret);awaitState(value,caret,15000);deletePlace();
    }
    private MotionEvent motion(int action,long when,float x,float y){MotionEvent e=MotionEvent.obtain(downTime,when,action,x,y,0);e.setSource(InputDevice.SOURCE_TOUCHSCREEN);return e;}
    private void normalTap(Place place,int number)throws Exception{
        if(held)throw new AssertionError("No normal tap during an owned long-hold stream");noAnr();
        // One public normal OS input tap owns its complete DOWN/UP. UiAutomation
        // injectInputEvent(sync=false) still waits animations, so it is NOT a fast pair API.
        String command="input tap "+place.key.centerX()+" "+place.key.centerY();long begin=SystemClock.uptimeMillis();
        ParcelFileDescriptor pipe=auto.executeShellCommand(command);long submitted=SystemClock.uptimeMillis();
        AtomicReference<byte[]> bytes=new AtomicReference<>();AtomicReference<Throwable> error=new AtomicReference<>();
        Thread reader=new Thread(()->{try(InputStream in=new ParcelFileDescriptor.AutoCloseInputStream(pipe);ByteArrayOutputStream output=new ByteArrayOutputStream()){byte[] buffer=new byte[512];int n;while((n=in.read(buffer))!=-1){if(output.size()+n>4096)throw new IllegalStateException("Bounded tap output exceeded");output.write(buffer,0,n);}bytes.set(output.toByteArray());}catch(Throwable failure){error.set(failure);}},"owned-backspace-tap-result");
        reader.setDaemon(true);reader.start();reader.join(15000);boolean complete=!reader.isAlive();
        if(!complete){uncertainShellTap=true;try{pipe.close();}catch(Exception ignored){}reader.join(1000);}
        String output=bytes.get()==null?"UNAVAILABLE":new String(bytes.get(),StandardCharsets.UTF_8);
        event("NORMAL_OS_TAP",new JSONObject().put("number",number).put("key_bounds",place.key.flattenToString()).put("command",command).put("submit_begin_ms",begin).put("pipe_return_ms",submitted).put("completion_ms",SystemClock.uptimeMillis()).put("completed_to_eof",complete&&error.get()==null).put("bounded_output",output).put("down_up_event_times","NOT_OBSERVED").put("delivery_span","NOT_OBSERVED").put("exit_status","NOT_EXPOSED_BY_EXECUTE_SHELL_COMMAND"));
        if(!complete||error.get()!=null){uncertainShellTap=true;throw new AssertionError("OS tap command completion uncertain; no duplicate tap or retry");}
        if(!output.isEmpty())throw new AssertionError("OS tap reported output; no retry; inspect retained command evidence");completedTapCommands++;
    }
    private void pointer(int action,float x,float y)throws Exception{
        if(action==MotionEvent.ACTION_DOWN){if(held)throw new AssertionError("No duplicate pointer DOWN");downTime=SystemClock.uptimeMillis();held=true;}
        if(!held)throw new AssertionError("No owned pointer stream");lastX=x;lastY=y;long eventTime=SystemClock.uptimeMillis(),begin=eventTime;MotionEvent event=motion(action,eventTime,x,y);
        try{test.sendPointerSync(event);}finally{event.recycle();}
        if(action==MotionEvent.ACTION_UP||action==MotionEvent.ACTION_CANCEL)held=false;
        event("SYNC_HOLD_POINTER",new JSONObject().put("motion_action",action).put("x_screen",x).put("y_screen",y).put("event_ms",eventTime).put("down_event_ms",downTime).put("submit_begin_ms",begin).put("submit_end_ms",SystemClock.uptimeMillis()));
    }
    private void idle()throws Exception{noAnr();auto.waitForIdle(150,5000);noAnr();}
    private Snapshot stopped()throws Exception{idle();Snapshot stable=snapshot();long until=SystemClock.elapsedRealtime()+Math.max(500,holdMillis+100);do{SystemClock.sleep(100);if(!stable.same(snapshot()))throw new AssertionError("Text/selection changed after settled release; retained failure evidence");}while(SystemClock.elapsedRealtime()<until);return stable;}
    private Snapshot awaitChanged(String before)throws Exception{long until=SystemClock.elapsedRealtime()+12000;do{Snapshot now=snapshot();if(!now.text.equals(before))return now;SystemClock.sleep(80);}while(SystemClock.elapsedRealtime()<until);throw new AssertionError("Hold did not produce actual host deletion within bound");}
    private boolean hint()throws Exception{return ownedNode(HINT,false,IME,AccessibilityWindowInfo.TYPE_INPUT_METHOD)!=null;}
    private void awaitHint(boolean visible)throws Exception{long until=SystemClock.elapsedRealtime()+10000;do{if(hint()==visible)return;SystemClock.sleep(100);}while(SystemClock.elapsedRealtime()<until);throw new AssertionError("Actual delete preview visibility did not reach "+visible);}
    private void screenshot(String name)throws Exception{LayoutChecks.screenshot(context,runName+"/"+name);screenshots.put(name);persist(null);}
    private float targetY(Place place){float target=place.key.exactCenterY()-dp(88);if(target<place.window.top+dp(4))throw new AssertionError("PRECONDITION_TARGET: insufficient actual visible space above key for64dp slide; no guessed off-window gesture");return target;}
    private Snapshot highlight(String initial,Place place)throws Exception{
        pointer(MotionEvent.ACTION_DOWN,place.key.exactCenterX(),place.key.exactCenterY());awaitChanged(initial);
        pointer(MotionEvent.ACTION_MOVE,place.key.exactCenterX(),targetY(place));awaitHint(true);Snapshot state=snapshot();
        SystemClock.sleep(300);check(state.same(snapshot()),"visible bulk preview pauses repeat with exact host text/selection stable");return state;
    }
    private static String prefix(){StringBuilder s=new StringBuilder();for(int i=0;i<32;i++)s.append("safe-prefix-");return s.toString();}
    private void cases()throws Exception{
        stage("whole-unicode-single");String unicode="A👩🏽‍💻";fixture(unicode,unicode.length());normalTap(deletePlace(),1);awaitState("A",1,15000);stopped();check(true,"one complete normal OS tap removes modified ZWJ emoji once");
        stage("four-normal-taps");fixture("abcdefghTAIL",8);for(int i=1;i<=4;i++)normalTap(deletePlace(),i);awaitState("abcdTAIL",4,15000);stopped();check(true,"four completed normal OS taps delete exactly four characters without ongoing repeat; no rapid-burst claim");
        stage("real-time-hold-release");String tail="|TAIL🧮",start=prefix()+tail;fixture(start,prefix().length());Place hold=deletePlace();pointer(MotionEvent.ACTION_DOWN,hold.key.exactCenterX(),hold.key.exactCenterY());awaitChanged(start);SystemClock.sleep(160);pointer(MotionEvent.ACTION_UP,hold.key.exactCenterX(),hold.key.exactCenterY());Snapshot released=stopped();check(!released.text.equals(start)&&released.text.endsWith(tail)&&released.start>0&&released.start==released.end,"actual hold deletes a nonempty proper prefix part and settled UP stops ongoing repeat");
        stage("bulk-release-and-exact-undo");fixture(start,prefix().length());Place bulk=deletePlace();Snapshot atHighlight=highlight(start,bulk);check(atHighlight.start==atHighlight.end&&atHighlight.text.substring(atHighlight.start).equals(tail),"highlight snapshot retains independently known suffix");screenshot("bulk-highlight.png");pointer(MotionEvent.ACTION_UP,bulk.key.exactCenterX(),targetY(bulk));awaitState(tail,0,15000);awaitHint(false);awaitNode(UNDO,true,IME,AccessibilityWindowInfo.TYPE_INPUT_METHOD,10000);screenshot("bulk-deleted.png");CaseModeChecks.click(UNDO,true);awaitState(atHighlight.text,atHighlight.start,15000);check(stopped().same(atHighlight),"native undo restores exactly highlight-time text/caret, preserving suffix");
        stage("move-back-cancels-bulk");fixture(start,prefix().length());Place back=deletePlace();highlight(start,back);pointer(MotionEvent.ACTION_MOVE,back.key.exactCenterX(),back.key.exactCenterY());pointer(MotionEvent.ACTION_UP,back.key.exactCenterX(),back.key.exactCenterY());awaitHint(false);Snapshot afterBack=stopped();check(afterBack.text.endsWith(tail)&&afterBack.start>0&&ownedNode(UNDO,true,IME,AccessibilityWindowInfo.TYPE_INPUT_METHOD)==null,"return to center cancels bulk; remaining prefix and suffix survive without bulk undo");
        stage("cancel-highlighted-stream");fixture(start,prefix().length());Place cancel=deletePlace();Snapshot beforeCancel=highlight(start,cancel);pointer(MotionEvent.ACTION_CANCEL,cancel.key.exactCenterX(),targetY(cancel));awaitHint(false);check(stopped().same(beforeCancel)&&ownedNode(UNDO,true,IME,AccessibilityWindowInfo.TYPE_INPUT_METHOD)==null,"ACTION_CANCEL hides preview and performs no bulk edit");
        stage("host-text-change-while-highlighted");fixture(start,prefix().length());Place textRace=deletePlace();highlight(start,textRace);AccessibilityNodeInfo node=editor();Bundle args=new Bundle();String replacement="NEW👩🏽‍💻|SAFE";args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,replacement);boolean accepted=node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT,args);event("HOST_REPLACEMENT_DURING_HOLD",new JSONObject().put("accepted",accepted));if(!accepted)throw new AssertionError("Owned host replacement refused; not retried");awaitState(replacement,-1,15000);selection(3,3);awaitState(replacement,3,15000);pointer(MotionEvent.ACTION_UP,textRace.key.exactCenterX(),targetY(textRace));check(stopped().same(awaitState(replacement,3,15000)),"stale held gesture does not delete replacement host text or move its new caret");
        stage("host-caret-change-while-highlighted");fixture(start,prefix().length());Place caretRace=deletePlace();Snapshot beforeCaret=highlight(start,caretRace);selection(2,2);awaitState(beforeCaret.text,2,15000);pointer(MotionEvent.ACTION_UP,caretRace.key.exactCenterX(),targetY(caretRace));check(stopped().same(awaitState(beforeCaret.text,2,15000)),"stale highlighted caret rejects old bulk request without touching same host text");
        screenshot("final-native-state.png");
    }
    private void cleanup()throws Exception{
        if(!held)return;
        try{noAnr();if(ownedNode(DELETE,true,IME,AccessibilityWindowInfo.TYPE_INPUT_METHOD)!=null&&ownedNode(EDITOR,true,HOST,AccessibilityWindowInfo.TYPE_APPLICATION)!=null){pointer(MotionEvent.ACTION_CANCEL,lastX,lastY);event("CLEANUP",new JSONObject().put("cancel","one owned cancellation sent"));}else event("CLEANUP",new JSONObject().put("cancel","NOT_SENT_UNSAFE_WINDOW; pointer outcome must be inspected"));}
        catch(Throwable failure){event("CLEANUP",new JSONObject().put("cancel","NOT_CONFIRMED; no Wait/retry/policy change"));}
    }
    private void rebuildCase()throws Exception{
        stage("stationary-pointer-through-candidate-rebuild");fixture("",0);
        if(ownedNode("英",false,IME,AccessibilityWindowInfo.TYPE_INPUT_METHOD)!=null){CaseModeChecks.click("英",false);awaitNode("中",false,IME,AccessibilityWindowInfo.TYPE_INPUT_METHOD,15000);}
        test.sendStringSync("ni");awaitNode("ni",false,IME,AccessibilityWindowInfo.TYPE_INPUT_METHOD,15000);awaitState("",0,15000);awaitNode("展开候选框",true,IME,AccessibilityWindowInfo.TYPE_INPUT_METHOD,15000);CaseModeChecks.click("展开候选框",true);awaitNode("收回候选框",true,IME,AccessibilityWindowInfo.TYPE_INPUT_METHOD,15000);
        Place original=deletePlace();float x=original.key.exactCenterX(),y=original.key.exactCenterY();pointer(MotionEvent.ACTION_DOWN,x,y);awaitState("",0,20000);
        awaitNode("光标与文本编辑",true,IME,AccessibilityWindowInfo.TYPE_INPUT_METHOD,15000);Place rebuilt=deletePlace();
        event("STATIONARY_LAYOUT_CHANGE",new JSONObject().put("original_key_screen_bounds",original.key.flattenToString()).put("rebuilt_key_screen_bounds",rebuilt.key.flattenToString()).put("original_window_bounds",original.window.flattenToString()).put("rebuilt_window_bounds",rebuilt.window.flattenToString()).put("pointer_screen_x",x).put("pointer_screen_y",y));
        pointer(MotionEvent.ACTION_MOVE,x,y);awaitHint(false);pointer(MotionEvent.ACTION_UP,x,y);awaitState("",0,15000);check(stopped().text.isEmpty(),"stationary OS pointer survives candidate-to-letter rebuild without bulk, duplicate child click or Enter");screenshot("stationary-rebuilt-keyboard.png");
        if(ownedNode("中",false,IME,AccessibilityWindowInfo.TYPE_INPUT_METHOD)!=null)CaseModeChecks.click("中",false);
    }
    private static String execute(Instrumentation test,boolean rebuildOnly)throws Exception{
        BackspaceUiChecks suite=new BackspaceUiChecks(test);
        try{suite.setup();if(rebuildOnly)suite.rebuildCase();else suite.cases();suite.status="PASS";suite.persist(null);return "PASS "+suite.checks+" actual OS Backspace "+(rebuildOnly?"stationary-rebuild":"core-UI")+" checks\n"+suite.log+"Evidence test-evidence/"+suite.runName+"/results.json; generated separate-UID editor only. Normal tap command timing recorded; delivery span/rapid burst not observed. "+(rebuildOnly?"Core UI scenarios not included in this entry.":"Stationary candidate rebuild is a separate runRebuild entry and NOT_RUN here.")+" Real phone and ASR NOT_RUN.\n";}
        catch(Throwable failure){suite.status="FAIL_OR_PRECONDITION_BLOCKED";try{suite.screenshot("failure.png");}catch(Throwable unavailable){/* preserve primary failure */}try{suite.persist(failure.getClass().getSimpleName()+": "+failure.getMessage());}catch(Throwable unavailable){/* primary failure remains */}throw new AssertionError("BackspaceUiChecks stage="+suite.stage+" evidence=test-evidence/"+suite.runName+" (no Wait/retry); "+failure.getMessage(),failure);}
        finally{try{suite.cleanup();suite.persist(null);}catch(Throwable unavailable){/* original verdict remains; evidence has earlier context */}}
    }
    public static String run(Instrumentation test)throws Exception{return execute(test,false);}
    public static String runRebuild(Instrumentation test)throws Exception{return execute(test,true);}
}
