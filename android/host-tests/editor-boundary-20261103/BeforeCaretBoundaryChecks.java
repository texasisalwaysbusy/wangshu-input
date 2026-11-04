package org.wangshu.ime;
import android.view.inputmethod.*;
public final class BeforeCaretBoundaryChecks {
 static int checks;static void check(boolean b,String s){if(!b)throw new AssertionError(s);checks++;}
 static class F implements BeforeCaretEditor.Provider,InputConnection {String text="abcXYZ";int start=3,end=3,begins,ends,commits,selections;long session=1;boolean chromeSnapshot;int partialStart=-1;boolean privateInput,rejectBegin,throwBegin,throwCommit,throwEnd,falseCommit;BeforeCaretEditor e=new BeforeCaretEditor(this);public BeforeCaretEditor.InputSession current(){return new BeforeCaretEditor.InputSession(this,session,privateInput);}public ExtractedText getExtractedText(ExtractedTextRequest r,int f){ExtractedText x=new ExtractedText();x.text=text;x.partialStartOffset=partialStart;if(chromeSnapshot)x.partialEndOffset=text.length();x.selectionStart=start;x.selectionEnd=end;return x;}public boolean beginBatchEdit(){begins++;if(throwBegin)throw new IllegalStateException();return !rejectBegin;}public boolean endBatchEdit(){ends++;if(throwEnd)throw new IllegalStateException();return false;}public boolean setSelection(int a,int b){selections++;start=a;end=b;return true;}public boolean commitText(CharSequence v,int p){commits++;text=text.substring(0,start)+v+text.substring(end);start=end=start+v.length();if(throwCommit)throw new IllegalStateException();return !falseCommit;}}
 public static void main(String[] args){
  F f=new F();check(f.e.deleteBeforeCaret().state==BeforeCaretEditor.State.APPLIED&&f.text.equals("XYZ"),"exact prefix delete");check(f.begins==1&&f.ends==1&&f.commits==1,"one begin/end/write");check(f.e.undo().state==BeforeCaretEditor.State.APPLIED&&f.text.equals("abcXYZ"),"exact undo");check(!f.e.hasUndo(),"one undo only");
  f=new F();f.rejectBegin=true;check(f.e.deleteBeforeCaret().state==BeforeCaretEditor.State.REJECTED&&f.selections==0&&f.commits==0&&f.ends==1,"rejected begin balances attempted call per InputConnection contract, no text write");
  f=new F();f.throwBegin=true;check(f.e.deleteBeforeCaret().state==BeforeCaretEditor.State.REJECTED&&f.ends==1&&f.commits==0,"throwing begin cleanup attempted once, no text write");
  f=new F();f.throwCommit=true;check(f.e.deleteBeforeCaret().state==BeforeCaretEditor.State.UNCERTAIN&&f.commits==1&&f.ends==1&&!f.e.hasUndo(),"throw-after-write never retries or creates undo");
  f=new F();f.throwEnd=true;check(f.e.deleteBeforeCaret().state==BeforeCaretEditor.State.UNCERTAIN&&!f.e.hasUndo(),"throwing end cannot certify success");
  f=new F();f.falseCommit=true;check(f.e.deleteBeforeCaret().state==BeforeCaretEditor.State.UNCERTAIN&&f.commits==1&&!f.e.hasUndo(),"false commit after application remains uncertain");
  f=new F();f.privateInput=true;check(f.e.prepareDelete()==null&&f.e.deleteBeforeCaret().state==BeforeCaretEditor.State.REJECTED&&f.begins==0,"private scope does not start edit");
  f=new F();BeforeCaretEditor.Preview p=f.e.prepareDelete();f.session++;check(!f.e.matchesPreview(p)&&f.e.deleteBeforeCaret(p).state==BeforeCaretEditor.State.REJECTED&&f.begins==0,"preview bound to session");
  f=new F();f.chromeSnapshot=true;BeforeCaretEditor.Preview chromium=f.e.prepareDelete();check(chromium!=null&&f.e.deleteBeforeCaret(chromium).state==BeforeCaretEditor.State.APPLIED&&f.text.equals("XYZ"),"Chromium full snapshot prefix deletion");check(f.e.undo().state==BeforeCaretEditor.State.APPLIED&&f.text.equals("abcXYZ"),"Chromium full snapshot exact undo");
  f=new F();f.chromeSnapshot=true;f.partialStart=0;check(f.e.prepareDelete()==null&&f.e.deleteBeforeCaret().state==BeforeCaretEditor.State.REJECTED&&f.commits==0,"partial positive end still rejected");
  f=new F();f.partialStart=-2;check(f.e.prepareDelete()==null&&f.e.deleteBeforeCaret().state==BeforeCaretEditor.State.REJECTED&&f.commits==0,"unknown partial sentinel rejected");
  System.out.println("PASS before-caret "+checks+" synthetic checks; native acceptance NOT_RUN");
 }
}
