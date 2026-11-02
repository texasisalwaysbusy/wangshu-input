package org.wangshu.ime;

import android.os.*;
import android.view.inputmethod.InputConnection;
import java.util.concurrent.*;

/** Local bounded provider controller. Reading/evaluation runs away from the keyboard UI thread. */
final class MathAssociation implements AutoCloseable {
    interface Host {
        boolean allowed();
        int session();
        InputConnection connection();
        /** Read only this captured connection; do not access mutable views from the worker. */
        SplitSelection.Snapshot read(InputConnection connection);
        void show(Display display);
        boolean insert(String value);
        void unavailable();
    }
    interface Provider {ContextMathCandidates.Result suggest(String context,String expression);}
    static final class Display {
        final long generation;final int session;final InputConnection connection;
        final SplitSelection.Snapshot before;final ContextMathCandidates.Candidate candidate;
        Display(long generation,int session,InputConnection connection,SplitSelection.Snapshot before,ContextMathCandidates.Candidate candidate){this.generation=generation;this.session=session;this.connection=connection;this.before=before;this.candidate=candidate;}
    }
    private final Host host;private final Provider provider;
    private final Handler handler=new Handler(Looper.getMainLooper());
    private final ThreadPoolExecutor worker=new ThreadPoolExecutor(1,1,0,TimeUnit.MILLISECONDS,new ArrayBlockingQueue<>(1),r->{Thread thread=new Thread(r,"local-math-candidate");thread.setDaemon(true);return thread;},new ThreadPoolExecutor.DiscardOldestPolicy());
    private volatile long generation;private volatile boolean closed;private Runnable debounce,timeout;private Future<?> running;private Display visible;
    MathAssociation(Host host){this(host,ContextMathCandidates::suggest);}
    MathAssociation(Host host,Provider provider){this.host=host;this.provider=provider;}
    private void main(){if(Looper.myLooper()!=Looper.getMainLooper())throw new IllegalStateException("Main thread required");}
    private boolean current(long id,int session,InputConnection connection){return !closed&&id==generation&&host.allowed()&&host.session()==session&&host.connection()==connection;}
    // Worker-only cancellation fence. Host eligibility belongs to the main thread;
    // cancellation stops later stages, but cannot interrupt an already-started IPC.
    private boolean workerCancelled(long id){return closed||id!=generation||Thread.currentThread().isInterrupted();}
    void clear(){main();generation++;if(debounce!=null)handler.removeCallbacks(debounce);if(timeout!=null)handler.removeCallbacks(timeout);if(running!=null)running.cancel(true);debounce=timeout=null;running=null;visible=null;host.show(null);}
    void request(){
        main();clear();if(closed||!host.allowed())return;
        final int session=host.session();final InputConnection connection=host.connection();if(connection==null)return;final long id=generation;
        debounce=()->{
            if(!current(id,session,connection))return;
            final long deadline=SystemClock.elapsedRealtime()+1000;timeout=()->{if(id==generation){clear();}};handler.postDelayed(timeout,1000);
            running=worker.submit(()->{
                SplitSelection.Snapshot before=null;ContextMathCandidates.Result result=null;
                try{
                    if(workerCancelled(id))return;
                    before=host.read(connection);
                    if(workerCancelled(id))return;
                    String[] query=query(before);
                    if(query!=null){
                        if(workerCancelled(id))return;
                        result=provider.suggest("",query[1]);
                        if(workerCancelled(id))return;
                        if(result.status!=ContextMathCandidates.Status.READY){
                            result=provider.suggest(query[0],query[1]);
                            if(workerCancelled(id))return;
                        }
                    }
                    if(result!=null&&result.status==ContextMathCandidates.Status.READY){
                        if(workerCancelled(id))return;
                        SplitSelection.Snapshot after=host.read(connection);
                        if(workerCancelled(id))return;
                        if(!WangshuIme.sameVoiceSnapshot(before,after))result=null;
                    }
                }catch(RuntimeException refused){before=null;result=null;/* No input logging or retry. Typing remains available. */}
                // Do not clear shared main-thread fields: a replacement request may own them.
                if(workerCancelled(id))return;
                final SplitSelection.Snapshot captured=before;final ContextMathCandidates.Result answer=result;
                handler.post(()->{
                    if(!current(id,session,connection))return;if(SystemClock.elapsedRealtime()>deadline){clear();return;}
                    if(timeout!=null)handler.removeCallbacks(timeout);timeout=null;running=null;
                    if(answer==null||answer.status!=ContextMathCandidates.Status.READY||answer.candidate.text.length()>256){visible=null;host.show(null);return;}
                    visible=new Display(id,session,connection,captured,answer.candidate);host.show(visible);
                });
            });
        };handler.postDelayed(debounce,120);
    }
    /** Each line/semicolon ends a context statement; the active final expression must end in '='. */
    static String[] query(SplitSelection.Snapshot before){
        if(before==null||before.text==null||before.start!=before.end||before.start<1||before.start>before.text.length())return null;
        int split=Math.max(before.text.lastIndexOf('\n',before.start-1),Math.max(before.text.lastIndexOf(';',before.start-1),before.text.lastIndexOf('；',before.start-1)));
        int from=split+1;if(before.start-from>ContextMathCandidates.MAX_EXPRESSION_UTF16)return null;
        String expression=before.text.substring(from,before.start).trim();if(!expression.endsWith("=")||expression.length()<2)return null;
        int contextEnd=Math.max(0,split),contextStart=Math.max(0,contextEnd-ContextMathCandidates.MAX_CONTEXT_UTF16);
        if(contextStart>0){int boundary=before.text.indexOf('\n',contextStart);if(boundary<0||boundary>=contextEnd)return null;contextStart=boundary+1;}
        return new String[]{before.text.substring(contextStart,contextEnd).replace('；',';'),expression};
    }
    void accept(Display selected){
        main();if(selected==null||selected!=visible||!current(selected.generation,selected.session,selected.connection)){host.unavailable();request();return;}
        SplitSelection.Snapshot now;try{now=host.read(selected.connection);}catch(RuntimeException unreadable){now=null;}
        if(!current(selected.generation,selected.session,selected.connection)||!WangshuIme.sameVoiceSnapshot(selected.before,now)){host.unavailable();request();return;}
        String value=selected.candidate.text;long acceptedEpoch=generation+1;clear();SplitSelection.Snapshot afterHide;try{afterHide=host.read(selected.connection);}catch(RuntimeException unreadable){afterHide=null;}if(!current(acceptedEpoch,selected.session,selected.connection)||!WangshuIme.sameVoiceSnapshot(selected.before,afterHide)){host.unavailable();request();return;}boolean accepted;try{accepted=host.insert(value);}catch(RuntimeException uncertain){accepted=false;}if(!accepted)host.unavailable();
    }
    @Override public void close(){main();clear();closed=true;worker.shutdownNow();}
}
