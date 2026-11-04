package org.wangshu.ime;

import android.os.*;
import android.view.inputmethod.InputConnection;
import java.util.*;
import java.util.concurrent.*;

/** Optional local continuations. Saved phrases keep priority; original finite local examples are separately enabled. */
final class PersonalPhraseAssociation implements AutoCloseable {
    static final class PhraseData {final long revision;final List<String> phrases;PhraseData(long revision,List<String> phrases){this.revision=revision;this.phrases=Collections.unmodifiableList(new ArrayList<>(phrases));}}
    static final class Choice {final String suffix,label;final boolean builtin;Choice(String value,String source,boolean local){suffix=value;label=source;builtin=local;}}
    static final class Display {final long generation,revision;final int session;final InputConnection owner;final SplitSelection.Snapshot snapshot;final PersonalPhraseSuggestions.Language language;final String suffix;final List<Choice> choices;
        Display(long g,long r,int s,InputConnection o,SplitSelection.Snapshot snap,PersonalPhraseSuggestions.Language l,String value){generation=g;revision=r;session=s;owner=o;snapshot=snap;language=l;suffix=value;choices=Collections.singletonList(new Choice(value,"常用语续写",false));}
        Display(long g,long r,int s,InputConnection o,SplitSelection.Snapshot snap,PersonalPhraseSuggestions.Language l,List<Choice> values){generation=g;revision=r;session=s;owner=o;snapshot=snap;language=l;choices=Collections.unmodifiableList(new ArrayList<>(values));suffix=choices.get(0).suffix;}}
    interface Host {boolean allowed();int session();InputConnection connection();PersonalPhraseSuggestions.Language language();SplitSelection.Snapshot read(InputConnection owner);PhraseData phrases()throws Exception;long revision();void show(Display d);boolean insert(String text);void unavailable();default boolean savedEnabled(){return true;}default boolean localEnabled(){return false;}default List<Choice> localSuggestions(String before,String after,PersonalPhraseSuggestions.Language language){return Collections.emptyList();}default boolean retract(InputConnection owner,int session,int utf16){return false;}}
    private final Host host;private final Handler handler=new Handler(Looper.getMainLooper());
    private final ThreadPoolExecutor worker=new ThreadPoolExecutor(1,1,0,TimeUnit.MILLISECONDS,new ArrayBlockingQueue<>(1),r->{Thread t=new Thread(r,"personal-phrase-candidate");t.setDaemon(true);return t;},new ThreadPoolExecutor.DiscardOldestPolicy());
    private volatile long generation;private volatile boolean closed;private Runnable debounce,timeout;private Future<?> running;private Display visible;private Undo undo;private boolean editing;
    private static final class Undo {final int session;final InputConnection owner;final PersonalPhraseSuggestions.Language language;final SplitSelection.Snapshot before,after;final int units;Undo(Display display,SplitSelection.Snapshot result,int length){session=display.session;owner=display.owner;language=display.language;before=display.snapshot;after=result;units=length;}}
    PersonalPhraseAssociation(Host host){this.host=host;}
    private void main(){if(Looper.myLooper()!=Looper.getMainLooper())throw new IllegalStateException("Main thread required");}
    private boolean sameEditor(int session,InputConnection owner,PersonalPhraseSuggestions.Language language){
        if(closed||owner==null||!host.allowed()||host.session()!=session||host.language()!=language)return false;
        InputConnection current=host.connection();
        // Acquiring the editor can synchronously invalidate the old session.
        return current==owner&&!closed&&host.allowed()&&host.session()==session&&host.language()==language;
    }
    private boolean current(long id,int session,InputConnection owner,PersonalPhraseSuggestions.Language language){return !closed&&id==generation&&sameEditor(session,owner,language)&&!closed&&id==generation;}
    // Worker-only cancellation fence. Host eligibility belongs to the main thread;
    // cancellation stops later stages, but cannot interrupt an already-started IPC.
    private boolean workerCancelled(long id){return closed||id!=generation||Thread.currentThread().isInterrupted();}
    void clear(){cancel(true);}
    private void cancel(boolean dropUndo){main();if(dropUndo)undo=null;generation++;if(debounce!=null)handler.removeCallbacks(debounce);if(timeout!=null)handler.removeCallbacks(timeout);if(running!=null)running.cancel(true);debounce=timeout=null;running=null;visible=null;host.show(null);}
    void request(){
        main();cancel(false);if(closed||!host.allowed())return;boolean savedEnabled=host.savedEnabled(),localEnabled=host.localEnabled();int session=host.session();InputConnection owner=host.connection();PersonalPhraseSuggestions.Language language=host.language();if(owner==null||language==null)return;long id=generation;
        debounce=()->{if(!current(id,session,owner,language))return;long deadline=SystemClock.elapsedRealtime()+1000;timeout=()->{if(id==generation)cancel(false);};handler.postDelayed(timeout,1000);
            running=worker.submit(()->{SplitSelection.Snapshot before=null;PhraseData data=null;String suffix=null;List<Choice> choices=new ArrayList<>();
                try{
                    if(workerCancelled(id))return;
                    before=host.read(owner);
                    if(workerCancelled(id))return;
                    if(before!=null&&before.start==before.end){
                        data=savedEnabled?host.phrases():new PhraseData(0,Collections.emptyList());
                        if(workerCancelled(id))return;
                        if(data!=null&&data.revision>=0){
                            String left=before.text.substring(0,before.start),right=before.text.substring(before.end);
                            suffix=savedEnabled?PersonalPhraseSuggestions.suggest(left,right,language,data.phrases):null;
                            if(suffix!=null)choices.add(new Choice(suffix,"常用语续写",false));
                            if(workerCancelled(id))return;
                            if(localEnabled){List<Choice> local=host.localSuggestions(left,right,language);if(local!=null)for(Choice choice:local){if(choices.size()>=5)break;if(choice!=null&&choice.builtin&&choice.suffix!=null&&!choice.suffix.isEmpty()&&choice.suffix.length()<=128&&choices.stream().noneMatch(old->old.suffix.equals(choice.suffix)))choices.add(choice);}}
                            suffix=choices.isEmpty()?null:choices.get(0).suffix;
                            if(workerCancelled(id))return;
                            if(suffix!=null){
                                SplitSelection.Snapshot after=host.read(owner);
                                if(workerCancelled(id))return;
                                if(!WangshuIme.sameVoiceSnapshot(before,after)){suffix=null;choices.clear();}
                            }
                        }
                    }
                }catch(Exception refused){before=null;data=null;suffix=null;choices.clear();}
                // Do not clear shared main-thread fields: a replacement request may own them.
                if(workerCancelled(id))return;
                final SplitSelection.Snapshot captured=before;final PhraseData saved=data;final String result=suffix;final List<Choice> results=new ArrayList<>(choices);
                handler.post(()->{if(!current(id,session,owner,language)||savedEnabled!=host.savedEnabled()||localEnabled!=host.localEnabled())return;if(SystemClock.elapsedRealtime()>deadline){cancel(false);return;}if(timeout!=null)handler.removeCallbacks(timeout);timeout=null;running=null;
                    if(result==null||saved==null||saved.revision!=(savedEnabled?host.revision():0)){visible=null;host.show(null);return;}visible=new Display(id,saved.revision,session,owner,captured,language,results);host.show(visible);});
            });};handler.postDelayed(debounce,120);
    }
    boolean hasUndo(){return undo!=null&&!closed;}
    private boolean revision(Display selected,Choice choice){return choice.builtin?host.localEnabled():host.savedEnabled()&&host.revision()==selected.revision;}
    private boolean selectionCurrent(Display selected,Choice choice,long id){
        return current(id,selected.session,selected.owner,selected.language)&&revision(selected,choice)&&current(id,selected.session,selected.owner,selected.language);
    }
    void accept(Display selected){accept(selected,selected==null?null:selected.choices.get(0));}
    void accept(Display selected,Choice choice){
        main();if(editing||selected==null||choice==null||selected!=visible||!selected.choices.contains(choice)||!selectionCurrent(selected,choice,selected.generation)){host.unavailable();return;}
        editing=true;try{
            SplitSelection.Snapshot now;try{now=host.read(selected.owner);}catch(RuntimeException refused){now=null;}
            if(!selectionCurrent(selected,choice,selected.generation)||!WangshuIme.sameVoiceSnapshot(selected.snapshot,now)){host.unavailable();cancel(false);return;}
            long next=generation+1;undo=null;cancel(false);
            // Hiding the old UI can synchronously change the editor/session.
            if(!selectionCurrent(selected,choice,next)){host.unavailable();return;}
            try{now=host.read(selected.owner);}catch(RuntimeException refused){now=null;}
            if(!selectionCurrent(selected,choice,next)||!WangshuIme.sameVoiceSnapshot(selected.snapshot,now)){host.unavailable();return;}
            boolean accepted;try{accepted=host.insert(choice.suffix);}catch(RuntimeException uncertain){accepted=false;}
            if(!accepted){host.unavailable();return;}
            // An acknowledged insert is not enough for Undo. Retain a token only
            // after exact text/caret readback in the same editor and input session.
            if(!sameEditor(selected.session,selected.owner,selected.language)){host.show(null);return;}
            SplitSelection.Snapshot after;try{after=host.read(selected.owner);}catch(RuntimeException refused){after=null;}
            if(sameEditor(selected.session,selected.owner,selected.language)&&inserted(selected.snapshot,after,choice.suffix))undo=new Undo(selected,after,choice.suffix.length());
            host.show(null);
        }finally{editing=false;}
    }
    private static boolean inserted(SplitSelection.Snapshot before,SplitSelection.Snapshot after,String suffix){
        if(before==null||after==null||before.start!=before.end||after.start!=before.start+suffix.length()||after.end!=after.start)return false;
        return after.text.equals(before.text.substring(0,before.start)+suffix+before.text.substring(before.end));
    }
    void undo(){
        main();if(editing)return;Undo saved=undo;undo=null;if(saved==null){host.unavailable();return;}
        editing=true;try{
            long id=generation;
            if(!current(id,saved.session,saved.owner,saved.language)){host.unavailable();host.show(null);return;}
            SplitSelection.Snapshot now;try{now=host.read(saved.owner);}catch(RuntimeException refused){now=null;}
            if(!current(id,saved.session,saved.owner,saved.language)||!WangshuIme.sameVoiceSnapshot(saved.after,now)){host.unavailable();host.show(null);return;}
            // Single targeted delete, never replace all matching text, poll, retry,
            // or roll back after an uncertain editor response.
            boolean accepted;try{accepted=host.retract(saved.owner,saved.session,saved.units);}catch(RuntimeException uncertain){accepted=false;}
            if(!sameEditor(saved.session,saved.owner,saved.language)){host.unavailable();host.show(null);return;}
            try{now=host.read(saved.owner);}catch(RuntimeException refused){now=null;}
            if(!accepted||!sameEditor(saved.session,saved.owner,saved.language)||!WangshuIme.sameVoiceSnapshot(saved.before,now))host.unavailable();
            host.show(null);
        }finally{editing=false;}
    }
    public void close(){main();clear();closed=true;worker.shutdownNow();}
}
