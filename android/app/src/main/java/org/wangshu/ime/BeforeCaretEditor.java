package org.wangshu.ime;

import android.icu.text.BreakIterator;
import android.os.Looper;
import android.text.Spanned;
import android.view.inputmethod.ExtractedText;
import android.view.inputmethod.ExtractedTextRequest;
import android.view.inputmethod.InputConnection;
import java.util.Locale;

/**
 * Bounded, ephemeral delete-before-caret transaction. No gesture/UI or service fields.
 * Call edit methods on the main thread; invalidate on lifecycle/input identity changes.
 * InputConnection has no compare-and-swap: an unverified write is UNCERTAIN, never retried.
 */
public final class BeforeCaretEditor {
    public static final int MAX_UTF16 = 32768;
    public interface Provider { InputSession current(); }
    public static final class InputSession {
        public final InputConnection connection;
        public final long id;
        public final boolean privateInput;
        public InputSession(InputConnection connection,long id,boolean privateInput) {
            this.connection=connection;this.id=id;this.privateInput=privateInput;
        }
    }
    public enum State { APPLIED, NO_OP, REJECTED, UNCERTAIN }
    public enum Reason {
        VERIFIED, AT_START, NO_UNDO, WRONG_THREAD, UNAVAILABLE_OR_PRIVATE,
        UNSAFE_SNAPSHOT, NON_COLLAPSED_SELECTION, STALE_HOST, BATCH_REJECTED,
        SELECTION_REJECTED, COMMIT_REJECTED, HOST_EXCEPTION, WRITE_NOT_VERIFIED
    }
    public static final class Result {
        public final State state;
        public final Reason reason;
        public final boolean undoAvailable;
        private Result(State state,Reason reason,boolean undoAvailable) {
            this.state=state;this.reason=reason;this.undoAvailable=undoAvailable;
        }
    }
    private static final class Snapshot {
        final String text;final int start,end;
        Snapshot(String text,int start,int end){this.text=text;this.start=start;this.end=end;}
        boolean same(Snapshot other){return other!=null&&start==other.start&&end==other.end&&text.equals(other.text);}
    }
    private static final class Undo {
        final InputSession owner;final String prefix,suffix;final long generation;
        Undo(InputSession owner,String prefix,String suffix,long generation){this.owner=owner;this.prefix=prefix;this.suffix=suffix;this.generation=generation;}
    }
    /** Ephemeral preview identity; release must still match its exact editor/text/caret. */
    public static final class Preview {
        private final InputSession owner;private final Snapshot before;private final long generation;
        private Preview(InputSession owner,Snapshot before,long generation){this.owner=owner;this.before=before;this.generation=generation;}
    }
    private final Provider provider;
    private long generation;
    private Undo undo;

    public BeforeCaretEditor(Provider provider) {
        if(provider==null)throw new IllegalArgumentException("Provider required");
        this.provider=provider;
    }
    /** Drops references only: Java immutable String storage cannot promise a zeroized heap. */
    public synchronized void invalidate(){generation++;undo=null;}
    /** UI hint only; undo() rechecks exact host state before sending any edit. */
    public synchronized boolean hasUndo(){return undo!=null&&undo.generation==generation;}
    private Result result(State state,Reason reason){return new Result(state,reason,hasUndo());}
    private InputSession current(){try{return provider.current();}catch(RuntimeException unavailable){return null;}}
    private boolean active(InputSession owner,long epoch){
        if(epoch!=generation||owner==null||owner.connection==null||owner.privateInput)return false;
        InputSession now=current();return now!=null&&!now.privateInput&&now.connection==owner.connection&&now.id==owner.id&&epoch==generation;
    }
    private static boolean validUtf16(String text){
        for(int i=0;i<text.length();i++){
            char c=text.charAt(i);
            if(Character.isHighSurrogate(c)){if(++i>=text.length()||!Character.isLowSurrogate(text.charAt(i)))return false;}
            else if(Character.isLowSurrogate(c))return false;
        }
        return true;
    }
    private Snapshot read(InputSession owner,long epoch){
        if(!active(owner,epoch))return null;
        try{
            ExtractedTextRequest request=new ExtractedTextRequest();
            request.hintMaxChars=MAX_UTF16+1;request.hintMaxLines=MAX_UTF16+1;
            request.flags=InputConnection.GET_TEXT_WITH_STYLES;
            ExtractedText value=owner.connection.getExtractedText(request,0);
            if(value==null||value.text==null||value.text.length()>MAX_UTF16||value.startOffset!=0||value.partialStartOffset>=0||value.partialEndOffset>=0)return null;
            if(value.text instanceof Spanned){
                Spanned styled=(Spanned)value.text;
                for(Object span:styled.getSpans(0,styled.length(),Object.class))if((styled.getSpanFlags(span)&Spanned.SPAN_COMPOSING)!=0)return null;
            }
            String text=value.text.toString();int start=value.selectionStart,end=value.selectionEnd;
            if(text.length()>MAX_UTF16||start<0||end<0||start>text.length()||end>text.length()||!validUtf16(text))return null;
            BreakIterator boundary=BreakIterator.getCharacterInstance(Locale.ROOT);boundary.setText(text);
            if(!boundary.isBoundary(start)||!boundary.isBoundary(end)||!active(owner,epoch))return null;
            return new Snapshot(text,start,end);
        }catch(RuntimeException unavailable){return null;}
    }
    public synchronized Preview prepareDelete(){
        if(Looper.myLooper()!=Looper.getMainLooper())return null;
        long epoch=generation;InputSession owner=current();Snapshot before=read(owner,epoch);
        return before!=null&&before.start==before.end?new Preview(owner,before,epoch):null;
    }
    /** Side-effect-free guard when moving back from a bulk preview to ordinary repeat. */
    public synchronized boolean matchesPreview(Preview expected){
        if(expected==null||Looper.myLooper()!=Looper.getMainLooper()||expected.generation!=generation)return false;
        InputSession owner=current();Snapshot now=read(owner,generation);
        return now!=null&&expected.owner.connection==owner.connection&&expected.owner.id==owner.id&&expected.before.same(now);
    }
    public synchronized Result deleteBeforeCaret(){return deleteBeforeCaret(null);}
    public synchronized Result deleteBeforeCaret(Preview expected){
        long priorGeneration=generation;undo=null;long epoch=++generation;
        if(Looper.myLooper()!=Looper.getMainLooper())return result(State.REJECTED,Reason.WRONG_THREAD);
        InputSession owner=current();if(!active(owner,epoch))return result(State.REJECTED,Reason.UNAVAILABLE_OR_PRIVATE);
        Snapshot before=read(owner,epoch);if(before==null)return result(State.REJECTED,Reason.UNSAFE_SNAPSHOT);
        if(expected!=null&&(expected.generation!=priorGeneration||expected.owner.connection!=owner.connection||expected.owner.id!=owner.id||!expected.before.same(before)))return result(State.REJECTED,Reason.STALE_HOST);
        if(before.start!=before.end)return result(State.REJECTED,Reason.NON_COLLAPSED_SELECTION);
        if(before.start==0)return result(State.NO_OP,Reason.AT_START);
        String prefix=before.text.substring(0,before.start),suffix=before.text.substring(before.start);
        Result applied=replace(owner,epoch,before,0,before.start,"",new Snapshot(suffix,0,0));
        if(applied.state==State.APPLIED){
            if(!active(owner,epoch))return result(State.UNCERTAIN,Reason.WRITE_NOT_VERIFIED);
            undo=new Undo(owner,prefix,suffix,epoch);
            return result(State.APPLIED,Reason.VERIFIED);
        }
        return applied;
    }
    public synchronized Result undo(){
        Undo saved=undo;undo=null;long epoch=++generation;
        if(Looper.myLooper()!=Looper.getMainLooper())return result(State.REJECTED,Reason.WRONG_THREAD);
        if(saved==null)return result(State.REJECTED,Reason.NO_UNDO);
        if(!active(saved.owner,epoch))return result(State.REJECTED,Reason.UNAVAILABLE_OR_PRIVATE);
        Snapshot expected=new Snapshot(saved.suffix,0,0),actual=read(saved.owner,epoch);
        if(!expected.same(actual))return result(State.REJECTED,Reason.STALE_HOST);
        String restored=saved.prefix+saved.suffix;
        return replace(saved.owner,epoch,actual,0,0,saved.prefix,new Snapshot(restored,saved.prefix.length(),saved.prefix.length()));
    }
    private Result replace(InputSession owner,long epoch,Snapshot before,int start,int end,String value,Snapshot after){
        InputConnection connection=owner.connection;
        boolean beginCalled=false,selectionSent=false,selectionAccepted=false,commitSent=false,commitAccepted=false,endReturned=false;
        Reason reason=Reason.WRITE_NOT_VERIFIED;
        try{
            beginCalled=true;
            if(!connection.beginBatchEdit())reason=Reason.BATCH_REJECTED;
            else if(!before.same(read(owner,epoch)))reason=Reason.STALE_HOST;
            else{
                selectionSent=true;
                selectionAccepted=connection.setSelection(start,end);
                if(!selectionAccepted)reason=Reason.SELECTION_REJECTED;
                else if(!new Snapshot(before.text,start,end).same(read(owner,epoch)))reason=Reason.STALE_HOST;
                else{
                    // The immediately preceding read checks text, selection, IC and session.
                    // The platform offers no atomic condition-and-write API between these calls.
                    commitSent=true;commitAccepted=connection.commitText(value,1);
                    if(!commitAccepted)reason=Reason.COMMIT_REJECTED;
                }
            }
        }catch(RuntimeException failure){reason=Reason.HOST_EXCEPTION;}
        finally{
            if(beginCalled)try{
                // Direct EditText API33+ normally returns false when nesting reaches zero.
                // A returned false is not a failed edit; verify the actual final snapshot.
                connection.endBatchEdit();endReturned=true;
            }catch(RuntimeException failure){reason=Reason.HOST_EXCEPTION;}
        }
        Snapshot actual=read(owner,epoch);
        if(commitSent&&commitAccepted&&endReturned&&after.same(actual))return result(State.APPLIED,Reason.VERIFIED);
        if(!selectionSent&&!commitSent)return result(State.REJECTED,reason);
        // An accepted/dispatched edit can be delayed or race another host edit.
        // A momentarily unchanged snapshot is not proof that it was rejected.
        if(commitSent||selectionAccepted||reason==Reason.HOST_EXCEPTION)return result(State.UNCERTAIN,reason);
        if(before.same(actual))return result(State.REJECTED,reason);
        return result(State.UNCERTAIN,reason);
    }
}
