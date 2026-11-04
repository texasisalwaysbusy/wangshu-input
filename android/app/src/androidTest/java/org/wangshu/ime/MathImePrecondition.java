package org.wangshu.ime;

import android.app.*;
import android.content.Context;
import android.graphics.Rect;
import android.os.*;
import android.view.*;
import android.view.accessibility.AccessibilityWindowInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import java.util.List;
import java.util.concurrent.*;
import java.util.function.IntSupplier;

/** Test-owned editor only. One show request, optional one explicitly logged OS tap,
 * then both real IME insets and a strict registered-keyboard node are required.
 * No forced show, repeated show, settings change or post-action recovery. */
final class MathImePrecondition {
    private static final String SPACE="空格；按住本地语音，松手结束";
    private static final class State {
        boolean attached,shown,enabled,focused,windowFocused,active,accepting,alive,insetsKnown,imeVisible;
        final Rect full=new Rect(),visible=new Rect();int softInputMode,imeBottom;Object token;
        boolean ready(){return alive&&attached&&shown&&enabled&&focused&&windowFocused&&active&&accepting&&token!=null&&visible.width()>=24&&visible.height()>=24;}
        public String toString(){return "attached="+attached+" shown="+shown+" enabled="+enabled+" focus="+focused+" windowFocus="+windowFocused+" active="+active+" accepting="+accepting+" alive="+alive+" full="+full.toShortString()+" visible="+visible.toShortString()+" softInputMode=0x"+Integer.toHexString(softInputMode)+" insetsKnown="+insetsKnown+" imeVisible="+imeVisible+" imeBottom="+imeBottom;}
    }
    // Pure policy functions are exercised on the host with explicit synthetic inputs.
    static boolean recoveryAllowed(int actions,int attempts,boolean ready,boolean insetsKnown,boolean imeVisible,boolean guardComplete,boolean sawIme){
        return actions==0&&attempts==0&&ready&&insetsKnown&&!imeVisible&&guardComplete&&!sawIme;
    }
    static boolean shortTapDelivered(boolean downAck,boolean upAck,long downTime,long upTime,long downDelivered,long upDelivered,long threshold,boolean cancelled){
        return downAck&&upAck&&!cancelled&&downTime>=0&&upTime>=downTime&&downDelivered>=0&&upDelivered>=downDelivered&&upTime-downTime<threshold&&upDelivered-downDelivered<threshold;
    }
    private static void main(Runnable task)throws Exception{
        CountDownLatch done=new CountDownLatch(1);Throwable[] failure={null};
        boolean posted=new Handler(Looper.getMainLooper()).post(()->{try{task.run();}catch(Throwable error){failure[0]=error;}finally{done.countDown();}});
        if(!posted||!done.await(3,TimeUnit.SECONDS))throw new AssertionError("Math precondition main-thread observation/request not acknowledged; no retry");
        if(failure[0] instanceof Error)throw (Error)failure[0];
        if(failure[0]!=null)throw new IllegalStateException("Math precondition main-thread failure",failure[0]);
    }
    /** Test-only capture: global visible bounds are root-relative, not screen-relative. */
    static void captureVisibleScreen(View editor,Rect full,Rect visible){
        int[] where=new int[2];editor.getLocationOnScreen(where);
        full.set(where[0],where[1],where[0]+editor.getWidth(),where[1]+editor.getHeight());
        if(!editor.getGlobalVisibleRect(visible)){visible.setEmpty();return;}
        // Add the actual root origin, including split/floating-window offsets.
        // Do not use globalOffset: that also includes the editor's own scroll.
        editor.getRootView().getLocationOnScreen(where);visible.offset(where[0],where[1]);
        if(!visible.intersect(full))visible.setEmpty();
    }
    private static State snapshot(Activity owner,EditText editor)throws Exception{
        State state=new State();main(()->{
            state.alive=!owner.isFinishing()&&!owner.isDestroyed();state.attached=editor.isAttachedToWindow();state.shown=editor.isShown();state.enabled=editor.isEnabled();state.focused=editor.hasFocus();state.windowFocused=editor.hasWindowFocus();state.token=editor.getWindowToken();
            captureVisibleScreen(editor,state.full,state.visible);state.softInputMode=owner.getWindow().getAttributes().softInputMode;
            InputMethodManager manager=(InputMethodManager)owner.getSystemService(Context.INPUT_METHOD_SERVICE);state.active=manager.isActive(editor);state.accepting=manager.isAcceptingText();
            if(Build.VERSION.SDK_INT>=30){WindowInsets insets=editor.getRootWindowInsets();state.insetsKnown=insets!=null;if(insets!=null){state.imeVisible=insets.isVisible(WindowInsets.Type.ime());state.imeBottom=insets.getInsets(WindowInsets.Type.ime()).bottom;}}
        });return state;
    }
    private static String windows(UiAutomation auto){
        List<AccessibilityWindowInfo> windows=auto.getWindows();if(windows.size()>16)throw new AssertionError("Math precondition window-count limit; no tap");StringBuilder result=new StringBuilder();
        for(AccessibilityWindowInfo window:windows)result.append("[id=").append(window.getId()).append(" type=").append(window.getType()).append(" active=").append(window.isActive()).append(" focused=").append(window.isFocused()).append(']');return result.toString();
    }
    private static final class Observation {
        State state;StrictImeWindowQuery.Result query;
        boolean visible(){return state.ready()&&query.node!=null&&(!state.insetsKnown||state.imeVisible);}
    }
    private static Observation observe(Activity owner,EditText editor,UiAutomation auto,StringBuilder log,String phase,long duration)throws Exception{
        long end=SystemClock.elapsedRealtime()+duration;String last="";Observation result;
        do{
            result=new Observation();result.query=StrictImeWindowQuery.find(auto,AccessibilityWindowInfo.TYPE_INPUT_METHOD,"org.wangshu.ime",SPACE,true);result.state=snapshot(owner,editor);
            String detail=result.state+" windows="+windows(auto)+" query="+result.query;
            if(!detail.equals(last)){log.append("IME_STAGE ").append(phase).append(' ').append(detail).append('\n');last=detail;}
            if(result.visible())return result;SystemClock.sleep(150);
        }while(SystemClock.elapsedRealtime()<end);return result;
    }
    private static final class TouchTrace implements View.OnTouchListener {
        long down=-1,up=-1,downDelivered=-1,upDelivered=-1;boolean cancelled;int events;final StringBuilder detail=new StringBuilder();
        public boolean onTouch(View view,MotionEvent event){
            int action=event.getActionMasked();long delivered=SystemClock.uptimeMillis();
            if(action==MotionEvent.ACTION_DOWN){down=event.getEventTime();downDelivered=delivered;}
            if(action==MotionEvent.ACTION_UP){up=event.getEventTime();upDelivered=delivered;}
            if(action==MotionEvent.ACTION_CANCEL)cancelled=true;
            if(events++<24)detail.append("EDITOR_TOUCH action=").append(MotionEvent.actionToString(action)).append(" downTime=").append(event.getDownTime()).append(" eventTime=").append(event.getEventTime()).append(" delivered=").append(delivered).append(" pointers=").append(event.getPointerCount()).append(" x=").append(event.getX()).append(" y=").append(event.getY()).append(" windowFocus=").append(view.hasWindowFocus()).append('\n');
            return false;
        }
    }
    private static boolean inject(UiAutomation auto,long down,int action,int x,int y){
        MotionEvent event=MotionEvent.obtain(down,SystemClock.uptimeMillis(),action,x,y,0);event.setSource(InputDevice.SOURCE_TOUCHSCREEN);try{return auto.injectInputEvent(event,true);}finally{event.recycle();}
    }
    static void ensure(Instrumentation test,Activity owner,EditText editor,UiAutomation auto,StringBuilder log,IntSupplier nativeActions)throws Exception{
        main(()->{editor.requestFocus();editor.requestRectangleOnScreen(new Rect(0,0,editor.getWidth(),editor.getHeight()),true);});
        State ready=snapshot(owner,editor);long end=SystemClock.elapsedRealtime()+12000;
        while(!ready.ready()&&SystemClock.elapsedRealtime()<end){SystemClock.sleep(100);ready=snapshot(owner,editor);}
        log.append("IME_STAGE before_single_show ").append(ready).append(" windows=").append(windows(auto)).append('\n');
        if(!ready.ready())throw new AssertionError("Owned editor is not visibly focused/served; no show or tap sent");
        final boolean[] show={false};main(()->show[0]=((InputMethodManager)owner.getSystemService(Context.INPUT_METHOD_SERVICE)).showSoftInput(editor,InputMethodManager.SHOW_IMPLICIT));
        log.append("IME_SHOW_IMPLICIT requests=1 acknowledged=").append(show[0]).append("; acknowledgement is not visibility\n");StrictImeWindowQuery.beginObservation(auto);
        Observation first=observe(owner,editor,auto,log,"after_single_show",6000);if(first.visible())return;
        log.append("INITIAL_SHOW_PRECONDITION_UNMET; no keyboard action sent; optional distinct editor-tap recovery follows only if safe\n");
        // Re-read the system guard and editor immediately before considering the
        // one recovery. A null root/unknown insets is not evidence of absence.
        StrictImeWindowQuery.Result guarded=StrictImeWindowQuery.find(auto,AccessibilityWindowInfo.TYPE_INPUT_METHOD,"org.wangshu.ime",SPACE,true);State before=snapshot(owner,editor);
        if(!recoveryAllowed(nativeActions.getAsInt(),0,before.ready(),before.insetsKnown,before.imeVisible,guarded.systemGuardComplete,guarded.requestedWindowSeen))throw new AssertionError("Initial IME visibility precondition failed; recovery not eligible; "+before+" query="+guarded);
        TouchTrace trace=new TouchTrace();Throwable primary=null;boolean[] acks={false,false};int x=before.visible.centerX(),y=before.visible.centerY();long started=SystemClock.uptimeMillis();
        log.append("RECOVERY_EDITOR_TAP attempts=1 nativeActionsBefore=").append(nativeActions.getAsInt()).append(" actualVisibleBounds=").append(before.visible.toShortString()).append(" point=").append(x).append(',').append(y).append("; no repeated show or FORCED\n");
        try{
            main(()->editor.setOnTouchListener(trace));State latest=snapshot(owner,editor);
            if(!latest.ready()||latest.token!=before.token||!latest.visible.equals(before.visible)||nativeActions.getAsInt()!=0)throw new AssertionError("Owned editor changed before tap; no DOWN sent");
            long down=SystemClock.uptimeMillis();
            // One UP also releases a possibly delivered DOWN when its ACK is
            // false/throws. It is never a second tap or an action retry.
            Throwable downFailure=null;
            try{acks[0]=inject(auto,down,MotionEvent.ACTION_DOWN,x,y);}catch(RuntimeException|AssertionError failure){downFailure=failure;throw failure;}
            finally{try{acks[1]=inject(auto,down,MotionEvent.ACTION_UP,x,y);}catch(RuntimeException|AssertionError upFailure){if(downFailure!=null)downFailure.addSuppressed(upFailure);else throw upFailure;}}
            long deliveredEnd=SystemClock.elapsedRealtime()+2500;while(SystemClock.elapsedRealtime()<deliveredEnd){main(()->{});if(trace.up>=0||trace.cancelled)break;SystemClock.sleep(50);}
            if(!shortTapDelivered(acks[0],acks[1],trace.down,trace.up,trace.downDelivered,trace.upDelivered,ViewConfiguration.getLongPressTimeout(),trace.cancelled))throw new AssertionError("The single recovery editor tap was not acknowledged/delivered as a complete short tap; no keyboard action sent or retry");
        }catch(Exception|AssertionError failure){primary=failure;throw failure;}
        finally{
            try{main(()->{log.append("RECOVERY_EDITOR_TAP_RESULT down_ack=").append(acks[0]).append(" up_ack=").append(acks[1]).append(" elapsedMs=").append(SystemClock.uptimeMillis()-started).append("\n").append(trace.detail);editor.setOnTouchListener(null);});}catch(Exception|AssertionError cleanup){if(primary!=null)primary.addSuppressed(cleanup);else throw cleanup;}
        }
        Observation recovered=observe(owner,editor,auto,log,"after_distinct_single_editor_tap",12000);
        if(!recovered.visible())throw new AssertionError("IME still not visibly ready after the distinct single editor tap; no keyboard action sent");
        log.append("IME_PRECONDITION_RECOVERED_BY_SINGLE_EDITOR_TAP; initial show miss retained; visibility is not an arithmetic acceptance\n");
    }
}
