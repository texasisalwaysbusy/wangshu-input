package org.wangshu.ime;

import android.os.*;
import android.view.inputmethod.InputConnection;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;

/** Optional local continuations. Saved phrases keep priority; original finite local examples are separately enabled. */
final class PersonalPhraseAssociation implements AutoCloseable {
    static final class PhraseData {final long revision;final List<String> phrases;PhraseData(long revision,List<String> phrases){this.revision=revision;this.phrases=Collections.unmodifiableList(new ArrayList<>(phrases));}}
    static final class Choice {final String suffix,label;final boolean builtin;Choice(String value,String source,boolean local){suffix=value;label=source;builtin=local;}}
    static final class Display {final long generation,revision;final int session;final InputConnection owner;final SplitSelection.Snapshot snapshot;final PersonalPhraseSuggestions.Language language;final String suffix;final List<Choice> choices;final boolean numericPanel,fromMovement;
        Display(long g,long r,int s,InputConnection o,SplitSelection.Snapshot snap,PersonalPhraseSuggestions.Language l,List<Choice> values,boolean numeric,boolean movement){generation=g;revision=r;session=s;owner=o;snapshot=snap;language=l;choices=Collections.unmodifiableList(new ArrayList<>(values));suffix=choices.get(0).suffix;numericPanel=numeric;fromMovement=movement;}}
    interface Host {
        boolean allowed();
        int session();
        InputConnection connection();
        PersonalPhraseSuggestions.Language language();
        SplitSelection.Snapshot read(InputConnection owner);
        PhraseData phrases()throws Exception;
        long revision();
        void show(Display display);
        boolean insert(InputConnection owner,int session,SplitSelection.Snapshot expected,
                       BooleanSupplier current,String text);
        void unavailable();
        default boolean savedEnabled(){return true;}
        default boolean localEnabled(){return false;}
        default boolean numericPanel(){return false;}
        default boolean cursorEnabled(){return true;}
        default List<Choice> localSuggestions(String before,String after,
                PersonalPhraseSuggestions.Language language,boolean numericPanel){return Collections.emptyList();}
        default boolean retract(InputConnection owner,int session,SplitSelection.Snapshot expected,
                                BooleanSupplier current,int utf16){return false;}
    }
    private final Host host;private final Handler handler=new Handler(Looper.getMainLooper());
    private final ThreadPoolExecutor worker=new ThreadPoolExecutor(1,1,0,TimeUnit.MILLISECONDS,new ArrayBlockingQueue<>(1),r->{Thread t=new Thread(r,"personal-phrase-candidate");t.setDaemon(true);return t;},new ThreadPoolExecutor.DiscardOldestPolicy());
    private volatile long generation;private volatile boolean closed;private Runnable debounce,timeout;private Future<?> running;private Display visible;private Undo undo;private boolean editing;
    // A single in-memory baseline distinguishes a selection notification from a text edit.
    private String observedText;
    private InputConnection observedOwner;
    private int observedSession;
    private boolean selectionPending, selectionUnsettled, acknowledgedEdit;
    private static final class Undo {final Object ticket=new Object();final int session;final InputConnection owner;final PersonalPhraseSuggestions.Language language;final SplitSelection.Snapshot before,after;final int units;Undo(Display display,SplitSelection.Snapshot result,int length){session=display.session;owner=display.owner;language=display.language;before=display.snapshot;after=result;units=length;}}
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
    void clear(){observedText=null;observedOwner=null;selectionPending=selectionUnsettled=acknowledgedEdit=false;cancel(true);}
    private void cancel(boolean dropUndo){main();if(dropUndo)undo=null;generation++;if(debounce!=null)handler.removeCallbacks(debounce);if(timeout!=null)handler.removeCallbacks(timeout);if(running!=null)running.cancel(true);debounce=timeout=null;running=null;visible=null;host.show(null);}
    void selectionChanged(int start,int end,boolean composing,boolean moved){
        main();
        boolean unsettled=start<0||start!=end||composing;
        if(!moved&&!unsettled&&!selectionUnsettled)return;
        selectionUnsettled=unsettled;selectionPending=true;
        if(undo!=null&&(undo.after.start!=start||undo.after.end!=end||composing))undo=null;
        if(selectionUnsettled){cancel(true);return;}
        request(start);
    }
    void textEdited(){main();selectionPending=selectionUnsettled=false;acknowledgedEdit=true;request();}
    void request(){request(-1);}
    private void request(int notifiedCaret){
        main();long id=generation+1;cancel(false);
        if(generation!=id)return;
        if(closed||!host.allowed()){
            observedText=null;observedOwner=null;acknowledgedEdit=false;
            return;
        }
        if(selectionUnsettled)return;
        boolean savedEnabled=host.savedEnabled(),localEnabled=host.localEnabled(),numericPanel=host.numericPanel();
        boolean selectionRequest=selectionPending,cursorEnabled=host.cursorEnabled(),compare=selectionRequest&&!cursorEnabled;
        int session=host.session();InputConnection owner=host.connection();PersonalPhraseSuggestions.Language language=host.language();
        if(owner==null||language==null||generation!=id)return;
        String prior=observedOwner==owner&&observedSession==session?observedText:null;
        boolean editWithoutBaseline=acknowledgedEdit&&prior==null;
        debounce=()->{if(!current(id,session,owner,language)||numericPanel!=host.numericPanel()||
                (selectionRequest&&cursorEnabled!=host.cursorEnabled()))return;long deadline=SystemClock.elapsedRealtime()+1000;timeout=()->{if(id==generation)cancel(false);};handler.postDelayed(timeout,1000);
            running=worker.submit(()->{SplitSelection.Snapshot before=null;PhraseData data=null;String suffix=null;List<Choice> choices=new ArrayList<>();
                try{
                    if(workerCancelled(id))return;
                    before=host.read(owner);
                    if(workerCancelled(id))return;
                    if(before!=null&&(before.start!=before.end||(notifiedCaret>=0&&before.start!=notifiedCaret)))before=null;
                    if(before!=null&&(!compare||editWithoutBaseline||(prior!=null&&!prior.equals(before.text)))){
                        data=savedEnabled?host.phrases():new PhraseData(0,Collections.emptyList());
                        if(workerCancelled(id))return;
                        if(data!=null&&data.revision>=0){
                            String left=before.text.substring(0,before.start),right=before.text.substring(before.end);
                            suffix=savedEnabled?PersonalPhraseSuggestions.suggest(left,right,language,data.phrases):null;
                            if(suffix!=null)choices.add(new Choice(suffix,"常用语续写",false));
                            if(workerCancelled(id))return;
                            if(localEnabled){List<Choice> local=host.localSuggestions(left,right,language,numericPanel);if(local!=null)for(Choice choice:local){if(choices.size()>=5)break;if(choice!=null&&choice.builtin&&choice.suffix!=null&&!choice.suffix.isEmpty()&&choice.suffix.length()<=128&&choices.stream().noneMatch(old->old.suffix.equals(choice.suffix)))choices.add(choice);}}
                            suffix=choices.isEmpty()?null:choices.get(0).suffix;
                            if(workerCancelled(id))return;
                            if(suffix!=null){
                                SplitSelection.Snapshot after=host.read(owner);
                                if(workerCancelled(id))return;
                                if(!WangshuIme.sameVoiceSnapshot(before,after)){before=null;suffix=null;choices.clear();}
                            }
                        }
                    }
                }catch(Exception refused){before=null;data=null;suffix=null;choices.clear();}
                // Do not clear shared main-thread fields: a replacement request may own them.
                if(workerCancelled(id))return;
                final SplitSelection.Snapshot captured=before;final PhraseData saved=data;final String result=suffix;final List<Choice> results=new ArrayList<>(choices);
                handler.post(()->{if(!current(id,session,owner,language)||savedEnabled!=host.savedEnabled()||localEnabled!=host.localEnabled()||numericPanel!=host.numericPanel()||
                        (selectionRequest&&cursorEnabled!=host.cursorEnabled()))return;if(SystemClock.elapsedRealtime()>deadline){cancel(false);return;}if(timeout!=null)handler.removeCallbacks(timeout);timeout=null;running=null;
                    if(captured!=null){
                        observedText=captured.text;observedOwner=owner;observedSession=session;acknowledgedEdit=false;
                        if(!selectionRequest||(compare&&(editWithoutBaseline||(prior!=null&&!prior.equals(captured.text)))))selectionPending=false;
                    }
                    if(result==null||saved==null||saved.revision!=(savedEnabled?host.revision():0)){if(id==generation){visible=null;host.show(null);}return;}
                    if(!current(id,session,owner,language)||(selectionRequest&&cursorEnabled!=host.cursorEnabled()))return;
                    visible=new Display(id,saved.revision,session,owner,captured,language,results,numericPanel,selectionRequest&&cursorEnabled);host.show(visible);});
            });};handler.postDelayed(debounce,120);
    }
    boolean hasUndo(){return undo!=null&&!closed;}
    Runnable undoAction(){
        if(!hasUndo())return null;
        Object ticket=undo.ticket;
        return ()->undo(ticket);
    }
    private boolean revision(Display selected,Choice choice){return choice.builtin?host.localEnabled():host.savedEnabled()&&host.revision()==selected.revision;}
    private boolean selectionCurrent(Display selected,Choice choice,long id){
        return current(id,selected.session,selected.owner,selected.language)&&revision(selected,choice)&&
                current(id,selected.session,selected.owner,selected.language)&&selected.numericPanel==host.numericPanel()&&
                (!selected.fromMovement||host.cursorEnabled());
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
            boolean accepted;
            try{accepted=host.insert(selected.owner,selected.session,selected.snapshot,
                    ()->selectionCurrent(selected,choice,next),choice.suffix);}
            catch(RuntimeException uncertain){accepted=false;}
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
    private void undo(Object ticket){
        main();
        if(editing)return;
        Undo saved=undo;
        if(saved==null||saved.ticket!=ticket){host.unavailable();return;}
        undo=null;
        editing=true;try{
            long id=generation;
            if(!current(id,saved.session,saved.owner,saved.language)){host.unavailable();host.show(null);return;}
            SplitSelection.Snapshot now;try{now=host.read(saved.owner);}catch(RuntimeException refused){now=null;}
            if(!current(id,saved.session,saved.owner,saved.language)||!WangshuIme.sameVoiceSnapshot(saved.after,now)){host.unavailable();host.show(null);return;}
            // Single targeted delete, never replace all matching text, poll, retry,
            // or roll back after an uncertain editor response.
            boolean accepted;
            try{accepted=host.retract(saved.owner,saved.session,saved.after,
                    ()->current(id,saved.session,saved.owner,saved.language),saved.units);}
            catch(RuntimeException uncertain){accepted=false;}
            if(!sameEditor(saved.session,saved.owner,saved.language)){host.unavailable();host.show(null);return;}
            try{now=host.read(saved.owner);}catch(RuntimeException refused){now=null;}
            if(!accepted||!sameEditor(saved.session,saved.owner,saved.language)||!WangshuIme.sameVoiceSnapshot(saved.before,now))host.unavailable();
            host.show(null);
        }finally{editing=false;}
    }
    public void close(){main();clear();closed=true;worker.shutdownNow();}
}
