package org.wangshu.ime;

import java.util.*;

/** Ephemeral insertion identity and exact-host guards; never global string replacement. */
final class SplitSelection {
    static final class Snapshot {final String text;final int start,end;Snapshot(String t,int s,int e){text=t;start=s;end=e;}}
    interface Editor {Snapshot read();boolean replace(int start,int end,String value,int caretAfter);}
    private static final class Span {int start;final String text;Span(int at,String value){start=at;text=value;}}
    private final Editor editor;private final LinkedHashMap<Integer,Span> selected=new LinkedHashMap<>();
    private String expected;private int caret;private boolean valid;
    SplitSelection(Editor host){editor=host;Snapshot s=readSafely();valid=s!=null&&s.text!=null&&s.start==s.end&&s.text.length()<=32768&&s.start>=0&&s.start<=s.text.length();if(valid){java.text.BreakIterator boundary=java.text.BreakIterator.getCharacterInstance(Locale.ROOT);boundary.setText(s.text);valid=boundary.isBoundary(s.start);}if(valid){expected=s.text;caret=s.end;}}
    private Snapshot readSafely(){try{return editor.read();}catch(RuntimeException uncertain){valid=false;return null;}}
    private boolean replaceSafely(int start,int end,String text,int after){try{if(editor.replace(start,end,text,after))return true;}catch(RuntimeException uncertain){/* The editor may already have changed. */}valid=false;return false;}
    boolean selected(int id){return selected.containsKey(id);}
    String selectedText(){StringBuilder text=new StringBuilder();for(Span span:selected.values())text.append(span.text);return text.toString();}
    boolean toggle(int id,String text){
        if(!valid)return false;Snapshot actual=readSafely();if(!valid||actual==null||!expected.equals(actual.text)||actual.start!=caret||actual.end!=caret){valid=false;return false;}
        Span old=selected.get(id);if(old==null){if(text==null||text.isEmpty()||text.length()>32768-expected.length())return false;if(!replaceSafely(caret,caret,text,caret+text.length()))return false;selected.put(id,new Span(caret,text));expected=expected.substring(0,caret)+text+expected.substring(caret);caret+=text.length();return true;}
        if(!expected.regionMatches(old.start,old.text,0,old.text.length())||!replaceSafely(old.start,old.start+old.text.length(),"",caret-old.text.length()))return false;
        expected=expected.substring(0,old.start)+expected.substring(old.start+old.text.length());selected.remove(id);for(Span span:selected.values())if(span.start>old.start)span.start-=old.text.length();caret-=old.text.length();return true;
    }
}
