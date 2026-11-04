"""Exact WangshuIme method-body checks with explicitly synthetic editor endpoints.

No Android classes, system input service, filesystem personal data, JNI or microphone.
Use a fresh output directory to retain each before/after run unchanged.
"""
import argparse,hashlib,json,re,subprocess
from pathlib import Path

ap=argparse.ArgumentParser();ap.add_argument('--source',type=Path);ap.add_argument('--output',type=Path,required=True);args=ap.parse_args()
project=Path(__file__).resolve().parents[1];repo=project.parents[1]
source=args.source or project/'android/app/src/main/java/org/wangshu/ime/WangshuIme.java';text=source.read_text()
out=args.output.resolve();out.mkdir(parents=True,exist_ok=False)
def method(name):
    hit=re.search(r'(?:private|public)\s+(?:static\s+)?\S+\s+'+re.escape(name)+r'\(',text)
    if not hit:raise ValueError('Missing production method '+name)
    opening=text.index('{',hit.start());depth=1;end=opening+1
    while depth:
        depth+=(text[end]=='{')-(text[end]=='}');end+=1
    return text[hit.start():end]
names=['onKeyDown','updateComposition','commitPending','commit']
methods='\n'.join(method(n) for n in names)
wrapper=r'''package org.wangshu.ime;
import java.util.*;
class FakeService {public boolean onKeyDown(int code,KeyEvent event){return false;}}
class KeyEvent {
 static final int KEYCODE_ESCAPE=111,KEYCODE_PAGE_UP=92,KEYCODE_PAGE_DOWN=93,KEYCODE_DEL=67,KEYCODE_SPACE=62,KEYCODE_ENTER=66;
 boolean isCtrlPressed(){return false;}boolean isAltPressed(){return false;}boolean isShiftPressed(){return false;}int getUnicodeChar(){return 0;}
}
public final class CompositionBoundaryChecks extends FakeService {
 interface InputConnection {boolean commitText(String text,int cursor);boolean setComposingText(CharSequence text,int cursor);}
 static final class Undo {void invalidate(){}boolean hasUndo(){return false;}}
 final Undo beforeCaret=new Undo();final StringBuilder buffer=new StringBuilder("ni");int page,inputSession,refreshes,toasts,commits,updates;
 boolean english,compositionWriteWarned,pendingCommitInFlight;InputConnection connection;boolean accept,throwCommit,throwCompose;boolean composeAccept=true;Runnable duringCommit=()->{},duringCompose=()->{};
 List<CharSequence> composingRequests=new ArrayList<>();List<String> committedValues=new ArrayList<>();
 CompositionBoundaryChecks(){connection=new InputConnection(){
  public boolean commitText(String text,int cursor){commits++;committedValues.add(text);duringCommit.run();if(throwCommit)throw new IllegalStateException("owned fake editor exception");return accept;}
  public boolean setComposingText(CharSequence text,int cursor){updates++;composingRequests.add(text);duringCompose.run();if(throwCompose)throw new IllegalStateException("owned fake editor composing exception");return composeAccept;}
 };}
 InputConnection getCurrentInputConnection(){return connection;}boolean hasVoiceWork(){return false;}void cancelVoice(){}void refresh(){refreshes++;}void toast(String text){toasts++;}
 void cancelBackspace(){}void cancelHoldForOtherInput(){}void rebuild(){}void backspace(){}void space(){}void enter(){}void punctuation(char c){}void key(String value){}boolean choose(int index){return false;}boolean finishRaw(){return false;}String[] lookup(String text,int page){return new String[0];}
 static class DataFiles{static boolean ready=true;}static class NativeBridge{static String[] lookup(String text,int page){return new String[0];}}
 METHOD_BODIES
 static int passed,failed;interface Case {void run();}static void test(String label,Case value){try{value.run();passed++;System.out.println("PASS "+label);}catch(Exception|AssertionError e){failed++;System.out.println("FAIL "+label+" :: "+e.getClass().getSimpleName()+" "+e.getMessage());}}
 static void require(boolean ok,String why){if(!ok)throw new AssertionError(why);}boolean escape(){return onKeyDown(KeyEvent.KEYCODE_ESCAPE,new KeyEvent());}
 public static void main(String[] args){
  test("Escape explicit rejection retains pending composition and page",()->{CompositionBoundaryChecks x=new CompositionBoundaryChecks();x.page=2;require(x.escape()&&x.buffer.toString().equals("ni")&&x.page==2&&x.commits==1,"rejected cancellation discarded pending text/page");});
  test("Escape uncertain editor exception retains text without crashing or retry",()->{CompositionBoundaryChecks x=new CompositionBoundaryChecks();x.throwCommit=true;require(x.escape()&&x.buffer.toString().equals("ni")&&x.commits==1,"uncertain cancellation lost pending or retried");});
  test("Escape successful cancellation commits empty once and clears only pending",()->{CompositionBoundaryChecks x=new CompositionBoundaryChecks();x.accept=true;require(x.escape()&&x.buffer.length()==0&&x.page==0&&x.commits==1&&x.committedValues.equals(Arrays.asList("")),"successful cancel semantics changed");});
  test("Escape reentrant input session preserves new composition",()->{CompositionBoundaryChecks x=new CompositionBoundaryChecks();x.accept=true;x.duringCommit=()->{x.inputSession++;x.buffer.setLength(0);x.buffer.append("hao");};require(x.escape()&&x.buffer.toString().equals("hao")&&x.commits==1,"new editor composition discarded");});
  test("Escape with no input connection neither clears nor calls editor",()->{CompositionBoundaryChecks x=new CompositionBoundaryChecks();x.connection=null;x.escape();require(x.buffer.toString().equals("ni")&&x.commits==0,"missing editor discarded composition");});
  test("composing request is an immutable snapshot across later keystrokes",()->{CompositionBoundaryChecks x=new CompositionBoundaryChecks();x.updateComposition();x.buffer.append("hao");require(x.composingRequests.size()==1&&x.composingRequests.get(0).toString().equals("ni"),"old request mutated after it was sent");});
  test("reentrant composing callback cannot change text of in-flight request",()->{CompositionBoundaryChecks x=new CompositionBoundaryChecks();x.duringCompose=()->{x.buffer.setLength(0);x.buffer.append("new");};x.updateComposition();require(x.buffer.toString().equals("new")&&x.composingRequests.get(0).toString().equals("ni"),"callback changed old request payload");});
  test("composing exception is surfaced while pending remains and UI refreshes",()->{CompositionBoundaryChecks x=new CompositionBoundaryChecks();x.throwCompose=true;x.updateComposition();require(x.buffer.toString().equals("ni")&&x.refreshes==1&&x.toasts==1&&x.updates==1,"pending/error UI lost or automatic retry");});
  test("composing explicit refusal retains pending and informs user once",()->{CompositionBoundaryChecks x=new CompositionBoundaryChecks();x.composeAccept=false;x.updateComposition();require(x.buffer.toString().equals("ni")&&x.refreshes==1&&x.toasts==1&&x.updates==1,"refusal silently ignored or retried");});
  test("normal composing acknowledgement stays silent and updates once",()->{CompositionBoundaryChecks x=new CompositionBoundaryChecks();x.updateComposition();require(x.buffer.toString().equals("ni")&&x.refreshes==1&&x.toasts==0&&x.updates==1,"normal composing path regressed");});
  System.out.println("RESULT "+passed+" PASS / "+failed+" FAIL; exact production method bodies, fake editor only; Android/Binder/UI NOT_RUN");if(failed>0)System.exit(1);
 }
}
'''.replace('METHOD_BODIES',methods)
java=out/'CompositionBoundaryChecks.java';java.write_text(wrapper)
component=project/'android/app/src/main/java/org/wangshu/ime/PendingTextCommit.java'
compile=subprocess.run([str(repo/'.tools/jdk21/bin/javac'),'-encoding','UTF-8','-d',str(out),str(component),str(java)],capture_output=True,text=True)
(out/'javac.txt').write_text(compile.stdout+compile.stderr)
run=subprocess.run([str(repo/'.tools/jdk21/bin/java'),'-cp',str(out),'org.wangshu.ime.CompositionBoundaryChecks'],capture_output=True,text=True) if compile.returncode==0 else None
if run:(out/'results.txt').write_text(run.stdout+run.stderr)
receipt={'source':str(source),'source_sha256':hashlib.sha256(source.read_bytes()).hexdigest(),'component_sha256':hashlib.sha256(component.read_bytes()).hexdigest(),'exact_methods':names,'case_sha256':hashlib.sha256(wrapper[wrapper.index('public static void main'):].encode()).hexdigest(),'javac_exit':compile.returncode,'test_exit':None if run is None else run.returncode,'scope':'exact method bodies plus explicit fake editor endpoints; not Android UI, Binder or universal editor behavior'}
(out/'receipt.json').write_text(json.dumps(receipt,indent=2)+'\n');print(json.dumps(receipt));print(run.stdout+run.stderr if run else compile.stderr)
raise SystemExit(run.returncode if run is not None else compile.returncode)
