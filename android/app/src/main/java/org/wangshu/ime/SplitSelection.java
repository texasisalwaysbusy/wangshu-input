package org.wangshu.ime;

import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Ephemeral, last-observed insertion spans; not an atomic host transaction. */
final class SplitSelection {
    static final class Snapshot {final String text;final int start,end;Snapshot(String t,int s,int e){text=t;start=s;end=e;}}
    interface Editor {Snapshot read();boolean replace(int start,int end,String value,int caretAfter);default boolean current(){return true;}}
    private static final class Span {int start;final String text;Span(int at,String value){start=at;text=value;}}
    private final Editor editor;private final LinkedHashMap<Integer,Span> selected=new LinkedHashMap<>();
    private final AtomicBoolean toggleInFlight=new AtomicBoolean();
    private String expected;private int caret;private volatile boolean valid;
    SplitSelection(Editor host){editor=host;Snapshot s=readSafely();valid=s!=null&&s.text!=null&&s.start==s.end&&s.text.length()<=32768&&s.start>=0&&s.start<=s.text.length();if(valid){java.text.BreakIterator boundary=java.text.BreakIterator.getCharacterInstance(Locale.ROOT);boundary.setText(s.text);valid=boundary.isBoundary(s.start);}if(valid){expected=s.text;caret=s.end;}}
    private boolean invalidate(){
        valid=false;expected=null;caret=0;
        synchronized(selected){selected.clear();}
        return false;
    }
    private Snapshot readSafely(){
        try{if(editor.current()){Snapshot value=editor.read();if(editor.current())return value;}}
        catch(RuntimeException uncertain){/* Read failure does not prove a prior edit was absent. */}
        invalidate();return null;
    }
    private boolean replaceSafely(int start,int end,String text,int after){
        try{if(editor.current()&&editor.replace(start,end,text,after)&&editor.current())return true;}
        catch(RuntimeException uncertain){/* A request may already have applied. */}
        return invalidate();
    }
    private boolean observeAfter(String text,int caretAfter){
        // One bounded snapshot request, no polling/retry/rollback. The Android IPC
        // itself may block; exact readback is an observation, not immutable identity.
        Snapshot after=readSafely();
        if(after==null||!text.equals(after.text)||after.start!=caretAfter||after.end!=caretAfter)return invalidate();
        return true;
    }
    boolean ready(){return !toggleInFlight.get()&&valid;}
    boolean selected(int id){synchronized(selected){return ready()&&selected.containsKey(id);}}
    String selectedText(){synchronized(selected){if(!ready())return "";StringBuilder text=new StringBuilder();for(Span span:selected.values())text.append(span.text);return text.toString();}}
    boolean toggle(int id,String text){
        // A nested/concurrent click is BUSY, not evidence that the outer edit failed.
        // Never hold the selected-state monitor across a call into the host.
        if(!toggleInFlight.compareAndSet(false,true))return false;
        try{
            if(!valid)return false;
            Snapshot actual=readSafely();
            if(!valid||actual==null||!expected.equals(actual.text)||actual.start!=caret||actual.end!=caret)return invalidate();
            Span old=selected.get(id);
            if(old==null){
                if(text==null||text.isEmpty()||text.length()>32768-expected.length())return false;
                int insertedAt=caret,nextCaret=caret+text.length();
                String next=expected.substring(0,caret)+text+expected.substring(caret);
                if(!replaceSafely(caret,caret,text,nextCaret)||!observeAfter(next,nextCaret))return false;
                synchronized(selected){selected.put(id,new Span(insertedAt,text));}
                expected=next;caret=nextCaret;return true;
            }
            if(!expected.regionMatches(old.start,old.text,0,old.text.length()))return invalidate();
            int nextCaret=caret-old.text.length();
            String next=expected.substring(0,old.start)+expected.substring(old.start+old.text.length());
            if(!replaceSafely(old.start,old.start+old.text.length(),"",nextCaret)||!observeAfter(next,nextCaret))return false;
            synchronized(selected){selected.remove(id);for(Span span:selected.values())if(span.start>old.start)span.start-=old.text.length();}
            expected=next;caret=nextCaret;return true;
        }finally{toggleInFlight.set(false);}
    }
}
