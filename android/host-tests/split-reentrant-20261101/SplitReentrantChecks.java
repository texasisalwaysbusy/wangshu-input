package org.wangshu.ime;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** Explicit synthetic editable text; no real editor or user data. */
public final class SplitReentrantChecks {
    static int checks;
    static void check(boolean ok,String message){if(!ok)throw new AssertionError(message);checks++;}
    static final class Fake implements SplitSelection.Editor {
        String text="XSAFE";int caret=1,calls;boolean nested,nestedOnRead,afterFailure,throwAfter,failAfter;Boolean inner;SplitSelection session;CountDownLatch started,release;
        public SplitSelection.Snapshot read(){if(nestedOnRead){nestedOnRead=false;inner=session.toggle(7,"A");}return new SplitSelection.Snapshot(text,caret,caret);}
        public boolean replace(int start,int end,String value,int after){
            calls++;
            if(started!=null){started.countDown();try{if(!release.await(3,TimeUnit.SECONDS))throw new AssertionError("test timeout");}catch(InterruptedException e){Thread.currentThread().interrupt();throw new IllegalStateException(e);}}
            if(nested){nested=false;inner=session.toggle(7,"A");}
            text=text.substring(0,start)+value+text.substring(end);caret=after;
            if(throwAfter){throwAfter=false;throw new IllegalStateException("synthetic applied-then-throw");}
            if(failAfter){failAfter=false;return false;}
            return true;
        }
    }
    static void insert(){Fake f=new Fake();f.session=new SplitSelection(f);f.nested=true;boolean outer=f.session.toggle(7,"A");System.out.println("INSERT synthetic outcome: text="+f.text+", sends="+f.calls+", nested="+f.inner);check(outer,"outer insert succeeded");check(Boolean.FALSE.equals(f.inner),"nested insert was refused");check(f.calls==1,"one insertion sent");check(f.text.equals("XASAFE"),"one inserted block and all original text preserved");check(f.session.selected(7)&&f.session.selectedText().equals("A"),"selection matches accepted insertion");}
    static void remove(){Fake f=new Fake();f.session=new SplitSelection(f);check(f.session.toggle(7,"A"),"setup insertion");f.calls=0;f.nested=true;boolean outer=f.session.toggle(7,"A");System.out.println("REMOVE synthetic outcome: text="+f.text+", sends="+f.calls+", nested="+f.inner);check(outer,"outer removal succeeded");check(Boolean.FALSE.equals(f.inner),"nested removal was refused");check(f.calls==1,"one removal sent");check(f.text.equals("XSAFE"),"removal preserves following original character");check(!f.session.selected(7)&&f.session.selectedText().isEmpty(),"selection cleared");}
    static void sequential(){Fake f=new Fake();f.session=new SplitSelection(f);check(f.session.toggle(7,"A")&&f.session.toggle(8,"B"),"separate inserts");check(f.text.equals("XABSAFE")&&f.session.selectedText().equals("AB"),"click order preserved");check(f.session.toggle(7,"A")&&f.text.equals("XBSAFE")&&f.session.selectedText().equals("B"),"only selected earlier span removed");check(f.session.toggle(7,"A")&&f.text.equals("XBASAFE")&&f.session.selectedText().equals("BA"),"reselected span appends in new click order");}
    static void uncertain(){Fake a=new Fake();a.session=new SplitSelection(a);a.failAfter=true;check(!a.session.toggle(7,"A"),"false acknowledgement reported failure");int sent=a.calls;check(!a.session.toggle(7,"A")&&a.calls==sent,"uncertain false is not automatically retried");Fake b=new Fake();b.session=new SplitSelection(b);b.throwAfter=true;check(!b.session.toggle(7,"A"),"applied-then-throw reported failure");sent=b.calls;check(!b.session.toggle(7,"A")&&b.calls==sent,"uncertain exception is not automatically retried");}
    static void duringRead(){Fake f=new Fake();f.session=new SplitSelection(f);f.nestedOnRead=true;check(f.session.toggle(7,"A"),"outer insert after read reentry");check(Boolean.FALSE.equals(f.inner)&&f.calls==1&&f.text.equals("XASAFE"),"read callback cannot reenter toggle");}
    static void concurrent()throws Exception{Fake f=new Fake();f.session=new SplitSelection(f);f.started=new CountDownLatch(1);f.release=new CountDownLatch(1);AtomicReference<Boolean> first=new AtomicReference<>();Thread worker=new Thread(()->first.set(f.session.toggle(7,"A")));worker.start();check(f.started.await(3,TimeUnit.SECONDS),"first toggle entered editor");boolean second=f.session.toggle(7,"A");f.release.countDown();worker.join(3000);check(!worker.isAlive(),"first toggle returned");check(Boolean.TRUE.equals(first.get())&&!second&&f.calls==1&&f.text.equals("XASAFE"),"concurrent toggle cannot touch editor");}
    static void identityRisk(){
        final class Swap implements SplitSelection.Editor {
            String first="XSAFE",second="XSAFE";int reads;boolean switched;
            public SplitSelection.Snapshot read(){String before=switched?second:first;if(++reads==2)switched=true;return new SplitSelection.Snapshot(before,1,1);}
            public boolean replace(int start,int end,String value,int caretAfter){String source=switched?second:first;String changed=source.substring(0,start)+value+source.substring(end);if(switched)second=changed;else first=changed;return true;}
        }
        Swap host=new Swap();SplitSelection s=new SplitSelection(host);boolean accepted=s.toggle(7,"A");
        if(!accepted||!host.first.equals("XSAFE")||!host.second.equals("XASAFE"))throw new AssertionError("identity-risk fixture did not demonstrate precondition");
        System.out.println("KNOWN LIMITATION: synthetic editor switch with identical text can redirect insertion because Snapshot has no editor identity; Android NOT RUN");
    }
    public static void main(String[] args)throws Exception{switch(args[0]){case "insert":insert();break;case "remove":remove();break;case "all":insert();remove();sequential();uncertain();duringRead();concurrent();break;case "identity-risk":identityRisk();return;default:throw new IllegalArgumentException();}System.out.println("PASS "+checks+" synthetic SplitSelection checks; Android InputConnection NOT RUN");}
}
