package org.wangshu.ime;

import android.icu.text.BreakIterator;
import android.text.Spanned;
import android.os.Looper;
import android.view.KeyEvent;
import android.view.inputmethod.*;
import java.util.Locale;

/** Bounded ephemeral navigation. Each action is bound to one connection/session; no retries. */
final class EditorActions {
    private static final int MAX_SNAPSHOT=100000;
    private final WangshuIme ime;
    boolean selecting;
    private int anchor=-1,generation,stateSession;
    private InputConnection stateConnection;
    private boolean busy;
    private long selectionRevision;private int notifiedStart=-1,notifiedEnd=-1;
    private static final class Owner {
        final InputConnection connection;final int session,generation;final long selection;
        Owner(InputConnection c,int s,int g,long r){connection=c;session=s;generation=g;selection=r;}
    }
    EditorActions(WangshuIme service){ime=service;}
    private void clearSelection(){selecting=false;anchor=-1;}
    void reset(){generation++;clearSelection();stateConnection=null;}
    void selection(int start,int end){selectionRevision++;notifiedStart=start;notifiedEnd=end;if(!selecting)anchor=start;}
    private boolean sameOwner(Owner owner){
        return owner!=null&&owner.connection!=null&&owner.generation==generation
            &&owner.session==ime.editorSession()&&owner.connection==ime.getCurrentInputConnection()
            &&owner.session==ime.editorSession()&&owner.generation==generation;
    }
    private boolean current(Owner owner){return sameOwner(owner)&&owner.selection==selectionRevision;}
    private Owner begin(){
        if(Looper.myLooper()!=Looper.getMainLooper()||busy)return null;busy=true;
        try{
            int epoch=generation,session=ime.editorSession();long revision=selectionRevision;
            Owner owner=new Owner(ime.getCurrentInputConnection(),session,epoch,revision);
            if(!current(owner)){busy=false;return null;}
            if(stateConnection!=owner.connection||stateSession!=owner.session){clearSelection();stateConnection=owner.connection;stateSession=owner.session;}
            return owner;
        }catch(RuntimeException unavailable){busy=false;return null;}
    }
    private static boolean validUtf16(String text){
        for(int i=0;i<text.length();i++){
            char c=text.charAt(i);
            if(Character.isHighSurrogate(c)){if(++i>=text.length()||!Character.isLowSurrogate(text.charAt(i)))return false;}
            else if(Character.isLowSurrogate(c))return false;
        }
        return true;
    }
    private ExtractedText snapshot(Owner owner){return snapshot(owner,false);}
    private ExtractedText snapshot(Owner owner,boolean rejectComposing){
        if(!current(owner))return null;
        ExtractedTextRequest request=new ExtractedTextRequest();request.hintMaxChars=MAX_SNAPSHOT;request.hintMaxLines=10000;request.flags=InputConnection.GET_TEXT_WITH_STYLES;
        ExtractedText value=owner.connection.getExtractedText(request,0);
        // Only partialStartOffset identifies a delta. For a full snapshot,
        // partialEndOffset is unused; Chromium sets it to text.length().
        if(value==null||value.text==null||value.text.length()>MAX_SNAPSHOT||value.startOffset!=0||value.partialStartOffset!=-1)return null;
        if(rejectComposing&&value.text instanceof Spanned){
            Spanned styled=(Spanned)value.text;
            for(Object span:styled.getSpans(0,styled.length(),Object.class))if((styled.getSpanFlags(span)&Spanned.SPAN_COMPOSING)!=0)return null;
        }
        String text=value.text.toString();int start=value.selectionStart,end=value.selectionEnd;
        if(text.length()>MAX_SNAPSHOT||start<0||end<0||start>text.length()||end>text.length()||!validUtf16(text)||!current(owner))return null;
        // Freeze externally supplied mutable CharSequence/snapshot fields before computing offsets.
        ExtractedText frozen=new ExtractedText();frozen.text=text;frozen.selectionStart=start;frozen.selectionEnd=end;
        return frozen;
    }
    private boolean setSelection(Owner owner,int start,int end){
        if(!current(owner))return false;
        long revision=selectionRevision;
        boolean accepted=owner.connection.setSelection(start,end);
        return accepted&&sameOwner(owner)&&(selectionRevision==revision||(notifiedStart==start&&notifiedEnd==end));
    }
    boolean toggle(){
        Owner owner=begin();if(owner==null)return false;
        try{
            ExtractedText value=snapshot(owner);if(value==null)return false;
            if(selecting){int end=value.selectionEnd;if(!setSelection(owner,end,end))return false;selecting=false;anchor=end;return true;}
            if(!current(owner))return false;anchor=value.selectionStart;selecting=true;return true;
        }catch(RuntimeException uncertain){return false;}finally{busy=false;}
    }
    boolean move(int direction,boolean edge){
        Owner owner=begin();if(owner==null)return false;
        try{
            ExtractedText value=snapshot(owner);if(value==null)return false;
            // Selection notifications are asynchronous; use the current bounded host endpoint.
            int current=value.selectionEnd,next;
            if(edge)next=direction<0?0:value.text.length();
            else if(!selecting&&value.selectionStart!=value.selectionEnd)next=direction<0?Math.min(value.selectionStart,value.selectionEnd):Math.max(value.selectionStart,value.selectionEnd);
            else{BreakIterator boundary=BreakIterator.getCharacterInstance(Locale.ROOT);boundary.setText(value.text.toString());next=direction<0?boundary.preceding(current):boundary.following(current);if(next==BreakIterator.DONE)next=current;}
            int from=selecting?(anchor<0?value.selectionStart:anchor):next;
            if(from<0||from>value.text.length()||!setSelection(owner,from,next))return false;
            anchor=selecting?from:next;return true;
        }catch(RuntimeException uncertain){return false;}finally{busy=false;}
    }
    boolean vertical(int code){
        Owner owner=begin();if(owner==null)return false;
        try{
            if(!current(owner))return false;
            int meta=selecting?KeyEvent.META_SHIFT_ON:0;long now=android.os.SystemClock.uptimeMillis();
            boolean down=false,up=false;
            try{down=owner.connection.sendKeyEvent(new KeyEvent(now,now,KeyEvent.ACTION_DOWN,code,0,meta));}
            finally{
                // Release only the captured connection, even if the down callback changed owners.
                up=owner.connection.sendKeyEvent(new KeyEvent(now,now,KeyEvent.ACTION_UP,code,0,meta));
            }
            return down&&up&&sameOwner(owner);
        }catch(RuntimeException uncertain){return false;}finally{busy=false;}
    }
    static final class Deletion {final boolean accepted,changed,newline;Deletion(boolean a,boolean c,boolean n){accepted=a;changed=c;newline=n;}}
    private static Deletion rejected(){return new Deletion(false,false,false);}
    boolean delete(boolean forward){return deleteDetails(forward).accepted;}
    Deletion deleteDetails(boolean forward){
        Owner owner=begin();if(owner==null)return rejected();
        try{
            // Separate surrounding/selected-text APIs may withhold or truncate data,
            // even below their requested limits. Only a complete bounded snapshot
            // can establish both selection endpoints and Unicode grapheme context.
            ExtractedText value=snapshot(owner);if(value==null)return rejected();
            String text=value.text.toString();int start=Math.min(value.selectionStart,value.selectionEnd),end=Math.max(value.selectionStart,value.selectionEnd);
            BreakIterator boundary=BreakIterator.getCharacterInstance(Locale.ROOT);boundary.setText(text);
            if(!boundary.isBoundary(start)||!boundary.isBoundary(end))return rejected();
            int next=start!=end?end:forward?boundary.following(end):boundary.preceding(start);
            if(next==BreakIterator.DONE)return current(owner)?new Deletion(true,false,false):rejected();
            if(!current(owner))return rejected();
            // Composing markers need not survive cross-process styled-text copies.
            // Acknowledge ending composition, then require unchanged text/selection;
            // a false/throwing finish is never followed by a guessed deletion.
            if(!owner.connection.finishComposingText()||!sameOwner(owner))return rejected();
            owner=new Owner(owner.connection,owner.session,owner.generation,selectionRevision);
            ExtractedText after=snapshot(owner,true);
            if(after==null||value.selectionStart!=after.selectionStart||value.selectionEnd!=after.selectionEnd||!text.equals(after.text.toString())||!current(owner))return rejected();
            if(start!=end){
                String removed=text.substring(start,end);clearSelection();
                boolean accepted=owner.connection.commitText("",1)&&sameOwner(owner);
                return new Deletion(accepted,accepted,removed.indexOf('\n')>=0||removed.indexOf('\r')>=0);
            }
            int size=Math.abs(next-start);if(size<1)return rejected();
            String removed=text.substring(Math.min(start,next),Math.max(start,next));clearSelection();
            boolean accepted=owner.connection.deleteSurroundingText(forward?0:size,forward?size:0)&&sameOwner(owner);
            return new Deletion(accepted,accepted,removed.indexOf('\n')>=0||removed.indexOf('\r')>=0);
        }catch(RuntimeException uncertain){return rejected();}finally{busy=false;}
    }
    boolean menu(int id){
        Owner owner=begin();if(owner==null)return false;
        try{
            if(!current(owner))return false;
            boolean accepted=owner.connection.performContextMenuAction(id);
            if(!sameOwner(owner))return false;
            if(accepted&&id==android.R.id.selectAll)clearSelection();
            return accepted;
        }catch(RuntimeException uncertain){return false;}finally{busy=false;}
    }
}
