package org.wangshu.ime;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** Synthetic hosts only; no Android InputConnection or user data. */
public final class EmojiReentrantChecks {
    static int checks;
    static void check(boolean ok,String message){if(!ok)throw new AssertionError(message);checks++;}
    static final class Fake implements EmojiSelection.Host {
        final Object owner=new Object();long session=1, generation=1, selection=1;boolean active=true;
        int sends;boolean reenter,throwOnce,reject,throwOnSession;EmojiSelection.Token token;EmojiSelection.Outcome inner;
        CountDownLatch started,release;
        public long session(){if(throwOnSession)throw new IllegalStateException("synthetic stale getter");return session;} public long generation(){return generation;} public long selectionRevision(){return selection;}
        public Object connection(){return owner;} public boolean active(){return active;}
        public boolean send(Object connection,String value){
            sends++;
            if(reenter){reenter=false;inner=EmojiSelection.select(this,token,value);}
            if(started!=null){started.countDown();try{if(!release.await(3,TimeUnit.SECONDS))throw new AssertionError("test timeout");}catch(InterruptedException ex){Thread.currentThread().interrupt();throw new IllegalStateException(ex);}}
            if(throwOnce){throwOnce=false;throw new IllegalStateException("synthetic uncertain result");}
            return !reject;
        }
    }
    public static void main(String[] args)throws Exception {
        Fake h=new Fake();h.token=EmojiSelection.capture(h);h.reenter=true;
        EmojiSelection.Outcome first=EmojiSelection.select(h,h.token,"😀");
        check(h.sends==1,"one host send under synchronous reentry");
        check(first==EmojiSelection.Outcome.SUBMITTED,"outer submitted");
        check(h.inner==EmojiSelection.Outcome.BUSY,"nested operation reports busy");
        check(EmojiSelection.select(h,h.token,"😀")==EmojiSelection.Outcome.SUBMITTED,"a later separate tap works");
        check(h.sends==2,"two sequential taps yield exactly two sends");
        h.throwOnce=true;check(EmojiSelection.select(h,h.token,"😀")==EmojiSelection.Outcome.UNCERTAIN,"throw is uncertain");
        check(EmojiSelection.select(h,h.token,"😀")==EmojiSelection.Outcome.SUBMITTED,"inflight guard clears after exception");
        h.throwOnSession=true;check(EmojiSelection.select(h,h.token,"😀")==EmojiSelection.Outcome.UNCERTAIN,"throw in identity check fails closed");h.throwOnSession=false;
        check(EmojiSelection.select(h,h.token,"😀")==EmojiSelection.Outcome.SUBMITTED,"guard clears after identity exception");
        h.reject=true;check(EmojiSelection.select(h,h.token,"😀")==EmojiSelection.Outcome.UNCERTAIN,"false result is uncertain");h.reject=false;
        int before=h.sends;h.generation++;check(EmojiSelection.select(h,h.token,"😀")==EmojiSelection.Outcome.STALE&&h.sends==before,"stale token cannot send");
        Fake concurrent=new Fake();concurrent.token=EmojiSelection.capture(concurrent);concurrent.started=new CountDownLatch(1);concurrent.release=new CountDownLatch(1);
        AtomicReference<EmojiSelection.Outcome> other=new AtomicReference<>();Thread thread=new Thread(()->other.set(EmojiSelection.select(concurrent,concurrent.token,"😺")));thread.start();
        check(concurrent.started.await(3,TimeUnit.SECONDS),"first send entered");
        EmojiSelection.Outcome second=EmojiSelection.select(concurrent,concurrent.token,"😺");concurrent.release.countDown();thread.join(3000);
        check(!thread.isAlive(),"first send returned");check(concurrent.sends==1,"one concurrent host send");
        check(other.get()==EmojiSelection.Outcome.SUBMITTED&&second==EmojiSelection.Outcome.BUSY,"concurrent second selection reports busy");
        System.out.println("PASS "+checks+" synthetic emoji reentry and isolation checks; Android InputConnection NOT RUN");
    }
}
