package org.wangshu.ime;
import android.text.Spanned;import android.view.*;import android.view.inputmethod.*;
public final class EditorBoundaryChecks {
 static int checks,failures;static void check(boolean b,String s){checks++;if(!b)failures++;System.out.println((b?"PASS ":"FAIL ")+s);}
 static final class Fixture {final WangshuIme ime=new WangshuIme();final Conn first=new Conn(this),second=new Conn(this);final EditorActions actions=new EditorActions(ime);Fixture(){ime.connection=first;}void swap(){ime.connection=second;ime.inputSession++;}}
 static final class Conn implements InputConnection {
  final Fixture f;String text="abcXYZ";int start=3,end=3,extracts,selections,commits,deletes,selectedReads,beforeReads,afterReads,downs,ups,menus;Runnable extractHook,selectedHook,beforeHook,afterHook,selectionHook,keyHook,menuHook;boolean reject,throwExtract,throwSelected,throwSelection,omitExtract,partial;CharSequence styled;int requestFlags,finishes;boolean rejectFinish,throwFinish;boolean chromeSnapshot;int partialStart=-1,startOffset;Runnable finishHook;
  Conn(Fixture f){this.f=f;}void once(int kind){Runnable r=kind==0?extractHook:kind==1?selectedHook:kind==2?beforeHook:afterHook;if(kind==0)extractHook=null;else if(kind==1)selectedHook=null;else if(kind==2)beforeHook=null;else afterHook=null;if(r!=null)r.run();}
  public ExtractedText getExtractedText(ExtractedTextRequest r,int flags){extracts++;requestFlags=r.flags;if(throwExtract)throw new IllegalStateException();if(omitExtract)return null;ExtractedText x=new ExtractedText();x.text=styled==null?text:styled;x.startOffset=startOffset;x.partialStartOffset=partialStart;if(chromeSnapshot)x.partialEndOffset=text.length();if(partial)x.partialStartOffset=0;x.selectionStart=start;x.selectionEnd=end;once(0);return x;}
  public CharSequence getSelectedText(int flags){selectedReads++;if(throwSelected)throw new IllegalStateException();String s=text.substring(Math.min(start,end),Math.max(start,end));once(1);return s.isEmpty()?null:s;}
  public CharSequence getTextBeforeCursor(int n,int flags){beforeReads++;String s=text.substring(Math.max(0,Math.min(start,end)-n),Math.min(start,end));once(2);return s;}
  public CharSequence getTextAfterCursor(int n,int flags){afterReads++;String s=text.substring(Math.max(start,end),Math.min(text.length(),Math.max(start,end)+n));once(3);return s;}
  public boolean setSelection(int a,int b){selections++;if(throwSelection)throw new IllegalStateException();if(!reject){start=a;end=b;}if(selectionHook!=null){Runnable r=selectionHook;selectionHook=null;r.run();}return !reject;}
  public boolean finishComposingText(){finishes++;if(throwFinish)throw new IllegalStateException();if(finishHook!=null)finishHook.run();return !rejectFinish;}
  public boolean commitText(CharSequence v,int p){commits++;text=text.substring(0,Math.min(start,end))+v+text.substring(Math.max(start,end));start=end=Math.min(start,end)+v.length();return !reject;}
  public boolean deleteSurroundingText(int b,int a){deletes++;text=text.substring(0,start-b)+text.substring(end+a);start-=b;end=start;return !reject;}
  public boolean sendKeyEvent(KeyEvent e){if(e.action==KeyEvent.ACTION_DOWN){downs++;if(keyHook!=null){Runnable r=keyHook;keyHook=null;r.run();}}else ups++;return !reject;}
  public boolean performContextMenuAction(int id){menus++;if(menuHook!=null)menuHook.run();return !reject;}
 }
 static final class Styled implements Spanned {final String text;Styled(String t){text=t;}public int length(){return text.length();}public char charAt(int n){return text.charAt(n);}public CharSequence subSequence(int a,int b){return text.subSequence(a,b);}public String toString(){return text;}public Object[] getSpans(int a,int b,Class<?> c){return new Object[]{this};}public int getSpanFlags(Object o){return SPAN_COMPOSING;}}
 interface Operation {boolean run(Fixture f);}
 static void attempt(String name,Operation op){try{check(op.run(new Fixture()),name);}catch(RuntimeException e){check(false,name+" (threw "+e.getClass().getSimpleName()+")");}}
 public static void main(String[] args){
  attempt("move never writes a replacement connection after extraction",f->{f.first.extractHook=f::swap;return !f.actions.move(1,false)&&f.first.selections==0&&f.second.selections==0;});
  attempt("same connection new session rejects stale snapshot",f->{f.first.extractHook=()->f.ime.inputSession++;return !f.actions.move(1,false)&&f.first.selections==0;});
  attempt("connection lookup reentry cannot recapture new owner",f->{f.ime.onConnection=f::swap;return !f.actions.move(1,false)&&f.first.extracts==0&&f.second.selections==0;});
  attempt("lifecycle reset during extraction invalidates move",f->{f.first.extractHook=f.actions::reset;return !f.actions.move(1,false)&&f.first.selections==0;});
  attempt("toggle collapse never writes replacement editor",f->{f.actions.toggle();f.first.extractHook=f::swap;return !f.actions.toggle()&&f.first.selections==0&&f.second.selections==0;});
  attempt("delete never guesses a range from separate selected/surrounding reads",f->{f.first.start=1;f.first.end=3;f.first.selectedHook=f::swap;return f.actions.delete(false)&&f.first.selectedReads==0&&f.first.beforeReads==0&&f.first.afterReads==0&&f.first.commits==1;});
  attempt("delete owner switch during full snapshot prevents every write",f->{f.first.extractHook=f::swap;return !f.actions.delete(false)&&f.first.deletes==0&&f.first.commits==0;});
  attempt("delete same-connection new session during snapshot prevents write",f->{f.first.extractHook=()->f.ime.inputSession++;return !f.actions.delete(false)&&f.first.deletes==0;});
  attempt("reentrant move cannot duplicate editor writes",f->{final boolean[] nested={true};f.first.extractHook=()->nested[0]=f.actions.move(1,false);return f.actions.move(1,false)&&!nested[0]&&f.first.selections==1;});
  attempt("snapshot exception rejects without crash",f->{f.first.throwExtract=true;return !f.actions.move(1,false)&&f.first.selections==0;});
  attempt("unavailable selected-text API does not trigger an unsafe fallback",f->{f.first.throwSelected=true;return f.actions.delete(false)&&f.first.selectedReads==0&&f.first.deletes==1;});
  attempt("selection exception is not retried",f->{f.first.throwSelection=true;return !f.actions.move(1,false)&&f.first.selections==1;});
  attempt("same-owner selection drift during snapshot never deletes new range",f->{f.first.start=1;f.first.end=3;f.first.extractHook=()->{f.first.start=4;f.first.end=6;f.actions.selection(4,6);};return !f.actions.delete(false)&&f.first.commits==0&&f.first.deletes==0;});
  attempt("same-owner selection drift during extraction cannot overwrite new caret",f->{f.first.extractHook=()->{f.first.start=f.first.end=5;f.actions.selection(5,5);};return !f.actions.move(1,false)&&f.first.selections==0&&f.first.end==5;});
  attempt("expected synchronous selection callback preserves successful navigation",f->{f.first.selectionHook=()->f.actions.selection(f.first.start,f.first.end);return f.actions.move(1,false)&&f.first.end==4;});
  attempt("unexpected synchronous selection callback is not confirmed success",f->{f.first.selectionHook=()->{f.first.start=f.first.end=1;f.actions.selection(1,1);};return !f.actions.move(1,false)&&f.first.selections==1;});
  attempt("truncated RI context cannot invent a flag boundary for forward delete",f->{f.first.text="🇦".repeat(2051);f.first.start=f.first.end=4098;return !f.actions.delete(true)&&f.first.deletes==0;});
  attempt("truncated RI context cannot invent a flag boundary for backspace",f->{f.first.text="🇦".repeat(2051);f.first.start=f.first.end=4098;return !f.actions.delete(false)&&f.first.deletes==0;});
  attempt("long complete snapshot permits safe ordinary backspace",f->{f.first.text="a".repeat(12000);f.first.start=f.first.end=6000;return f.actions.delete(false)&&f.first.text.length()==11999&&f.first.extracts==2;});
  attempt("long text above complete-snapshot bound fails closed",f->{f.first.text="a".repeat(100001);f.first.start=f.first.end=6000;return !f.actions.delete(false)&&f.first.deletes==0;});
  attempt("withheld full snapshot fails closed without surrounding-text fallback",f->{f.first.omitExtract=true;return !f.actions.delete(false)&&f.first.deletes==0&&f.first.selectedReads==0&&f.first.beforeReads==0;});
  attempt("partial full snapshot fails closed",f->{f.first.partial=true;return !f.actions.delete(false)&&f.first.deletes==0;});
  attempt("composing span snapshot never replaces composing text on delete",f->{f.first.start=1;f.first.end=3;f.first.styled=new Styled(f.first.text);return !f.actions.delete(false)&&f.first.commits==0&&f.first.requestFlags==InputConnection.GET_TEXT_WITH_STYLES;});
  attempt("off-main editing is rejected before any read or write",f->{final boolean[] value={true};Thread thread=new Thread(()->value[0]=f.actions.move(1,false));thread.start();try{thread.join(2000);}catch(InterruptedException e){throw new IllegalStateException(e);}return !thread.isAlive()&&!value[0]&&f.first.extracts==0&&f.first.selections==0;});
  attempt("composition finish refusal never sends a text deletion",f->{f.first.rejectFinish=true;return !f.actions.delete(false)&&f.first.finishes==1&&f.first.deletes==0;});
  attempt("composition finish exception never retries or deletes",f->{f.first.throwFinish=true;return !f.actions.delete(false)&&f.first.finishes==1&&f.first.deletes==0;});
  attempt("composition finish selection drift rejects a new range",f->{f.first.finishHook=()->{f.first.start=f.first.end=5;f.actions.selection(5,5);};return !f.actions.delete(false)&&f.first.deletes==0;});
  attempt("unchanged selection notification while finishing composition remains usable",f->{f.first.finishHook=()->f.actions.selection(f.first.start,f.first.end);return f.actions.delete(false)&&f.first.finishes==1&&f.first.deletes==1;});
  attempt("composition finish owner switch never reads replacement editor",f->{f.first.finishHook=f::swap;return !f.actions.delete(false)&&f.first.deletes==0&&f.second.extracts==0;});
  attempt("normal navigation and reversed selection collapse",f->{boolean ok=f.actions.move(1,false)&&f.first.end==4;f.first.start=5;f.first.end=2;return ok&&f.actions.move(-1,false)&&f.first.start==2&&f.first.end==2;});
  attempt("whole emoji and combining grapheme movement",f->{f.first.text="A👩🏽‍💻e\u0301Z";f.first.start=f.first.end=1;return f.actions.move(1,false)&&f.first.end==8&&f.actions.move(1,false)&&f.first.end==10;});
  attempt("navigation leaves an interior grapheme caret safely",f->{f.first.text="A👩🏽‍💻B";f.first.start=f.first.end=2;return f.actions.move(1,false)&&f.first.end==8;});
  attempt("toggle selection extends anchor then collapses",f->{return f.actions.toggle()&&f.actions.move(1,false)&&f.first.start==3&&f.first.end==4&&f.actions.toggle()&&f.first.start==4&&f.first.end==4&&!f.actions.selecting;});
  attempt("edge navigation preserves entire UTF16 range",f->{return f.actions.move(-1,true)&&f.first.end==0&&f.actions.move(1,true)&&f.first.end==6;});
  attempt("normal backspace removes one grapheme",f->{f.first.text="A👩🏽‍💻Z";f.first.start=f.first.end=8;EditorActions.Deletion d=f.actions.deleteDetails(false);return d.accepted&&d.changed&&!d.newline&&f.first.text.equals("AZ")&&f.first.deletes==1;});
  attempt("selected newline deletion reports slowdown",f->{f.first.text="A\r\nZ";f.first.start=1;f.first.end=3;EditorActions.Deletion d=f.actions.deleteDetails(false);return d.accepted&&d.changed&&d.newline&&f.first.text.equals("AZ");});
  attempt("document boundary deletion is accepted no-op",f->{f.first.start=f.first.end=0;EditorActions.Deletion d=f.actions.deleteDetails(false);return d.accepted&&!d.changed&&f.first.deletes==0;});
  attempt("interior surrogate selection cannot delete",f->{f.first.text="A😀Z";f.first.start=2;f.first.end=3;return !f.actions.delete(false)&&f.first.commits==0;});
  attempt("malformed UTF16 cannot delete",f->{f.first.text="A\ud800Z";f.first.start=f.first.end=2;return !f.actions.delete(false)&&f.first.deletes==0;});
  attempt("vertical key cleanup stays on captured old connection",f->{f.first.keyHook=f::swap;boolean value=f.actions.vertical(1);return !value&&f.first.downs==1&&f.first.ups==1&&f.second.downs==0&&f.second.ups==0;});
  attempt("menu reentry does not report replacement success",f->{f.first.menuHook=f::swap;return !f.actions.menu(android.R.id.copy)&&f.first.menus==1&&f.second.menus==0;});

  attempt("Chromium full snapshot first backspace deletes e+combining exactly at DOM10",f->{f.first.chromeSnapshot=true;f.first.text="A👩🏽‍💻e\u0301Z";f.first.start=f.first.end=10;EditorActions.Deletion d=f.actions.deleteDetails(false);return d.accepted&&d.changed&&f.first.text.equals("A👩🏽‍💻Z")&&f.first.start==8&&f.first.end==8&&f.first.deletes==1&&f.second.deletes==0&&f.second.commits==0;});
  attempt("Chromium full snapshot whole ZWJ emoji backspace is synthetic coverage only",f->{f.first.chromeSnapshot=true;f.first.text="A👩🏽‍💻Z";f.first.start=f.first.end=8;return f.actions.delete(false)&&f.first.text.equals("AZ")&&f.first.start==1&&f.first.deletes==1;});
  attempt("Chromium full snapshot navigation remains grapheme bounded",f->{f.first.chromeSnapshot=true;f.first.text="A👩🏽‍💻e\u0301Z";f.first.start=f.first.end=10;return f.actions.move(-1,false)&&f.first.start==8;});
  attempt("Chromium full snapshot drift during read still blocks deletion",f->{f.first.chromeSnapshot=true;f.first.extractHook=f::swap;return !f.actions.delete(false)&&f.first.deletes==0&&f.first.finishes==0&&f.second.deletes==0;});
  attempt("Chromium full snapshot drift during finish still blocks deletion",f->{f.first.chromeSnapshot=true;f.first.finishHook=f::swap;return !f.actions.delete(false)&&f.first.deletes==0&&f.second.extracts==0&&f.second.deletes==0;});
  attempt("Chromium full snapshot selection drift after finish still blocks deletion",f->{f.first.chromeSnapshot=true;f.first.finishHook=()->{f.first.start=f.first.end=5;f.actions.selection(5,5);};return !f.actions.delete(false)&&f.first.deletes==0;});
  attempt("actual partial change remains rejected with a positive end",f->{f.first.chromeSnapshot=true;f.first.partial=true;return !f.actions.delete(false)&&f.first.deletes==0&&f.first.finishes==0;});
  attempt("nonzero snapshot origin still rejected",f->{f.first.chromeSnapshot=true;f.first.startOffset=1;return !f.actions.delete(false)&&f.first.deletes==0;});
  attempt("unknown negative partial sentinel rejected",f->{f.first.partialStart=-2;return !f.actions.delete(false)&&f.first.deletes==0;});
  System.out.println("TOTAL editor "+checks+" checks; failures="+failures+"; synthetic boundaries only");if(failures>0)System.exit(1);
 }
}
