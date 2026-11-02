package org.wangshu.ime;

import android.os.*;
import android.view.inputmethod.InputConnection;
import java.util.*;
import java.util.concurrent.*;

/** Optional local continuation using only phrases the user explicitly saved. */
final class PersonalPhraseAssociation implements AutoCloseable {
    static final class PhraseData {final long revision;final List<String> phrases;PhraseData(long revision,List<String> phrases){this.revision=revision;this.phrases=Collections.unmodifiableList(new ArrayList<>(phrases));}}
    static final class Display {final long generation,revision;final int session;final InputConnection owner;final SplitSelection.Snapshot snapshot;final PersonalPhraseSuggestions.Language language;final String suffix;
        Display(long g,long r,int s,InputConnection o,SplitSelection.Snapshot snap,PersonalPhraseSuggestions.Language l,String value){generation=g;revision=r;session=s;owner=o;snapshot=snap;language=l;suffix=value;}}
    interface Host {boolean allowed();int session();InputConnection connection();PersonalPhraseSuggestions.Language language();SplitSelection.Snapshot read(InputConnection owner);PhraseData phrases()throws Exception;long revision();void show(Display d);boolean insert(String text);void unavailable();}
    private final Host host;private final Handler handler=new Handler(Looper.getMainLooper());
    private final ThreadPoolExecutor worker=new ThreadPoolExecutor(1,1,0,TimeUnit.MILLISECONDS,new ArrayBlockingQueue<>(1),r->{Thread t=new Thread(r,"personal-phrase-candidate");t.setDaemon(true);return t;},new ThreadPoolExecutor.DiscardOldestPolicy());
    private volatile long generation;private volatile boolean closed;private Runnable debounce,timeout;private Future<?> running;private Display visible;
    PersonalPhraseAssociation(Host host){this.host=host;}
    private void main(){if(Looper.myLooper()!=Looper.getMainLooper())throw new IllegalStateException("Main thread required");}
    private boolean current(long id,int session,InputConnection owner,PersonalPhraseSuggestions.Language language){return !closed&&id==generation&&host.allowed()&&host.session()==session&&host.connection()==owner&&host.language()==language;}
    // Worker-only cancellation fence. Host eligibility belongs to the main thread;
    // cancellation stops later stages, but cannot interrupt an already-started IPC.
    private boolean workerCancelled(long id){return closed||id!=generation||Thread.currentThread().isInterrupted();}
    void clear(){main();generation++;if(debounce!=null)handler.removeCallbacks(debounce);if(timeout!=null)handler.removeCallbacks(timeout);if(running!=null)running.cancel(true);debounce=timeout=null;running=null;visible=null;host.show(null);}
    void request(){
        main();clear();if(closed||!host.allowed())return;int session=host.session();InputConnection owner=host.connection();PersonalPhraseSuggestions.Language language=host.language();if(owner==null||language==null)return;long id=generation;
        debounce=()->{if(!current(id,session,owner,language))return;long deadline=SystemClock.elapsedRealtime()+1000;timeout=()->{if(id==generation)clear();};handler.postDelayed(timeout,1000);
            running=worker.submit(()->{SplitSelection.Snapshot before=null;PhraseData data=null;String suffix=null;
                try{
                    if(workerCancelled(id))return;
                    before=host.read(owner);
                    if(workerCancelled(id))return;
                    if(before!=null&&before.start==before.end){
                        data=host.phrases();
                        if(workerCancelled(id))return;
                        if(data!=null&&data.revision>=0){
                            suffix=PersonalPhraseSuggestions.suggest(before.text.substring(0,before.start),before.text.substring(before.end),language,data.phrases);
                            if(workerCancelled(id))return;
                            if(suffix!=null){
                                SplitSelection.Snapshot after=host.read(owner);
                                if(workerCancelled(id))return;
                                if(!WangshuIme.sameVoiceSnapshot(before,after))suffix=null;
                            }
                        }
                    }
                }catch(Exception refused){before=null;data=null;suffix=null;}
                // Do not clear shared main-thread fields: a replacement request may own them.
                if(workerCancelled(id))return;
                final SplitSelection.Snapshot captured=before;final PhraseData saved=data;final String result=suffix;
                handler.post(()->{if(!current(id,session,owner,language))return;if(SystemClock.elapsedRealtime()>deadline){clear();return;}if(timeout!=null)handler.removeCallbacks(timeout);timeout=null;running=null;
                    if(result==null||saved==null||saved.revision!=host.revision()){visible=null;host.show(null);return;}visible=new Display(id,saved.revision,session,owner,captured,language,result);host.show(visible);});
            });};handler.postDelayed(debounce,120);
    }
    void accept(Display selected){
        main();if(selected==null||selected!=visible||!current(selected.generation,selected.session,selected.owner,selected.language)||host.revision()!=selected.revision){host.unavailable();return;}
        SplitSelection.Snapshot now;try{now=host.read(selected.owner);}catch(RuntimeException refused){now=null;}
        if(!current(selected.generation,selected.session,selected.owner,selected.language)||host.revision()!=selected.revision||!WangshuIme.sameVoiceSnapshot(selected.snapshot,now)){host.unavailable();clear();return;}
        long next=generation+1;clear();try{now=host.read(selected.owner);}catch(RuntimeException refused){now=null;}
        if(!current(next,selected.session,selected.owner,selected.language)||host.revision()!=selected.revision||!WangshuIme.sameVoiceSnapshot(selected.snapshot,now)){host.unavailable();return;}
        boolean accepted;try{accepted=host.insert(selected.suffix);}catch(RuntimeException uncertain){accepted=false;}if(!accepted)host.unavailable();
    }
    public void close(){main();clear();closed=true;worker.shutdownNow();}
}
