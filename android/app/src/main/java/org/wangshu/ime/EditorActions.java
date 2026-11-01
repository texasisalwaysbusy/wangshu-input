package org.wangshu.ime;

import android.icu.text.BreakIterator;
import android.view.KeyEvent;
import android.view.inputmethod.*;
import java.util.Locale;

/** Bounded snapshot navigation. Never guesses offsets when a host withholds text. */
final class EditorActions {
    private final WangshuIme ime;
    boolean selecting;
    private int anchor=-1;
    EditorActions(WangshuIme service){ime=service;}
    void reset(){selecting=false;anchor=-1;}
    void selection(int start,int end){if(!selecting)anchor=start;}
    private ExtractedText snapshot(){InputConnection c=ime.getCurrentInputConnection();if(c==null)return null;ExtractedTextRequest r=new ExtractedTextRequest();r.hintMaxChars=100000;r.hintMaxLines=10000;ExtractedText x=c.getExtractedText(r,0);return x!=null&&x.text!=null&&x.startOffset==0&&x.partialStartOffset<0&&x.partialEndOffset<0&&x.selectionStart>=0&&x.selectionEnd>=0&&x.selectionStart<=x.text.length()&&x.selectionEnd<=x.text.length()?x:null;}
    boolean toggle(){ExtractedText x=snapshot();if(x==null)return false;InputConnection c=ime.getCurrentInputConnection();if(selecting){int end=x.selectionEnd;selecting=false;anchor=end;return c.setSelection(end,end);}anchor=x.selectionStart;selecting=true;return true;}
    boolean move(int direction,boolean edge){ExtractedText x=snapshot();InputConnection c=ime.getCurrentInputConnection();if(x==null||c==null)return false;
        // Selection notifications arrive asynchronously. An older notification
        // must not overwrite the end of a newer setSelection request. Read the
        // host's current snapshot instead of a cached callback endpoint.
        int current=x.selectionEnd;int next;
        if(edge)next=direction<0?0:x.text.length();
        else if(!selecting&&x.selectionStart!=x.selectionEnd)next=direction<0?Math.min(x.selectionStart,x.selectionEnd):Math.max(x.selectionStart,x.selectionEnd);
        else{BreakIterator b=BreakIterator.getCharacterInstance(Locale.ROOT);b.setText(x.text.toString());next=direction<0?b.preceding(current):b.following(current);if(next==BreakIterator.DONE)next=current;}
        if(selecting){if(anchor<0)anchor=x.selectionStart;if(anchor>x.text.length())return false;return c.setSelection(anchor,next);}anchor=next;return c.setSelection(next,next);
    }
    boolean vertical(int code){InputConnection c=ime.getCurrentInputConnection();if(c==null)return false;int meta=selecting?KeyEvent.META_SHIFT_ON:0;long now=android.os.SystemClock.uptimeMillis();boolean down=c.sendKeyEvent(new KeyEvent(now,now,KeyEvent.ACTION_DOWN,code,0,meta));boolean up=c.sendKeyEvent(new KeyEvent(now,now,KeyEvent.ACTION_UP,code,0,meta));return down&&up;}
    static final class Deletion {final boolean accepted,changed,newline;Deletion(boolean a,boolean c,boolean n){accepted=a;changed=c;newline=n;}}
    boolean delete(boolean forward){return deleteDetails(forward).accepted;}
    Deletion deleteDetails(boolean forward){
        InputConnection c=ime.getCurrentInputConnection();if(c==null)return new Deletion(false,false,false);CharSequence selected=c.getSelectedText(0);
        CharSequence before=c.getTextBeforeCursor(4096,0),after=c.getTextAfterCursor(4096,0);if(before==null||after==null)return new Deletion(false,false,false);
        // Validate both ends of an existing selection too: a host can expose a
        // programmatic range containing only half a surrogate or ZWJ cluster.
        String chosen=selected==null?"":selected.toString(),context=before.toString()+chosen+after;int caret=before.length();BreakIterator b=BreakIterator.getCharacterInstance(Locale.ROOT);b.setText(context);if(!b.isBoundary(caret)||!b.isBoundary(caret+chosen.length()))return new Deletion(false,false,false);
        if(!chosen.isEmpty()){reset();boolean ok=c.commitText("",1);return new Deletion(ok,ok,chosen.indexOf('\n')>=0||chosen.indexOf('\r')>=0);}
        int next=forward?b.following(caret):b.preceding(caret);if(next==BreakIterator.DONE)return new Deletion(true,false,false);int size=Math.abs(next-caret);if(size<1||size>=4096)return new Deletion(false,false,false);String removed=context.substring(Math.min(caret,next),Math.max(caret,next));reset();boolean ok=c.deleteSurroundingText(forward?0:size,forward?size:0);return new Deletion(ok,ok,removed.indexOf('\n')>=0||removed.indexOf('\r')>=0);
    }
    boolean menu(int id){InputConnection c=ime.getCurrentInputConnection();boolean ok=c!=null&&c.performContextMenuAction(id);if(id==android.R.id.selectAll)selecting=false;return ok;}
}
