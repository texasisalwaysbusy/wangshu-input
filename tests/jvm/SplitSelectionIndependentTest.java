package org.wangshu.ime;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Non-author boundary review of the actual production model; no Android or clipboard access. */
public final class SplitSelectionIndependentTest {
    interface Case {void run()throws Exception;}
    static final class Host implements SplitSelection.Editor {
        String text;int start,end,calls,readLimit=Integer.MAX_VALUE;boolean unavailable,reject,applyThenReject;
        Host(String value,int caret){text=value;start=end=caret;}
        public SplitSelection.Snapshot read(){return unavailable||text.length()>readLimit?null:new SplitSelection.Snapshot(text,start,end);}
        public boolean replace(int a,int b,String value,int caretAfter){calls++;if(reject)return false;text=text.substring(0,a)+value+text.substring(b);start=end=applyThenReject?a+value.length():caretAfter;return !applyThenReject;}
    }
    static int passed,failed;static Host current;static String original;static final List<String> details=new ArrayList<>();
    static Host host(String text,int caret){original=text;return current=new Host(text,caret);}
    static void require(boolean ok,String why){if(!ok)throw new AssertionError(why);}
    static String units(String text){StringBuilder result=new StringBuilder();int limit=Math.min(text.length(),80);for(int i=0;i<limit;i++){char c=text.charAt(i);if(c>=32&&c<127&&c!='\\')result.append(c);else result.append(String.format(Locale.ROOT,"\\u%04X",(int)c));}if(text.length()>limit)result.append("...(UTF16 length ").append(text.length()).append(')');return result.toString();}
    static boolean validUtf16(String text){for(int i=0;i<text.length();i++){char c=text.charAt(i);if(Character.isHighSurrogate(c)){if(++i>=text.length()||!Character.isLowSurrogate(text.charAt(i)))return false;}else if(Character.isLowSurrogate(c))return false;}return true;}
    static void run(String id,String invariant,Case test){current=null;original=null;String state="PASS",reason="";try{test.run();passed++;}catch(Throwable failure){failed++;state="FAIL";reason=failure.getClass().getSimpleName()+": "+failure.getMessage();}System.out.println(state+" "+id+" "+invariant+(reason.isEmpty()?"":" -- "+reason));if(current!=null)details.add(id+"\t"+state+"\t"+current.calls+"\t"+original.length()+"\t"+current.text.length()+"\t"+validUtf16(current.text)+"\t"+units(original)+"\t"+units(current.text)+"\t"+reason);}
    static String repeat(char c,int n){char[] value=new char[n];Arrays.fill(value,c);return new String(value);}
    public static void main(String[] args)throws Exception {
        run("ID-01","duplicate non-BMP blocks preserve preexisting identical host text",()->{
            String token="👩🏽‍🔬",base=token+"|"+token;Host h=host(base,token.length()+1);SplitSelection s=new SplitSelection(h);
            require(s.toggle(41,token)&&s.toggle(99,token)&&s.toggle(41,token),"duplicate insertion sequence rejected");require(h.text.equals(token+"|"+token+token),"first withdrawal changed an original or the other identity");require(s.selected(99)&&!s.selected(41),"selection identity mismatch");require(s.toggle(99,token)&&h.text.equals(base),"final withdrawal did not restore exact host");
        });
        run("ID-02","middle then first withdrawal rebases remaining UTF16 ranges",()->{
            String base="HEAD|α𝕏βTAIL";Host h=host(base,5);SplitSelection s=new SplitSelection(h);require(s.toggle(1,"α")&&s.toggle(2,"𝕏")&&s.toggle(3,"β"),"initial append failed");require(s.toggle(2,"𝕏")&&h.text.equals("HEAD|αβα𝕏βTAIL"),"middle withdrawal wrong");require(s.toggle(1,"α")&&h.text.equals("HEAD|βα𝕏βTAIL"),"first withdrawal wrong");require(s.toggle(3,"β")&&h.text.equals(base)&&h.start==5&&h.end==5,"last identity or caret not restored");
        });
        run("HOST-01","observed external caret movement blocks undo and later stale retries",()->{
            Host h=host("a|b",2);SplitSelection s=new SplitSelection(h);require(s.toggle(1,"same"),"initial insert failed");String stable=h.text;h.start=h.end=0;require(!s.toggle(1,"same")&&h.text.equals(stable)&&h.calls==1,"external caret did not block write");h.start=h.end=6;require(!s.toggle(1,"same")&&h.calls==1,"invalidated session resumed after caret restoration");
        });
        run("HOST-02","same-length external text change blocks removal",()->{
            Host h=host("aa|bb",3);SplitSelection s=new SplitSelection(h);require(s.toggle(1,"X"),"initial insert failed");h.text="za|Xbb";String changed=h.text;require(!s.toggle(1,"X")&&h.text.equals(changed)&&h.calls==1,"same-length external edit was overwritten");
        });
        run("HOST-03","unavailable host snapshot blocks later removal",()->{
            Host h=host("p|q",2);SplitSelection s=new SplitSelection(h);require(s.toggle(1,"X"),"initial insert failed");h.unavailable=true;String stable=h.text;require(!s.toggle(1,"X")&&h.text.equals(stable)&&h.calls==1,"unavailable host was edited");
        });
        run("HOST-04","a rejected attempt requires a new session before retry",()->{
            Host h=host("p|q",2);h.reject=true;SplitSelection s=new SplitSelection(h);require(!s.toggle(1,"X")&&!s.selected(1)&&h.text.equals("p|q"),"rejected edit altered tracking or text");h.reject=false;require(!s.toggle(1,"X")&&h.calls==1,"unconfirmed attempt must not retry in the old session");s=new SplitSelection(h);require(s.toggle(1,"X")&&h.text.equals("p|Xq")&&s.toggle(1,"X")&&h.text.equals("p|q"),"fresh session roundtrip failed");
        });
        run("HOST-05","partially applied host failure cannot trigger a second stale edit",()->{
            Host h=host("p|q",2);h.applyThenReject=true;SplitSelection s=new SplitSelection(h);require(!s.toggle(1,"X")&&h.text.equals("p|Xq"),"fixture did not reproduce final-caret failure after commit");require(!s.toggle(1,"X")&&h.calls==1&&h.text.equals("p|Xq"),"partial host side effect was blindly retried or rolled back");
        });
        run("UNICODE-01","initial caret inside a surrogate pair refuses insertion",()->{
            String base="A𝕏Z";Host h=host(base,2);SplitSelection s=new SplitSelection(h);boolean inserted=s.toggle(1,"x");require(!inserted&&h.text.equals(base)&&h.calls==0&&validUtf16(h.text),"interior UTF16 caret was accepted and can split a scalar");
        });
        run("UNICODE-02","initial caret inside an existing ZWJ glyph refuses insertion",()->{
            String base="A👩‍🔬Z";Host h=host(base,3);SplitSelection s=new SplitSelection(h);boolean inserted=s.toggle(1,"x");require(!inserted&&h.text.equals(base)&&h.calls==0,"scalar-boundary caret inside one ZWJ grapheme was accepted");
        });
        run("UNICODE-03","initial caret between base letter and combining mark refuses insertion",()->{
            String base="Ae\u0301Z";Host h=host(base,2);SplitSelection s=new SplitSelection(h);boolean inserted=s.toggle(1,"x");require(!inserted&&h.text.equals(base)&&h.calls==0,"caret inside preexisting accented grapheme was accepted");
        });
        run("BOUND-01","known snapshot ceiling is checked before creating an un-undoable insert",()->{
            String base=repeat('a',32768);Host h=host(base,base.length());h.readLimit=32768;SplitSelection s=new SplitSelection(h);boolean inserted=s.toggle(1,"x");boolean undone=inserted&&s.toggle(1,"x");require(!inserted&&h.text.equals(base)&&h.calls==0,"inserted="+inserted+", undo="+undone+"; operation crossed the production snapshot bound");
        });
        run("UNICODE-04","valid boundary after a whole modified emoji permits exact roundtrip",()->{
            String prefix="A👩🏽‍🔬",base=prefix+"Z";Host h=host(base,prefix.length());SplitSelection s=new SplitSelection(h);require(s.toggle(1,"β")&&s.toggle(1,"β")&&h.text.equals(base)&&h.start==prefix.length(),"valid whole-grapheme boundary was unnecessarily rejected");
        });
        run("UNICODE-05","undo of a newly inserted combining mark preserves the old base",()->{
            Host h=host("AeZ",2);SplitSelection s=new SplitSelection(h);require(s.toggle(1,"\u0301")&&h.text.equals("Ae\u0301Z")&&s.toggle(1,"\u0301")&&h.text.equals("AeZ"),"provenance-based accent withdrawal changed preexisting base text");
        });
        if(args.length==1){Path folder=Paths.get(args[0]);Files.createDirectories(folder);List<String> lines=new ArrayList<>();lines.add("case\tstate\treplace_calls\tbefore_utf16_length\tafter_utf16_length\tafter_well_formed_utf16\tbefore_utf16\tafter_utf16\treason");lines.addAll(details);Files.write(folder.resolve("case-details.tsv"),lines,StandardCharsets.UTF_8);}
        System.out.println("RESULT cases="+(passed+failed)+" passed="+passed+" failed="+failed+"; pure production SplitSelection model only; Android UI/clipboard/real device NOT_RUN");if(failed>0)System.exit(1);
    }
}
