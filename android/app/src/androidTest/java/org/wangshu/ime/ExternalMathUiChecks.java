package org.wangshu.ime;

import android.accessibilityservice.AccessibilityServiceInfo;
import android.app.*;
import android.content.*;
import android.graphics.*;
import android.os.*;
import android.provider.Settings;
import android.view.*;
import android.view.accessibility.*;
import java.io.File;
import java.util.*;

/** Independent-UID, generated-only numeric association acceptance. This is a new
 * route, not a repair or a retry of the Settings-Activity mathui failure. */
final class ExternalMathUiChecks {
    private static final String IME="org.wangshu.ime",HOST="org.wangshu.scan.test",EDITOR="独立应用编辑器";
    private final Instrumentation test;private final Context context;private final UiAutomation auto;
    private final String tag="math-external-"+UUID.randomUUID();private final File directory;
    private final String probeSession=tag.substring("math-external-".length());private String probeInstance;private long probeRevision=-1,probeTextRevision=-1;
    private final StringBuilder log=new StringBuilder();private final Rect screen=new Rect();
    private final SharedPreferences preferences;private final TestBooleanPreferenceCheckpoint checkpoint;
    private int count,hostWindow=-1,pointers,hostStateReads,unknownSelectionReads;private String lastQuery="not observed",stage="checkpoint",lastHostState="not observed";
    interface Condition {boolean yes()throws Exception;}
    static final class State {
        final String text;final int start,end,window,rawUtf16,a11yStart,a11yEnd;final boolean hint;final FixtureEditorState evidence;
        State(AccessibilityNodeInfo node){this(node,null);}
        State(AccessibilityNodeInfo node,FixtureEditorState evidence){CharSequence raw=node.getText();hint=node.isShowingHintText();rawUtf16=raw==null?0:raw.length();text=hint?"":raw==null?"":raw.toString();a11yStart=node.getTextSelectionStart();a11yEnd=node.getTextSelectionEnd();this.evidence=evidence;start=evidence==null?a11yStart:evidence.start;end=evidence==null?a11yEnd:evidence.end;window=node.getWindowId();}
        boolean unknownSelection(){return start==-1&&end==-1;}
        boolean observable(){return text.length()<=2048&&start>=0&&end>=start&&end<=text.length();}
        boolean matches(String value,int caret){return observable()&&text.equals(value)&&start==caret&&end==caret;}
        boolean same(State value){return observable()&&value!=null&&value.observable()&&text.equals(value.text)&&start==value.start&&end==value.end&&window==value.window&&(evidence==null?value.evidence==null:evidence.same(value.evidence));}
        boolean matchesNode(AccessibilityNodeInfo node){State raw=new State(node);return text.equals(raw.text)&&window==raw.window&&(evidence==null?same(raw):FixtureEditorState.coherent(evidence,evidence,evidence.instance,raw.text.length(),raw.a11yStart,raw.a11yEnd));}
        public String toString(){return "window="+window+" logicalUtf16="+text.length()+" rawUtf16="+rawUtf16+" showingHint="+hint+" selection="+start+","+end+" a11ySelection="+a11yStart+","+a11yEnd+" source="+(evidence==null?"A11Y_UNVERIFIED":"FIXTURE_READONLY instance="+evidence.instance+" revision="+evidence.revision+" textRevision="+evidence.textRevision);}
    }
    private ExternalMathUiChecks(Instrumentation test)throws Exception{
        this.test=test;context=test.getTargetContext();auto=test.getUiAutomation();LayoutChecks.auto=auto;
        directory=new File(context.getFilesDir(),"test-evidence/"+tag);if(!directory.mkdirs())throw new AssertionError("Cannot create new external math evidence directory");
        preferences=AppPreferences.open(context,"associations");
        checkpoint=new TestBooleanPreferenceCheckpoint(preferences,new File(directory,"preferences-before.tsv"),"associations","math",null);
        log.append("EVIDENCE ").append(tag).append("/preferences-before.tsv\n");
        AccessibilityServiceInfo config=auto.getServiceInfo();config.flags|=AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS;auto.setServiceInfo(config);
    }
    private void phase(String value){stage=value;log.append("STAGE ").append(value).append('\n');Bundle update=new Bundle();update.putString("stream","MATH_EXTERNAL_STAGE "+value+" evidence="+tag+"\n");test.sendStatus(0,update);}
    private void check(boolean ok,String message){if(!ok)throw new AssertionError(message);count++;log.append("PASS ").append(message).append('\n');}
    private void await(Condition value,String label)throws Exception{
        StrictImeWindowQuery.beginObservation(auto);long until=SystemClock.elapsedRealtime()+20000;
        do{if(value.yes())return;SystemClock.sleep(150);}while(SystemClock.elapsedRealtime()<until);
        throw new AssertionError("External math precondition/state wait: "+label+"; "+lastQuery+"; hostStateReads="+hostStateReads+" unknownSelectionReads="+unknownSelectionReads+" lastHostState={"+lastHostState+"}; no action retry");
    }
    private AccessibilityNodeInfo node(int type,String owner,String label,boolean description){StrictImeWindowQuery.Result result=StrictImeWindowQuery.find(auto,type,owner,label,description);lastQuery=result.toString();return result.node;}
    private AccessibilityNodeInfo awaitIme(String label,boolean description)throws Exception{AccessibilityNodeInfo[] found={null};await(()->(found[0]=node(AccessibilityWindowInfo.TYPE_INPUT_METHOD,IME,label,description))!=null,label);return found[0];}
    static boolean ownedEditor(AccessibilityNodeInfo node,int expectedWindow){
        return node!=null&&(expectedWindow<0||node.getWindowId()==expectedWindow)&&HOST.contentEquals(node.getPackageName()==null?"":node.getPackageName())&&EDITOR.contentEquals(node.getContentDescription()==null?"":node.getContentDescription())&&"android.widget.EditText".contentEquals(node.getClassName()==null?"":node.getClassName())&&node.isEditable()&&node.isFocused()&&!node.isPassword()&&node.isVisibleToUser()&&node.isEnabled();
    }
    private AccessibilityNodeInfo editor(){
        AccessibilityNodeInfo value=node(AccessibilityWindowInfo.TYPE_APPLICATION,HOST,EDITOR,true);
        if(value==null)return null;int id=value.getWindowId();
        if(!value.refresh()||value.getWindowId()!=id||!ownedEditor(value,hostWindow))throw new AssertionError("Owned independent editor changed identity/focus; no edit or pointer sent");
        // A View may retain its focus flag while another window is modal. Bind
        // to the current focused APPLICATION descriptor as well as the leaf.
        List<AccessibilityWindowInfo> current=new ArrayList<>(auto.getWindows());if(current.size()>16)throw new AssertionError("Host window-count limit; no action sent");boolean focusedWindow=false;
        for(AccessibilityWindowInfo window:current)if(window.getId()==id){if(window.getType()!=AccessibilityWindowInfo.TYPE_APPLICATION||!window.isFocused())throw new AssertionError("Owned host window is no longer focused APPLICATION; no action sent");if(focusedWindow)throw new AssertionError("Duplicate host window; no action sent");focusedWindow=true;}
        if(!focusedWindow)throw new AssertionError("Owned host window disappeared; no action sent");
        if(hostWindow<0)hostWindow=id; // Lock identity even while selection is temporarily unreadable.
        return value;
    }
    private FixtureEditorState probeState(){
        AccessibilityNodeInfo value=node(AccessibilityWindowInfo.TYPE_APPLICATION,HOST,FixtureEditorState.LABEL,true);if(value==null)return null;
        if(!value.refresh()||value.getWindowId()!=hostWindow||!HOST.contentEquals(value.getPackageName()==null?"":value.getPackageName())||!FixtureEditorState.LABEL.contentEquals(value.getContentDescription()==null?"":value.getContentDescription())||!"android.widget.TextView".contentEquals(value.getClassName()==null?"":value.getClassName())||value.isEditable()||!value.isVisibleToUser()||!value.isEnabled())throw new AssertionError("Read-only fixture probe identity/window invalid; no action sent");
        CharSequence wire=value.getText();FixtureEditorState parsed;try{parsed=FixtureEditorState.parse(wire==null?null:wire.toString(),probeSession);}catch(IllegalArgumentException invalid){throw new AssertionError("Read-only fixture probe schema/session invalid; no action sent",invalid);}
        if(probeInstance==null)probeInstance=parsed.instance;
        if(!probeInstance.equals(parsed.instance)||parsed.revision<probeRevision||parsed.textRevision<probeTextRevision)throw new AssertionError("Read-only fixture instance/version changed backward; no action sent");probeRevision=parsed.revision;probeTextRevision=parsed.textRevision;return parsed;
    }
    private State observedState(){
        AccessibilityNodeInfo first=editor();State raw=first==null?null:new State(first),captured=null;String firstRaw=raw==null?"no owned node":raw.toString();boolean sawUnknown=raw!=null&&raw.unknownSelection();String bridge="no owned node";
        if(raw!=null){
            if(raw.text.length()>2048||(!raw.unknownSelection()&&!raw.observable()))throw new AssertionError("Malformed known host text/selection; no action sent; "+raw);
            FixtureEditorState before=probeState();AccessibilityNodeInfo value=editor();if(value!=null)raw=new State(value);FixtureEditorState after=probeState();
            if(value!=null&&(raw.text.length()>2048||(!raw.unknownSelection()&&!raw.observable())))throw new AssertionError("Malformed known host text/selection; no action sent; "+raw);
            if(before!=null&&before.same(after)&&after.ready()&&value!=null){
                if(!FixtureEditorState.coherent(before,after,probeInstance,raw.text.length(),raw.a11yStart,raw.a11yEnd))throw new AssertionError("Actual fixture probe and host text/known selection conflict; no action sent; "+raw);
                captured=new State(value,after);bridge="two matching fresh read-only widget snapshots";
            }else{bridge="read-only probe missing/not-ready/changed; no action";if(raw.unknownSelection())captured=raw;}
        }
        hostStateReads++;if(sawUnknown||(raw!=null&&raw.a11yStart==-1&&raw.a11yEnd==-1))unknownSelectionReads++;
        lastHostState=(captured==null?(raw==null?"no owned node":raw.toString()):captured.toString())+" firstNodeA11y={"+firstRaw+"} bridge="+bridge+" observedAtMs="+SystemClock.elapsedRealtime();
        if(hostStateReads<=16)log.append("HOST_OBSERVATION sample=").append(hostStateReads).append(' ').append(lastHostState).append("; read-only, no caret inference\n");return captured;
    }
    private State state(){State captured=observedState();if(captured!=null&&!captured.observable())throw new AssertionError("Owned fixture text/selection not safely observable; no edit or pointer sent; "+captured);return captured;}
    private State awaitState(String expected,int caret)throws Exception{
        State[] found={null};await(()->{
            found[0]=observedState();if(found[0]==null)return false;
            // Only the platform's explicit unknown pair can be observed again.
            // Never convert it to zero, set a cursor, or relax the action guard.
            if(found[0].text.length()>2048||(!found[0].unknownSelection()&&!found[0].observable()))throw new AssertionError("Malformed known host text/selection; no action sent; "+found[0]);
            return !found[0].unknownSelection()&&found[0].matches(expected,caret);
        },"exact generated host text and caret");return found[0];
    }
    private void requireState(State expected){State current=state();if(expected==null||!expected.same(current))throw new AssertionError("Owned fixture changed before the sole action; no action sent; expected="+expected+" actual="+current);}
    private static final class Place {final int window;final Rect bounds;Place(int window,Rect bounds){this.window=window;this.bounds=bounds;}}
    private Place place(AccessibilityNodeInfo initiallyBound,String label,boolean description){
        int id=initiallyBound.getWindowId();AccessibilityNodeInfo current=node(AccessibilityWindowInfo.TYPE_INPUT_METHOD,IME,label,description);
        if(current==null||current.getWindowId()!=id)throw new AssertionError("IME target migrated before pointer; no action sent");
        List<AccessibilityWindowInfo> windows=new ArrayList<>(auto.getWindows());if(windows.size()>16)throw new AssertionError("Window limit; no action sent");AccessibilityWindowInfo owner=null;
        for(AccessibilityWindowInfo window:windows)if(window.getId()==id){if(owner!=null)throw new AssertionError("Duplicate window id; no action sent");owner=window;}
        if(owner==null||owner.getType()!=AccessibilityWindowInfo.TYPE_INPUT_METHOD)throw new AssertionError("Current pointer window is not IME; no action sent");
        AccessibilityNodeInfo root=ImeSelectorDiagnostics.currentRoot(owner);
        if(root==null||!root.refresh()||root.getWindowId()!=id||!IME.contentEquals(root.getPackageName()==null?"":root.getPackageName()))throw new AssertionError("Current IME root unavailable/foreign; no action sent");
        if(!current.refresh())throw new AssertionError("Current pointer target stale; no action sent");CharSequence exact=description?current.getContentDescription():current.getText();Rect bounds=new Rect(),keyboard=new Rect();current.getBoundsInScreen(bounds);owner.getBoundsInScreen(keyboard);
        if(owner.getId()!=id||owner.getType()!=AccessibilityWindowInfo.TYPE_INPUT_METHOD||current.getWindowId()!=id||!IME.contentEquals(current.getPackageName()==null?"":current.getPackageName())||exact==null||!label.contentEquals(exact)||!"android.widget.Button".contentEquals(current.getClassName()==null?"":current.getClassName())||!current.isVisibleToUser()||!current.isEnabled()||!current.isClickable()||(current.getActions()&AccessibilityNodeInfo.ACTION_CLICK)==0||bounds.width()<1||bounds.height()<1||!keyboard.contains(bounds)||!screen.contains(bounds))throw new AssertionError("Pointer target identity/type/screen bounds changed; no action sent");
        log.append("POINTER_TARGET ").append(label).append(" window=").append(id).append(" type=").append(owner.getType()).append(" package=").append(current.getPackageName()).append(" screen=").append(bounds.toShortString()).append(" keyboard=").append(keyboard.toShortString()).append('\n');return new Place(id,bounds);
    }
    private static boolean inject(UiAutomation auto,long down,int action,Rect bounds){MotionEvent event=MotionEvent.obtain(down,SystemClock.uptimeMillis(),action,bounds.centerX(),bounds.centerY(),0);event.setSource(InputDevice.SOURCE_TOUCHSCREEN);try{return auto.injectInputEvent(event,false);}finally{event.recycle();}}
    static void pointerOnce(UiAutomation auto,Rect bounds,String label,StringBuilder log){
        long down=SystemClock.uptimeMillis();boolean[] ack={false,false};long[] up={-1};Throwable primary=null;
        try{
            try{ack[0]=inject(auto,down,MotionEvent.ACTION_DOWN,bounds);}catch(RuntimeException|AssertionError error){primary=error;throw error;}
            finally{up[0]=SystemClock.uptimeMillis();try{ack[1]=inject(auto,down,MotionEvent.ACTION_UP,bounds);}catch(RuntimeException|AssertionError release){if(primary!=null)primary.addSuppressed(release);else throw release;}}
            if(!ack[0]||!ack[1]||up[0]-down>=ViewConfiguration.getLongPressTimeout())throw new AssertionError("Single external-math pointer not acknowledged as short; no retry or fallback");
        }finally{log.append("POINTER ").append(label).append(" downAck=").append(ack[0]).append(" upAck=").append(ack[1]).append(" eventSpanMs=").append(up[0]<0?-1:up[0]-down).append(" callElapsedMs=").append(SystemClock.uptimeMillis()-down).append(" streams=1 repeated=false; host outcome checked separately\n");}
    }
    private void tap(String label,boolean description,State before)throws Exception{
        requireState(before);AccessibilityNodeInfo found=awaitIme(label,description);Place where=place(found,label,description);requireState(before);pointers++;pointerOnce(auto,where.bounds,label,log);
    }
    private State key(String label,boolean description,String before,String after)throws Exception{State initial=awaitState(before,before.length());tap(label,description,initial);return awaitState(after,after.length());}
    private void generatedText(String value)throws Exception{
        State before=state();AccessibilityNodeInfo target=editor();if(before==null||target==null||!before.matchesNode(target)||!before.same(state())||(target.getActions()&AccessibilityNodeInfo.ACTION_SET_TEXT)==0)throw new AssertionError("Owned generated-text state/action unavailable; no action sent");
        Bundle args=new Bundle();args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,value);boolean ack=target.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT,args);log.append("OWNED_SET_GENERATED_TEXT utf16=").append(value.length()).append(" window=").append(target.getWindowId()).append(" ack=").append(ack).append(" attempts=1\n");if(!ack)throw new AssertionError("Generated owned fixture text not acknowledged; no retry");awaitState(value,value.length());
    }
    private void selection(String expected,int caret)throws Exception{
        State initial=awaitState(expected,expected.length());AccessibilityNodeInfo target=editor();if(target==null||!initial.matchesNode(target)||!initial.same(state())||(target.getActions()&AccessibilityNodeInfo.ACTION_SET_SELECTION)==0)throw new AssertionError("Owned selection changed/unavailable; no action sent");
        Bundle args=new Bundle();args.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT,caret);args.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT,caret);boolean ack=target.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION,args);log.append("OWNED_SET_SELECTION caret=").append(caret).append(" window=").append(target.getWindowId()).append(" ack=").append(ack).append(" attempts=1\n");if(!ack)throw new AssertionError("Owned selection not acknowledged; no retry");awaitState(expected,caret);
    }
    private void stable(State expected)throws Exception{long end=SystemClock.elapsedRealtime()+1200;do{requireState(expected);SystemClock.sleep(150);}while(SystemClock.elapsedRealtime()<end);}
    private void screenshot(String name)throws Exception{LayoutChecks.screenshot(context,tag+"/"+name);}
    private String execute()throws Exception{
        Throwable primary=null;
        try{
            phase("independent-fixture-preconditions");check(preferences.edit().putBoolean("math",true).commit(),"temporary math scalar saved after exact UUID original-value checkpoint");
            String selected=Settings.Secure.getString(context.getContentResolver(),Settings.Secure.DEFAULT_INPUT_METHOD);check(selected!=null&&selected.startsWith(IME+"/"),"OS selected real Wangshu InputMethodService");
            context.startActivity(new Intent().setComponent(new ComponentName(HOST,HOST+".ImeEditorFixture")).putExtra(FixtureEditorState.EXTRA,probeSession).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TOP));
            State initial=awaitState("",0);hostWindow=initial.window;check(hostWindow>=0,"fresh owned editor is empty with observable collapsed caret before any generated edit");
            awaitIme("空格；按住本地语音，松手结束",true);
            // Query package metadata only after explicit launch/served-editor
            // interaction. If Android package visibility still denies it, stop
            // before edits rather than request wider package/security access.
            int hostUid=context.getPackageManager().getApplicationInfo(HOST,0).uid,imeUid=context.getApplicationInfo().uid;check(hostUid!=imeUid&&IME.equals(context.getPackageName()),"known ephemeral scanner test editor is a separate application UID");
            Bitmap bitmap=auto.takeScreenshot();if(bitmap==null)throw new AssertionError("Actual compositor bounds unavailable; no pointer sent");try{screen.set(0,0,bitmap.getWidth(),bitmap.getHeight());}finally{bitmap.recycle();}
            tap("123",false,initial);awaitIme("数字面板换行或编辑器动作",true);check(awaitState("",0).same(initial),"single native numeric-panel tap preserves independent editor text/selection");
            phase("observable-native-digit-insertion");key("1",false,"","1");check(true,"one actual native digit tap is observable as exactly one character and caret in separate UID");
            key("数字面板符号 +",true,"1","1+");key("2",false,"1+","1+2");key("数字面板符号 =",true,"1+2","1+2=");
            phase("visible-exact-candidate-selection");awaitIme("数学联想 精确结果 3",true);State sum=awaitState("1+2=",4);check(true,"numeric association is visible and has not automatically changed the generated expression");screenshot("arithmetic-before.png");tap("数学联想 精确结果 3",true,sum);State inserted=awaitState("1+2=3",5);stable(inserted);check(true,"one visible arithmetic candidate tap inserts exactly once via real cross-UID InputConnection");screenshot("arithmetic-after.png");
            phase("caret-before-unicode-suffix");String prefix="1/3+1/6=",suffix="后文𝕏👩🏽‍💻",whole=prefix+suffix;generatedText(whole);selection(whole,prefix.length());awaitIme("数学联想 精确结果 1/2",true);State fraction=awaitState(whole,prefix.length());check(true,"cursor context offers an exact fraction without altering either generated side");screenshot("fraction-before.png");tap("数学联想 精确结果 1/2",true,fraction);State safe=awaitState(prefix+"1/2"+suffix,prefix.length()+3);stable(safe);check(true,"candidate insertion preserves exact prefix and non-BMP/ZWJ suffix and collapses caret after result");screenshot("fraction-after.png");
            return "PASS "+count+" independent-UID numeric association UI checks\n"+log+"Pointer streams="+pointers+". Generated ephemeral fixture only; scalar journal/restoration required. New path does not fix the old Settings mathui timeout. No product-method invocation, fake candidate/provider/ASR, microphone, real-phone, universal app or broad CAS acceptance.\n";
        }catch(Throwable failure){
            primary=failure;TestCleanupSteps.run(checkpoint::restore).attachOrThrow(failure);log.append("FAIL stage=").append(stage).append(" after attempted scalar restore; first cause retained\n");
            try{log.append(ImeSelectorDiagnostics.snapshot(auto));}catch(Exception|AssertionError metadata){if(metadata!=failure)failure.addSuppressed(metadata);}
            try{screenshot("failure.png");}catch(Exception|AssertionError image){if(image!=failure)failure.addSuppressed(image);}
            AssertionError report=new AssertionError("ExternalMath UI partial="+count+"\n"+log,failure);primary=report;throw report;
        }finally{TestCleanupSteps.run(checkpoint::restore,()->{if(!checkpoint.matches())throw new AssertionError("Math scalar not restored exactly");},checkpoint::verifyUnrelated,checkpoint::recordRestored).attachOrThrow(primary);}
    }
    static String run(Instrumentation test)throws Exception{return new ExternalMathUiChecks(test).execute();}
}
