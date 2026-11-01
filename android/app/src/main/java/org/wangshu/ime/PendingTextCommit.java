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
    private PendingTextCommit(){}

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
