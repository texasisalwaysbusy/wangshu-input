package org.wangshu.ime;

/** Per-user-tap guard: no editor text, audio, history, file I/O or automatic retry. */
public final class EmojiSelection {
    public enum Outcome { SUBMITTED, STALE, UNCERTAIN, BUSY }
    public interface Host {
        long session();long generation();long selectionRevision();Object connection();boolean active();
        /** Called at most once. Adapter rechecks its owner/session immediately before commitText. */
        boolean send(Object expectedConnection,String exactText);
    }
    public static final class Token {
        private final long session,generation,selection;private final Object connection;
        private boolean inFlight;
        private Token(long s,long g,long x,Object c){session=s;generation=g;selection=x;connection=c;}
    }
    public static Token capture(Host host){if(host==null||!host.active())return null;Object owner=host.connection();return owner==null?null:new Token(host.session(),host.generation(),host.selectionRevision(),owner);}
    /** Checks stale category buttons without sending an editor action. */
    public static boolean current(Host host,Token token){return host!=null&&token!=null&&host.active()&&host.session()==token.session&&host.generation()==token.generation&&host.selectionRevision()==token.selection&&host.connection()==token.connection&&host.session()==token.session&&host.generation()==token.generation&&host.selectionRevision()==token.selection&&host.active()&&host.session()==token.session;}
    /** Sequential taps remain independent; a reentrant or concurrent send is blocked. */
    public static Outcome select(Host host,Token token,String exactText){
        if(exactText==null||exactText.isEmpty()||exactText.length()>128||token==null)return Outcome.STALE;
        synchronized(token){if(token.inFlight)return Outcome.BUSY;token.inFlight=true;}
        try{
            if(!current(host,token))return Outcome.STALE;
            return host.send(token.connection,exactText)?Outcome.SUBMITTED:Outcome.UNCERTAIN;
        }catch(RuntimeException error){return Outcome.UNCERTAIN;}
        finally{synchronized(token){token.inFlight=false;}}
    }
    private EmojiSelection(){}
}
