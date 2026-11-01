package org.wangshu.ime;

/** Original owner-thread state machine. Host deletes one visible grapheme, never UTF-16 units. */
public final class BackspaceGesture {
    public interface Handle { void cancel(); }
    public interface Scheduler { Handle after(long milliseconds,Runnable action); }
    public interface Host {
        DeleteResult deleteOne(long session);
        /** Validate unchanged editor/session and a collapsed, bounded snapshot before deleting. */
        boolean deleteBeforeCaret(long session);
        void preview(boolean armed);
    }
    public static final class DeleteResult {
        public final boolean deleted,newline;
        public DeleteResult(boolean changed,boolean line){deleted=changed;newline=line;}
    }
    public enum Phase { IDLE, ARMED, REPEATING, BULK_PREVIEW }
    private final Host host; private final Scheduler scheduler;
    private final long holdMillis; private final float upwardThreshold,sideSlop,touchSlopSquared;
    private final Thread owner=Thread.currentThread();
    private long session,generation,timerGeneration; private int pointer,steps,slowChars;
    private boolean bound,deleted; private float downX,downY; private Phase phase=Phase.IDLE;
    private boolean previewActive;private long previewOwner=-1;
    private Handle pending;
    public BackspaceGesture(Host host,Scheduler scheduler,long holdMillis,float upwardThreshold,float sideSlop){
        this(host,scheduler,holdMillis,upwardThreshold,sideSlop,Math.min(8,Math.min(upwardThreshold,sideSlop)));
    }
    public BackspaceGesture(Host host,Scheduler scheduler,long holdMillis,float upwardThreshold,float sideSlop,float touchSlop){
        if(host==null||scheduler==null||holdMillis<1||!finite(upwardThreshold)||upwardThreshold<=0||!finite(sideSlop)||sideSlop<=0||!finite(touchSlop)||touchSlop<0)throw new IllegalArgumentException();
        this.host=host;this.scheduler=scheduler;this.holdMillis=holdMillis;this.upwardThreshold=upwardThreshold;this.sideSlop=sideSlop;touchSlopSquared=touchSlop*touchSlop;
    }
    private void thread(){if(Thread.currentThread()!=owner)throw new IllegalStateException("Owner thread required");}
    private static boolean finite(float f){return !Float.isNaN(f)&&!Float.isInfinite(f);}
    public Phase phase(){thread();return phase;}
    public void bind(long value){thread();if(bound&&session==value)return;session=value;bound=true;cancel();}
    public void unbind(){thread();bound=false;cancel();}
    public long down(int id,float x,float y){
        thread();if(!bound||phase!=Phase.IDLE||!finite(x)||!finite(y))return -1;
        pointer=id;downX=x;downY=y;steps=slowChars=0;deleted=false;phase=Phase.ARMED;long token=++generation;
        schedule(holdMillis,token);return token;
    }
    private boolean current(long token,int id){return bound&&token==generation&&pointer==id&&phase!=Phase.IDLE;}
    private void stopTimer(){timerGeneration++;if(pending!=null){pending.cancel();pending=null;}}
    private void schedule(long milliseconds,long token){stopTimer();long timer=timerGeneration;pending=scheduler.after(milliseconds,()->repeat(token,timer));}
    private long delay(){return slowChars>0?220:Math.max(45,220-steps*25);}
    private void repeat(long token,long timer){
        thread();if(!bound||token!=generation||timer!=timerGeneration||(phase!=Phase.ARMED&&phase!=Phase.REPEATING))return;
        timerGeneration++;pending=null;phase=Phase.REPEATING;
        DeleteResult result=host.deleteOne(session);
        if(!bound||token!=generation||phase!=Phase.REPEATING)return;
        if(result==null||!result.deleted){cancel();return;}deleted=true;
        if(result.newline){slowChars=2;steps=0;}else if(slowChars>0){slowChars--;if(slowChars==0)steps=1;}else steps++;
        schedule(delay(),token);
    }
    private boolean target(float x,float y){return finite(x)&&finite(y)&&Math.abs(x-downX)<=sideSlop&&y<=downY-upwardThreshold;}
    private void clearPreview(long owner){if(previewActive&&previewOwner==owner){previewActive=false;previewOwner=-1;host.preview(false);}}
    public void move(long token,int id,float x,float y){
        thread();if(!current(token,id))return;
        if(!finite(x)||!finite(y)){cancel();return;}
        if(phase==Phase.ARMED){float dx=x-downX,dy=y-downY;if(dx*dx+dy*dy>touchSlopSquared)cancel();return;}
        if(target(x,y)){
            if(phase!=Phase.BULK_PREVIEW){stopTimer();phase=Phase.BULK_PREVIEW;previewActive=true;previewOwner=token;host.preview(true);}
        }else if(phase==Phase.BULK_PREVIEW){
            phase=Phase.REPEATING;clearPreview(token);if(current(token,id))schedule(220,token);
        }
    }
    public void up(long token,int id,float x,float y){
        thread();if(!current(token,id))return;
        float dx=x-downX,dy=y-downY;Phase finalPhase=phase;boolean bulk=finalPhase==Phase.BULK_PREVIEW&&target(x,y),tap=finalPhase==Phase.ARMED&&finite(x)&&finite(y)&&dx*dx+dy*dy<=touchSlopSquared;
        long input=session;
        if(bulk){
            // Preserve the host's preview snapshot until its one release action.
            // Invalidate timers first; only then clear the preview and its text.
            stopTimer();phase=Phase.IDLE;generation++;
            try{if(bound&&session==input)host.deleteBeforeCaret(input);}finally{clearPreview(token);}
            return;
        }
        cancel();if(bound&&session==input&&tap&&!deleted)host.deleteOne(input);
    }
    public void cancel(long token){thread();if(token==generation)cancel();}
    public void cancel(){thread();stopTimer();long owner=previewOwner;phase=Phase.IDLE;generation++;clearPreview(owner);}
}
