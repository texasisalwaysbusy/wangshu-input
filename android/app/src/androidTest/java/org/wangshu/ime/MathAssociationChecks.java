package org.wangshu.ime;

import android.app.Instrumentation;
import android.os.Looper;
import android.os.SystemClock;
import android.view.inputmethod.InputConnection;
import android.view.inputmethod.InputConnectionWrapper;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/** Future native Handler/executor checks. Only generated fake-host snapshots, no actual editor input. */
public final class MathAssociationChecks {
    private final Instrumentation test;
    private final StringBuilder log=new StringBuilder();
    private final List<Fixture> fixtures=new ArrayList<>();
    private int checks;
    private MathAssociationChecks(Instrumentation test){this.test=test;}
    private void check(boolean value,String label){if(!value)throw new AssertionError(label);checks++;log.append("PASS ").append(label).append('\n');}
    private void main(Runnable work){
        AtomicReference<Throwable> problem=new AtomicReference<>();
        test.runOnMainSync(()->{try{work.run();}catch(Throwable failure){problem.set(failure);}});
        Throwable failure=problem.get();if(failure!=null)throw new AssertionError("Native main-thread fixture action failed",failure);
    }
    private void stage(String name){android.os.Bundle bundle=new android.os.Bundle();bundle.putString("stream","MATH_ASSOCIATION_STAGE "+name+"\n");test.sendStatus(0,bundle);log.append("STAGE ").append(name).append(" elapsed=").append(SystemClock.elapsedRealtime()).append('\n');}
    private static void await(CountDownLatch signal,String label)throws Exception{if(!signal.await(6,TimeUnit.SECONDS))throw new AssertionError("PRECONDITION_TIMEOUT: "+label);}
    private void settle(){main(()->{});SystemClock.sleep(200);main(()->{});}
    private static final class Scope {
        // A real Android interface wrapper provides identity only; no InputConnection method is invoked.
        final InputConnection identity=new InputConnectionWrapper(null,true);
        volatile SplitSelection.Snapshot snapshot;
        Scope(String value,int start,int end){snapshot=new SplitSelection.Snapshot(value,start,end);}
    }
    private static final class Host implements MathAssociation.Host {
        final Map<InputConnection,Scope> scopes=new ConcurrentHashMap<>();
        volatile Scope scope;volatile int session=1;volatile boolean enabled=true,readThrows,readNull,insertAccepted=true,insertThrows;volatile Runnable onNextHide;
        final AtomicInteger inserts=new AtomicInteger(),unavailable=new AtomicInteger(),positiveShows=new AtomicInteger(),hides=new AtomicInteger(),backgroundReads=new AtomicInteger(),mainReads=new AtomicInteger();
        final CountDownLatch shown=new CountDownLatch(1);
        final AtomicReference<Throwable> threadFault=new AtomicReference<>();
        final List<String> displayedValues=new java.util.concurrent.CopyOnWriteArrayList<>();
        volatile MathAssociation.Display visible;volatile String inserted;
        Host(String text){scope=new Scope(text,text.length(),text.length());scopes.put(scope.identity,scope);}
        void ui(){if(Looper.myLooper()!=Looper.getMainLooper())threadFault.compareAndSet(null,new AssertionError("UI callback outside Android main looper"));}
        public boolean allowed(){ui();return enabled;}
        public int session(){ui();return session;}
        public InputConnection connection(){ui();return scope==null?null:scope.identity;}
        public SplitSelection.Snapshot read(InputConnection captured){
            if(Looper.myLooper()==Looper.getMainLooper())mainReads.incrementAndGet();else backgroundReads.incrementAndGet();
            if(readThrows)throw new IllegalStateException("synthetic captured-snapshot read failure");if(readNull)return null;
            Scope owner=scopes.get(captured);return owner==null?null:owner.snapshot;
        }
        public void show(MathAssociation.Display value){ui();visible=value;if(value==null){hides.incrementAndGet();Runnable action=onNextHide;onNextHide=null;if(action!=null)action.run();}else{positiveShows.incrementAndGet();displayedValues.add(value.candidate.text);shown.countDown();}}
        public boolean insert(String value){
            ui();inserts.incrementAndGet();inserted=value;if(insertThrows)throw new IllegalStateException("synthetic insert outcome unavailable");if(!insertAccepted)return false;
            SplitSelection.Snapshot old=scope.snapshot;int caret=old.start;
            scope.snapshot=new SplitSelection.Snapshot(old.text.substring(0,caret)+value+old.text.substring(old.end),caret+value.length(),caret+value.length());return true;
        }
        public void unavailable(){ui();unavailable.incrementAndGet();}
        void snapshot(String text,int start,int end){scope.snapshot=new SplitSelection.Snapshot(text,start,end);}
        void replaceConnection(){SplitSelection.Snapshot before=scope.snapshot;scope=new Scope(before.text,before.start,before.end);scopes.put(scope.identity,scope);}
    }
    private static final class Provider implements MathAssociation.Provider {
        final boolean blockFirst;final CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1),returned=new CountDownLatch(1);
        final AtomicInteger calls=new AtomicInteger();final AtomicReference<Throwable> threadFault=new AtomicReference<>();
        volatile boolean throwFailure;volatile ContextMathCandidates.Result firstOverride;
        volatile String lastContext,lastExpression;volatile long enteredAt,returnedAt;
        Provider(boolean blockFirst){this.blockFirst=blockFirst;}
        public ContextMathCandidates.Result suggest(String context,String expression){
            if(Looper.myLooper()==Looper.getMainLooper())threadFault.compareAndSet(null,new AssertionError("Provider evaluated on keyboard main looper"));
            int call=calls.incrementAndGet();lastContext=context;lastExpression=expression;
            if(call==1){enteredAt=SystemClock.elapsedRealtime();entered.countDown();}
            try{
                if(call==1&&blockFirst){
                    // Deliberately emulate an uncooperative provider so cancellation must discard a late answer.
                    long deadline=SystemClock.elapsedRealtime()+10000;
                    while(release.getCount()!=0&&SystemClock.elapsedRealtime()<deadline){try{release.await(100,TimeUnit.MILLISECONDS);}catch(InterruptedException ignored){/* synthetic provider deliberately ignores interrupt */}}
                    if(release.getCount()!=0)throw new IllegalStateException("synthetic provider bound exceeded");
                }
                if(throwFailure)throw new IllegalStateException("synthetic provider failure");
                return call==1&&firstOverride!=null?firstOverride:ContextMathCandidates.suggest(context,expression);
            }finally{if(call==1){returnedAt=SystemClock.elapsedRealtime();returned.countDown();}}
        }
    }
    private static final class Fixture {
        final Host host;final Provider provider;MathAssociation controller;long requestedAt;
        Fixture(String text,boolean blocked){host=new Host(text);provider=new Provider(blocked);}
    }
    private Fixture fixture(String text,boolean blocked){Fixture f=new Fixture(text,blocked);fixtures.add(f);main(()->f.controller=new MathAssociation(f.host,f.provider));return f;}
    private void request(Fixture f){main(()->{f.requestedAt=SystemClock.elapsedRealtime();f.controller.request();});}
    private MathAssociation.Display shown(Fixture f)throws Exception{await(f.host.shown,"actual Handler show callback");main(()->{});check(f.host.visible!=null,"native Handler publishes a current candidate");return f.host.visible;}
    private void close(Fixture f){f.provider.release.countDown();main(()->f.controller.close());}
    private void verifyThreads(Fixture f){check(f.host.threadFault.get()==null&&f.provider.threadFault.get()==null,"UI callbacks stay on native main looper while provider stays off it");}
    private void acceptedOnlyByUser()throws Exception{
        stage("explicit-accept-only");Fixture f=fixture("1+1=",false);request(f);MathAssociation.Display display=shown(f);
        check(f.host.inserts.get()==0&&f.host.scope.snapshot.text.equals("1+1="),"READY never automatically inserts or modifies host text");
        check(display.candidate.text.equals("2")&&display.before.text.equals("1+1="),"display retains generated candidate and its exact captured snapshot");
        check(f.host.backgroundReads.get()>0,"captured connection snapshot read occurs on executor");
        main(()->f.controller.accept(display));check(f.host.inserts.get()==1&&"2".equals(f.host.inserted),"one explicit user acceptance invokes exactly one insertion");
        main(()->f.controller.accept(display));check(f.host.inserts.get()==1,"second acceptance of old Display never inserts twice");verifyThreads(f);close(f);
    }
    private void staleAccepts()throws Exception{
        for(String mode:new String[]{"text","caret","selection","session","connection","hidden"}){
            stage("stale-accept-"+mode);Fixture f=fixture("1+1=",false);request(f);MathAssociation.Display display=shown(f);
            main(()->{
                switch(mode){
                    case "text":f.host.snapshot("9+9=",4,4);break;
                    case "caret":f.host.snapshot("1+1=",3,3);break;
                    case "selection":f.host.snapshot("1+1=",0,2);break;
                    case "session":f.host.session++;break;
                    case "connection":f.host.replaceConnection();break;
                    case "hidden":f.host.enabled=false;break;
                    default:throw new AssertionError("Unknown generated test mode");
                }
                f.controller.accept(display);
            });
            check(f.host.inserts.get()==0&&f.host.unavailable.get()>0,"changed "+mode+" refuses stale displayed candidate without insertion");verifyThreads(f);close(f);
        }
    }
    private void staleResponses()throws Exception{
        for(String mode:new String[]{"text","caret","session","connection","hidden"}){
            stage("stale-worker-response-"+mode);Fixture f=fixture("1+1=",true);request(f);await(f.provider.entered,"provider entered before generated host change");
            main(()->{switch(mode){case "text":f.host.snapshot("9+9=",4,4);break;case "caret":f.host.snapshot("1+1=",3,3);break;case "session":f.host.session++;break;case "connection":f.host.replaceConnection();break;case "hidden":f.host.enabled=false;break;default:throw new AssertionError();}});
            f.provider.release.countDown();await(f.provider.returned,"late provider answer returned");settle();
            long observedAt=SystemClock.elapsedRealtime();log.append("TIMING stale-").append(mode).append(" request=").append(f.requestedAt).append(" enter=").append(f.provider.enteredAt).append(" return=").append(f.provider.returnedAt).append(" observed=").append(observedAt).append('\n');
            if(observedAt-f.requestedAt>=1000)throw new AssertionError("PRECONDITION_TOO_SLOW: stale-response observation reached the 1000ms deadline window; cannot count timeout masking as snapshot-guard acceptance");
            check(f.host.positiveShows.get()==0&&f.host.inserts.get()==0,"changed "+mode+" suppresses old in-flight answer before display");verifyThreads(f);close(f);
        }
    }
    private void reentrantClearDuringAccept()throws Exception{
        for(String mode:new String[]{"text","connection","hidden"}){
            stage("accept-clear-reentry-"+mode);Fixture f=fixture("1+1=",false);request(f);MathAssociation.Display display=shown(f);
            main(()->{f.host.onNextHide=()->{switch(mode){case "text":f.host.snapshot("9+9=",4,4);break;case "connection":f.host.replaceConnection();break;case "hidden":f.host.enabled=false;break;default:throw new AssertionError();}};f.controller.accept(display);});
            check(f.host.inserts.get()==0&&f.host.unavailable.get()>0,"scope/text change reentered from clearing candidate UI refuses subsequent insertion: "+mode);verifyThreads(f);close(f);
        }
    }
    private void lifecycleAndDeadline()throws Exception{
        stage("cancel-during-debounce");Fixture before=fixture("1+1=",false);main(()->{before.controller.request();before.controller.clear();});SystemClock.sleep(200);main(()->{});
        check(before.provider.calls.get()==0&&before.host.positiveShows.get()==0,"clear before actual debounce prevents provider launch");close(before);
        stage("cancel-inflight");Fixture cancel=fixture("1+1=",true);request(cancel);await(cancel.provider.entered,"cancel fixture provider entry");main(()->cancel.controller.clear());cancel.provider.release.countDown();await(cancel.provider.returned,"cancelled uncooperative provider return");settle();
        check(cancel.host.positiveShows.get()==0&&cancel.host.inserts.get()==0,"explicit cancellation suppresses a provider that ignores interruption");close(cancel);
        stage("close-inflight");Fixture closed=fixture("1+1=",true);request(closed);await(closed.provider.entered,"close fixture provider entry");main(()->closed.controller.close());closed.provider.release.countDown();await(closed.provider.returned,"closed provider return");settle();main(()->closed.controller.request());settle();
        check(closed.host.positiveShows.get()==0&&closed.host.inserts.get()==0&&closed.provider.calls.get()==1,"close rejects late answers and future requests");verifyThreads(closed);
        stage("actual-handler-timeout");Fixture expired=fixture("1+1=",true);request(expired);await(expired.provider.entered,"timeout fixture provider entry");long until=SystemClock.elapsedRealtime()+6000;
        while(expired.host.hides.get()<2&&SystemClock.elapsedRealtime()<until)SystemClock.sleep(30);main(()->{});
        check(expired.host.hides.get()>=2&&expired.host.positiveShows.get()==0,"native Handler deadline clears a still-blocked request");expired.provider.release.countDown();await(expired.provider.returned,"timed-out provider return");settle();
        check(expired.host.positiveShows.get()==0&&expired.host.inserts.get()==0,"late timed-out answer never displays or inserts");close(expired);
        stage("latest-request-wins");Fixture replaced=fixture("1+1=",true);replaced.provider.firstOverride=ContextMathCandidates.suggest("","9=");request(replaced);await(replaced.provider.entered,"obsolete request provider entry");main(()->{replaced.host.snapshot("3+3=",4,4);replaced.controller.request();});replaced.provider.release.countDown();MathAssociation.Display latest=shown(replaced);
        check(latest.candidate.text.equals("6")&&replaced.host.positiveShows.get()==1&&!replaced.host.displayedValues.contains("9"),"new generation publishes only latest value after obsolete provider returns");check(replaced.host.inserts.get()==0,"replacement query still requires explicit acceptance");verifyThreads(replaced);close(replaced);
    }
    private void refusalAndScope()throws Exception{
        stage("disallowed-input-scope");Fixture disabled=fixture("1+1=",false);main(()->{disabled.host.enabled=false;disabled.controller.request();});SystemClock.sleep(200);main(()->{});
        check(disabled.provider.calls.get()==0&&disabled.host.backgroundReads.get()==0&&disabled.host.positiveShows.get()==0,"disallowed/private/hidden scope performs no snapshot read or provider evaluation");close(disabled);
        stage("missing-input-connection");Fixture disconnected=fixture("1+1=",false);main(()->{disconnected.host.scope=null;disconnected.controller.request();});SystemClock.sleep(200);main(()->{});
        check(disconnected.provider.calls.get()==0&&disconnected.host.backgroundReads.get()==0&&disconnected.host.positiveShows.get()==0,"missing InputConnection refuses without reading generated editor text");close(disconnected);
        stage("provider-failure");Fixture broken=fixture("1+1=",false);broken.provider.throwFailure=true;request(broken);await(broken.provider.returned,"throwing provider entry/return");settle();check(broken.host.positiveShows.get()==0&&broken.host.inserts.get()==0,"provider exception refuses suggestion without insertion or fallback upload");close(broken);
        stage("snapshot-read-failure-at-accept");Fixture unreadable=fixture("1+1=",false);request(unreadable);MathAssociation.Display display=shown(unreadable);main(()->{unreadable.host.readThrows=true;unreadable.controller.accept(display);});
        check(unreadable.host.inserts.get()==0&&unreadable.host.unavailable.get()>0,"accept snapshot read exception is a safe refusal instead of a main-thread crash");close(unreadable);
        stage("snapshot-unavailable-at-accept");Fixture missing=fixture("1+1=",false);request(missing);MathAssociation.Display selected=shown(missing);main(()->{missing.host.readNull=true;missing.controller.accept(selected);});check(missing.host.inserts.get()==0&&missing.host.unavailable.get()>0,"null accept snapshot refuses stale insertion");close(missing);
        stage("host-insert-refusal");Fixture refuses=fixture("1+1=",false);request(refuses);MathAssociation.Display rejected=shown(refuses);main(()->{refuses.host.insertAccepted=false;refuses.controller.accept(rejected);});check(refuses.host.inserts.get()==1&&refuses.host.unavailable.get()==1&&refuses.host.scope.snapshot.text.equals("1+1="),"host insertion refusal is reported once without retry or text replacement");close(refuses);
        stage("host-insert-exception");Fixture uncertain=fixture("1+1=",false);request(uncertain);MathAssociation.Display failed=shown(uncertain);main(()->{uncertain.host.insertThrows=true;uncertain.controller.accept(failed);});check(uncertain.host.inserts.get()==1&&uncertain.host.unavailable.get()==1&&uncertain.host.scope.snapshot.text.equals("1+1="),"insertion exception reports unavailable once without a duplicate attempt");close(uncertain);
        stage("query-context-and-caret-suffix");String expression="a²+b²−c=";String context="a=j b=1 c=h";String generated=context+"\n"+expression+"TAIL";Fixture substitution=fixture(generated,false);main(()->substitution.host.snapshot(generated,context.length()+1+expression.length(),context.length()+1+expression.length()));request(substitution);MathAssociation.Display symbolic=shown(substitution);
        check(symbolic.candidate.kind==ContextMathCandidates.Kind.SUBSTITUTION&&symbolic.candidate.text.equals("j²+1²−h"),"provider receives only current ending expression and explicit preceding context");check(substitution.provider.lastContext.equals(context)&&substitution.provider.lastExpression.equals(expression),"caret suffix is excluded and context fallback uses exact bounded query fields");close(substitution);
        stage("oversized-display-candidate");Fixture large=fixture("1+1=",false);large.provider.firstOverride=ContextMathCandidates.suggest("","((2^16)^16)^4=");request(large);await(large.provider.returned,"oversized numeric answer");settle();check(large.host.positiveShows.get()==0&&large.host.inserts.get()==0,"controller refuses candidate over its 256-character presentation cap");close(large);
    }
    private void cleanup(){for(Fixture f:fixtures)f.provider.release.countDown();for(Fixture f:fixtures)if(f.controller!=null)main(()->f.controller.close());}
    public static String run(Instrumentation test)throws Exception{
        if(Looper.myLooper()==Looper.getMainLooper())throw new AssertionError("Run instrumentation suite off the native main looper");
        MathAssociationChecks suite=new MathAssociationChecks(test);
        try{suite.acceptedOnlyByUser();suite.staleAccepts();suite.staleResponses();suite.reentrantClearDuringAccept();suite.lifecycleAndDeadline();suite.refusalAndScope();return "PASS "+suite.checks+" actual Android Handler/executor math-controller assertions\n"+suite.log+"Generated fake Host/InputConnection identities only; no editor commit, OS keyboard UI, screenshots, real device, ASR or user text tested. Deadline timings are recorded; stale-response observations at/after the deadline are blocked instead of counted as guard acceptance.\n";}
        catch(Throwable failure){throw new AssertionError("MathAssociationChecks failed after "+suite.checks+" executed assertions\n"+suite.log,failure);}
        finally{suite.cleanup();}
    }
}
