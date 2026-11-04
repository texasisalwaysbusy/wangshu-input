package org.wangshu.ime;

/** Host-contract regression; no claim of real Android InputConnection execution. */
public final class PendingTextCommitTest {
    private static int checks;
    private static void check(boolean value,String label){if(!value)throw new AssertionError(label);checks++;System.out.println("PASS "+label);}
    private static final class Editor implements PendingTextCommit.Host {
        Object owner=new Object();int session;String pending="ni";int calls,clears;
        boolean accept;boolean fail;Runnable during=()->{};
        public Object connection(){return owner;}
        public int session(){return session;}
        public String pending(){return pending;}
        public boolean commit(Object expected,String text){check(expected==owner,"commit captures exact connection");calls++;during.run();if(fail)throw new IllegalStateException("synthetic editor failure");return accept;}
        public void clearPending(){clears++;pending="";}
    }
    public static void main(String[] args){
        Editor denied=new Editor();check(PendingTextCommit.send(denied,"你")==PendingTextCommit.Result.REJECTED&&denied.pending.equals("ni")&&denied.clears==0&&denied.calls==1,"explicit refusal preserves composition and does not retry");
        Editor accepted=new Editor();accepted.accept=true;check(PendingTextCommit.send(accepted,"你")==PendingTextCommit.Result.ACCEPTED&&accepted.pending.isEmpty()&&accepted.clears==1&&accepted.calls==1,"accepted commit clears pending exactly once");
        Editor missing=new Editor();missing.owner=null;check(PendingTextCommit.send(missing,"你")==PendingTextCommit.Result.UNAVAILABLE&&missing.pending.equals("ni")&&missing.calls==0,"absent connection preserves pending without call");
        Editor thrown=new Editor();thrown.fail=true;check(PendingTextCommit.send(thrown,"你")==PendingTextCommit.Result.UNCERTAIN&&thrown.pending.equals("ni")&&thrown.calls==1&&thrown.clears==0,"uncertain exception never clears or retries");
        Editor switched=new Editor();switched.accept=true;switched.during=()->{switched.owner=new Object();switched.pending="new";};check(PendingTextCommit.send(switched,"你")==PendingTextCommit.Result.STALE&&switched.pending.equals("new")&&switched.clears==0,"reentrant editor switch preserves new composition");
        Editor rebound=new Editor();rebound.accept=true;rebound.during=()->rebound.session++;check(PendingTextCommit.send(rebound,"你")==PendingTextCommit.Result.STALE&&rebound.pending.equals("ni")&&rebound.clears==0,"same connection with new input session rejects continuation");
        Editor retyped=new Editor();retyped.accept=true;retyped.during=()->retyped.pending="nihao";check(PendingTextCommit.send(retyped,"你")==PendingTextCommit.Result.STALE&&retyped.pending.equals("nihao"),"reentrant new composition is not discarded");
        Editor raw=new Editor();raw.pending="\\alpha";raw.accept=false;check(PendingTextCommit.send(raw,raw.pending)==PendingTextCommit.Result.REJECTED&&raw.pending.equals("\\alpha"),"failed raw completion preserves full mathematical command");
        Editor noCapture=new Editor();noCapture.owner=null;boolean invalid=false;try{PendingTextCommit.send(noCapture,null);}catch(IllegalArgumentException expected){invalid=true;}check(invalid&&noCapture.calls==0,"invalid payload never touches editor");
        System.out.println("PASS "+checks+" contract assertions; fake host only, real Android runtime NOT_RUN");
    }
}
