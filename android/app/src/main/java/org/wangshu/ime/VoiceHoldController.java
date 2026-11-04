package org.wangshu.ime;

/** Original, UI-thread confined hold-to-talk protocol. No microphone or editor API is used here. */
public final class VoiceHoldController {
    public enum Phase { IDLE, ARMED, HOLDING, WAITING_RESULT, UNAVAILABLE }
    public enum Target { CENTER, LEFT_DISCARD, RIGHT_ACTION }
    public enum RightAction { NEWLINE, KEEP_TEXT, CUSTOM }
    public enum CancelReason { ACTION_CANCEL, INPUT_CHANGED, WINDOW_CHANGED, OTHER_INPUT, MULTI_POINTER, POINTER_LOST, PRE_HOLD_MOVED, LATE_HOLD_TIMER, START_REJECTED, RECOGNIZER_FAILURE, USER_ABORT, EMPTY_RESULT }
    public interface Handle {void cancel();}
    /** Schedule asynchronously on this controller's owner/UI thread using a monotonic clock. */
    public interface Scheduler {Handle after(long delayMillis,Runnable action);}
    public interface Host {
        /** Return false without opening a permission UI or falling back to a remote service. */
        boolean beginVoice(Token token);
        void finishVoice(Token token);
        void cancelVoice(Token token,CancelReason reason);
        /** Ordinary existing Space behavior, including candidate confirmation when applicable. */
        void tapSpace(long inputSession);
        /** Exactly one release-only completion. Adapter must still validate its editor/session scope. */
        void commitPreview(Token token,String text,Completion completion);
        /** IME preview only in this version. It never authorizes deleting earlier host text. */
        void discardPreview(Token token,CancelReason reason);
        void update(State state);
        void failed(Token token,String message);
    }
    public static final class Token {
        public final long inputSession,gesture;
        private Token(long input,long id){inputSession=input;gesture=id;}
        @Override public boolean equals(Object other){return other instanceof Token&&((Token)other).inputSession==inputSession&&((Token)other).gesture==gesture;}
        @Override public int hashCode(){return (int)(inputSession^(inputSession>>>32)^gesture^(gesture>>>32));}
    }
    /** Coordinates are in one stable touch-surface coordinate system, not movement directions. */
    public static final class Region {
        public final float left,top,right,bottom;
        public Region(float l,float t,float r,float b){if(!finite(l)||!finite(t)||!finite(r)||!finite(b)||r<=l||b<=t)throw new IllegalArgumentException("Invalid target rectangle");left=l;top=t;right=r;bottom=b;}
        boolean contains(float x,float y){return x>=left&&x<right&&y>=top&&y<bottom;}
        boolean overlaps(Region other){return left<other.right&&other.left<right&&top<other.bottom&&other.top<bottom;}
    }
    public static final class Completion {
        public final Target releasedAt;public final RightAction rightAction;public final String customAction;
        Completion(Target target,RightAction action,String custom){releasedAt=target;rightAction=action;customAction=custom;}
        public boolean appendNewline(){return releasedAt==Target.RIGHT_ACTION&&rightAction==RightAction.NEWLINE;}
    }
    public static final class State {
        public final Phase phase;public final Token token;public final Target highlighted;
        public final String preview,status,rightLabel;public final boolean finalResult;
        State(Phase p,Token t,Target target,String text,String message,String right,boolean ready){phase=p;token=t;highlighted=target;preview=text;status=message;rightLabel=right;finalResult=ready;}
        public boolean showVoiceLayer(){return phase==Phase.HOLDING||phase==Phase.WAITING_RESULT;}
    }
    private final Host host;private final Scheduler scheduler;private final Thread owner=Thread.currentThread();
    private final long holdMillis;private final float slopSquared;
    private boolean bound;private long inputSession,sequence,downAt,lastEventAt;private int pointer;
    private float downX,downY,x,y;private Phase phase=Phase.IDLE;private Token token;
    private Region left,right;private Target target=Target.CENTER;private Handle timer;
    private boolean engineAttempted,finished,finalReady;private String preview="",status="";
    private RightAction configuredAction=RightAction.NEWLINE,gestureAction=RightAction.NEWLINE;
    private String configuredLabel="换行",gestureLabel="换行",configuredCustom="",gestureCustom="";
    private Completion completion;
    public VoiceHoldController(Host value,Scheduler timing,long thresholdMillis,float touchSlop){if(value==null||timing==null||thresholdMillis<1||!finite(touchSlop)||touchSlop<0)throw new IllegalArgumentException("Invalid hold configuration");host=value;scheduler=timing;holdMillis=thresholdMillis;slopSquared=touchSlop*touchSlop;}
    private void thread(){if(Thread.currentThread()!=owner)throw new IllegalStateException("Voice hold callbacks must run on the owner/UI thread");}
    private static boolean finite(float value){return !Float.isNaN(value)&&!Float.isInfinite(value);}
    private boolean current(Token value){return value!=null&&value.equals(token)&&bound&&value.inputSession==inputSession;}
    public void bindInputSession(long value){thread();if(bound&&inputSession==value)return;inputSession=value;bound=true;cancel(CancelReason.INPUT_CHANGED);}
    public void clearInputSession(){thread();bound=false;cancel(CancelReason.INPUT_CHANGED);}
    public void rightAction(RightAction action,String label,String customKey){thread();if(action==null||label==null||label.trim().isEmpty()||(action==RightAction.CUSTOM&&(customKey==null||customKey.isEmpty())))throw new IllegalArgumentException("Invalid right action");configuredAction=action;configuredLabel=label;configuredCustom=customKey==null?"":customKey;}
    public State state(){thread();return new State(phase,token,target,preview,status,gestureLabel,finalReady);}
    public boolean busy(){thread();return phase!=Phase.IDLE;}
    private void publish(){host.update(state());}
    public Token down(int id,float px,float py,long at){
        thread();if(!bound||phase!=Phase.IDLE||!finite(px)||!finite(py))return null;
        token=new Token(inputSession,++sequence);pointer=id;downX=x=px;downY=y=py;downAt=lastEventAt=at;phase=Phase.ARMED;
        gestureAction=configuredAction;gestureLabel=configuredLabel;gestureCustom=configuredCustom;target=Target.CENTER;left=right=null;preview=status="";finalReady=engineAttempted=finished=false;completion=null;
        final Token owned=token;timer=scheduler.after(holdMillis,()->activate(owned));publish();return owned;
    }
    private void activate(Token owned){
        thread();if(!current(owned)||phase!=Phase.ARMED)return;timer=null;phase=Phase.HOLDING;engineAttempted=true;status="正在准备本地语音…";publish();if(!current(owned)||phase!=Phase.HOLDING)return;
        boolean started;try{started=host.beginVoice(owned);}catch(RuntimeException failure){started=false;}
        if(current(owned)&&!started){engineAttempted=false;phase=Phase.UNAVAILABLE;status="本地语音暂不可用，未请求权限或转云";host.cancelVoice(owned,CancelReason.START_REJECTED);if(current(owned)){publish();if(current(owned))host.failed(owned,status);}}
    }
    public void move(Token owned,int id,float px,float py,long at){
        thread();if(!current(owned)||id!=pointer)return;if(!finite(px)||!finite(py)||at<lastEventAt){cancel(CancelReason.POINTER_LOST);return;}lastEventAt=at;x=px;y=py;
        if(phase==Phase.ARMED){float dx=x-downX,dy=y-downY;if(dx*dx+dy*dy>slopSquared)cancel(CancelReason.PRE_HOLD_MOVED);}
        else if(phase==Phase.HOLDING)hover();
    }
    public boolean targets(Token owned,Region discard,Region action){
        thread();if(!current(owned)||phase!=Phase.HOLDING)return false;
        if(discard==null||action==null||discard.overlaps(action)){boolean changed=left!=null||right!=null||target!=Target.CENTER;left=right=null;target=Target.CENTER;if(changed)publish();return false;}left=discard;right=action;hover();return true;
    }
    private Target hit(float px,float py){if(left!=null&&left.contains(px,py))return Target.LEFT_DISCARD;if(right!=null&&right.contains(px,py))return Target.RIGHT_ACTION;return Target.CENTER;}
    private void hover(){Target next=hit(x,y);if(next!=target){target=next;publish();}}
    public void up(Token owned,int id,float px,float py,long at){
        thread();if(!current(owned)||id!=pointer)return;if(!finite(px)||!finite(py)||at<lastEventAt){cancel(CancelReason.POINTER_LOST);return;}x=px;y=py;lastEventAt=at;
        if(phase==Phase.ARMED){float dx=x-downX,dy=y-downY;if(dx*dx+dy*dy>slopSquared){cancel(CancelReason.PRE_HOLD_MOVED);return;}if(at-downAt>=holdMillis){cancel(CancelReason.LATE_HOLD_TIMER);return;}long input=owned.inputSession;reset();if(bound&&inputSession==input)host.tapSpace(input);publish();return;}
        if(phase==Phase.UNAVAILABLE){reset();publish();return;}if(phase!=Phase.HOLDING)return;
        target=hit(x,y);if(target==Target.LEFT_DISCARD){cancel(CancelReason.USER_ABORT);return;}
        completion=new Completion(target,gestureAction,gestureCustom);phase=Phase.WAITING_RESULT;publish();if(!current(owned))return;
        if(!finished){finished=true;host.finishVoice(owned);}if(current(owned)&&finalReady)complete(owned);
    }
    public void additionalPointer(Token owned){thread();if(current(owned))cancel(CancelReason.MULTI_POINTER);}
    public void partial(Token owned,String text){thread();if(!current(owned)||(phase!=Phase.HOLDING&&phase!=Phase.WAITING_RESULT)||finalReady)return;preview=text==null?"":text;publish();}
    public void engineState(Token owned,String message){thread();if(!current(owned)||(phase!=Phase.HOLDING&&phase!=Phase.WAITING_RESULT)||finalReady)return;status=message==null?"":message;publish();}
    public void result(Token owned,String text){thread();if(!current(owned)||(phase!=Phase.HOLDING&&phase!=Phase.WAITING_RESULT)||finalReady)return;finalReady=true;preview=text==null?"":text;if(phase==Phase.WAITING_RESULT)complete(owned);else publish();}
    public void failure(Token owned,String message){thread();if(!current(owned)||(phase!=Phase.HOLDING&&phase!=Phase.WAITING_RESULT))return;String safe=message==null?"本地语音未完成":message;cancel(CancelReason.RECOGNIZER_FAILURE);if(token==null&&bound&&owned.inputSession==inputSession)host.failed(owned,safe);}
    private void complete(Token owned){if(!current(owned)||phase!=Phase.WAITING_RESULT||!finalReady)return;if(preview.isEmpty()&&completion.releasedAt!=Target.RIGHT_ACTION){cancel(CancelReason.EMPTY_RESULT);return;}String text=preview;Completion action=completion;reset();if(bound&&owned.inputSession==inputSession)host.commitPreview(owned,text,action);publish();}
    public void cancel(Token owned,CancelReason reason){thread();if(current(owned))cancel(reason);}
    public void cancel(CancelReason reason){thread();Token old=token;boolean engine=engineAttempted;reset();if(old!=null){if(engine)host.cancelVoice(old,reason);host.discardPreview(old,reason);}publish();}
    private void reset(){if(timer!=null){timer.cancel();timer=null;}token=null;phase=Phase.IDLE;left=right=null;target=Target.CENTER;preview=status="";completion=null;finalReady=engineAttempted=finished=false;}
}
