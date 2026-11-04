package org.wangshu.ime;

import java.util.concurrent.atomic.AtomicBoolean;

/** Synthetic candidate protocol; NOT production and NOT an Android guarantee. */
final class BoundEditorPrototype {
    static final class Snapshot {
        final String text;final int start,end;
        Snapshot(String t,int s,int e){text=t;start=s;end=e;}
        boolean same(Snapshot x){return x!=null&&text!=null&&text.equals(x.text)&&start==x.start&&end==x.end;}
    }
    interface Host {
        long session();Object connection();boolean allowed();
        Snapshot read(Object connection);
        boolean select(Object connection,int start,int end);
        boolean commit(Object connection,String text);
    }
    enum Outcome { ACCEPTED, BLOCKED, UNCERTAIN, BUSY }
    private final Host host;private final long session;private final Object owner;
    private final AtomicBoolean inflight=new AtomicBoolean();private volatile boolean closed;
    private BoundEditorPrototype(Host h,long s,Object c){host=h;session=s;owner=c;}
    static BoundEditorPrototype capture(Host h){
        try{if(h==null||!h.allowed())return null;long s=h.session();Object c=h.connection();if(c==null)return null;BoundEditorPrototype b=new BoundEditorPrototype(h,s,c);return b.current()?b:null;}
        catch(RuntimeException unavailable){return null;}
    }
    private boolean current(){return host.allowed()&&host.session()==session&&host.connection()==owner&&host.session()==session&&host.allowed();}
    Snapshot snapshot(){
        if(closed||!inflight.compareAndSet(false,true))return null;
        try{if(closed||!current()){closed=true;return null;}Snapshot s=host.read(owner);if(!current()||s==null){closed=true;return null;}return s;}
        catch(RuntimeException unavailable){closed=true;return null;}
        finally{inflight.set(false);}
    }
    /** A bound operation is one-shot, including on unknown result. No automatic retry. */
    Outcome replace(Snapshot expected,int start,int end,String replacement,int caretAfter){
        if(closed)return Outcome.BLOCKED;if(!inflight.compareAndSet(false,true))return Outcome.BUSY;
        boolean sentMutation=false;
        try{
            // A thread can observe open just before another finishes; recheck after acquiring.
            if(closed)return Outcome.BLOCKED;
            if(!current()||expected==null||expected.text==null||replacement==null||start<0||end<start||end>expected.text.length())return Outcome.BLOCKED;
            String target=expected.text.substring(0,start)+replacement+expected.text.substring(end);
            if(caretAfter<0||caretAfter>target.length())return Outcome.BLOCKED;
            Snapshot before=host.read(owner);if(!current()||!expected.same(before))return Outcome.BLOCKED;
            sentMutation=true;if(!host.select(owner,start,end)||!current())return Outcome.UNCERTAIN;
            Snapshot selected=host.read(owner);if(!current()||selected==null||!expected.text.equals(selected.text)||selected.start!=start||selected.end!=end)return Outcome.UNCERTAIN;
            if(!host.commit(owner,replacement)||!current())return Outcome.UNCERTAIN;
            if(!host.select(owner,caretAfter,caretAfter)||!current())return Outcome.UNCERTAIN;
            Snapshot after=host.read(owner);if(!current()||after==null||!target.equals(after.text)||after.start!=caretAfter||after.end!=caretAfter)return Outcome.UNCERTAIN;
            return Outcome.ACCEPTED;
        }catch(RuntimeException unavailable){return sentMutation?Outcome.UNCERTAIN:Outcome.BLOCKED;}
        finally{closed=true;inflight.set(false);}
    }
}
