package org.wangshu.ime;

/** Keep pending composition until its captured editor accepts the commit. No retries. */
final class PendingTextCommit {
    enum Result { ACCEPTED, REJECTED, UNAVAILABLE, STALE, UNCERTAIN }
    interface Host {
        Object connection();
        int session();
        String pending();
        boolean commit(Object connection,String value);
        void clearPending();
    }
    /** A caller-owned display/editor binding, never recaptured during cleanup.
     * current() must check that binding after its external reads. The final
     * clear operation must recheck and then change local pending state without
     * another callback between that check and mutation.
     */
    interface BoundHost {
        boolean current();
        boolean commit(String value);
        boolean clearPendingIfCurrent();
    }
    private PendingTextCommit(){}

    static Result sendBound(BoundHost host,String value){
        if(value==null)throw new IllegalArgumentException("Missing pending text");
        try{if(!host.current())return Result.STALE;}
        catch(RuntimeException unavailable){return Result.UNAVAILABLE;}
        final boolean accepted;
        try{accepted=host.commit(value);}
        catch(RuntimeException uncertain){return Result.UNCERTAIN;}
        try{
            if(!host.current())return Result.STALE;
            if(!accepted)return Result.REJECTED;
            return host.clearPendingIfCurrent()?Result.ACCEPTED:Result.STALE;
        }catch(RuntimeException uncertain){return Result.UNCERTAIN;}
    }

    static Result send(Host host,String value){
        if(value==null)throw new IllegalArgumentException("Missing pending text");
        Object owner=host.connection();int session=host.session();String raw=host.pending();
        if(owner==null)return Result.UNAVAILABLE;
        boolean accepted;
        try{accepted=host.commit(owner,value);}
        catch(RuntimeException uncertain){return Result.UNCERTAIN;}
        // A synchronous host callback can switch editors or start another composition.
        if(owner!=host.connection()||session!=host.session()||!raw.equals(host.pending()))return Result.STALE;
        if(!accepted)return Result.REJECTED;
        host.clearPending();return Result.ACCEPTED;
    }
}
