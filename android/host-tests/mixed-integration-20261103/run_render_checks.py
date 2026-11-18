from pathlib import Path
import subprocess,sys,hashlib,json
r=Path(__file__).resolve().parents[1];source=Path(sys.argv[1]).resolve() if len(sys.argv)>1 else r/'source';out=Path(sys.argv[2]).resolve();out.mkdir(exist_ok=False,parents=True)
s=(source/'android/app/src/main/java/org/wangshu/ime/WangshuIme.java').read_text(encoding='utf-8')
def take(text,sig):
 start=text.index(sig);i=text.index('{',start);depth=0;state='code';j=i
 while j<len(text):
  c=text[j];pair=text[j:j+2]
  if state in ['str','char']:
   if c=='\\':j+=2;continue
   if c==('"' if state=='str' else "'"):state='code'
  elif state=='line':
   if c=='\n':state='code'
  elif state=='comment':
   if pair=='*/':state='code';j+=2;continue
  elif pair=='//':state='line';j+=2;continue
  elif pair=='/*':state='comment';j+=2;continue
  elif c=='"':state='str'
  elif c=="'":state='char'
  elif c=='{':depth+=1
  elif c=='}':
   depth-=1
   if depth==0:return text[start:j+1]
  j+=1
 raise Exception(sig)
fragments={}
signatures={'entry':'private void updateEntryComposition(', 'selection':'@Override public void onUpdateSelection(', 'backspace':'private BackspaceGesture.DeleteResult backspaceStep('}
if 'private void updateComposition()' in s:signatures['normal']='private void updateComposition()'
if 'private void discardPreedit()' in s:signatures['discard']='private void discardPreedit()'
if 'private boolean insertEditorText(' in s:signatures['commit']='private boolean insertEditorText('
signatures['reconcile']='private boolean reconcilePreeditSelection('
fragments={n:take(s,sig) for n,sig in signatures.items()}
code='''package org.wangshu.ime;
class Base {public void onUpdateSelection(int a,int b,int c,int d,int e,int f){}}
public class RenderChecks extends Base {
 static final class Connection {String document="ORIGINAL";boolean accept=true;int writes,finishes,commits;boolean setComposingText(String value,int p){writes++;if(accept)document=value;return accept;}boolean finishComposingText(){finishes++;return true;}boolean commitText(String value,int p){commits++;return accept;}}
 final Connection owner=new Connection();Connection preeditOwner; SplitSelection.Snapshot preeditSnapshot, hostSnapshot;
 boolean pendingCommitInFlight,candidateChoiceInFlight,inputEntryInFlight;
 final PreeditSelection preeditSelection=new PreeditSelection();StringBuilder buffer=new StringBuilder();int inputSession,page,refreshes;boolean compositionWriteWarned,editing;long emojiSelectionRevision,shownGeneration;
 Connection getCurrentInputConnection(){return owner;}void toast(String s){}void refresh(){refreshes++;}void refreshAfterCandidate(boolean b){refresh();}boolean textWriteCurrent(TextWrite s,String p){return s.owner==owner&&buffer.toString().equals(p);}void rebuild(){}boolean emojiPanelActive(){return false;}void cancelBackspace(){}
 static final class TextWrite {TextWrite(RenderChecks host){this(host.owner,host.buffer.toString());}Connection owner;String raw;TextWrite(Connection c,String s){owner=c;raw=s;}}
 SplitSelection.Snapshot snapshotFrom(Connection c){return hostSnapshot;}
 static boolean sameVoiceSnapshot(SplitSelection.Snapshot a,SplitSelection.Snapshot b){return a!=null&&b!=null&&a.start==b.start&&a.end==b.end&&a.text.equals(b.text);}
 static final class Association {void request(){}void selectionChanged(int a,int b,boolean composing,boolean moved){}} Association mathAssociation,phraseAssociation;
 static final class VoiceUndo {boolean atCaret(int a,int b){return false;}}VoiceUndo voiceUndo;void clearVoiceUndo(){}
 static final class Undo {boolean hasUndo(){return false;}void invalidate(){}}Undo beforeCaret=new Undo();boolean hasVoiceWork(){return false;}void cancelVoice(){}
 static final class BackspaceGesture {static final class DeleteResult {DeleteResult(boolean a,boolean b){}}enum Phase {BULK_PREVIEW,IDLE}Phase phase(){return Phase.IDLE;}}BackspaceGesture backspaceGesture;
 static final class EditorActions {boolean selecting;static final class Failure {String message="unavailable";}static final class Deletion {boolean accepted=true,changed,newline;Failure failure;}Deletion deleteDetails(boolean b){return new Deletion();}void selection(int a,int b){}}EditorActions editorActions=new EditorActions();
 static final class Snapshot {int start,end;}static final class HeldVoice {Snapshot before;boolean contextChanged;}HeldVoice heldVoice;
 interface InputConnection {} // replaced below to use the fake typed connection
'''+ '\n'.join(fragments.values()).replace('InputConnection c=getCurrentInputConnection()','Connection c=getCurrentInputConnection()').replace('InputConnection connection=getCurrentInputConnection()','Connection connection=getCurrentInputConnection()').replace('InputConnection owner,','Connection owner,')+'''
 static int failures,n;static void check(boolean value,String label){n++;if(!value)failures++;System.out.println((value?"PASS ":"FAIL ")+label);}
 public static void main(String[]args){
 RenderChecks f=new RenderChecks();f.buffer.append("nihao`OpenAI`zhongwen");f.updateEntryComposition(new TextWrite(f.owner,f.buffer.toString()));check(f.owner.document.equals("ORIGINAL")&&f.owner.writes==0,"mixed preedit is visible only in the keyboard");check(f.buffer.toString().equals("nihao`OpenAI`zhongwen"),"internal grammar remains exact");f.onUpdateSelection(19,19,0,0,0,19);check(f.buffer.length()==0,"caret-away production callback clears internal composition");check(f.owner.document.equals("ORIGINAL")&&f.owner.finishes==0,"caret-away leaves the document and composition untouched");
 f=new RenderChecks();f.buffer.append("`");f.owner.document="SELECTED";f.backspaceStep();check(f.buffer.length()==0&&f.owner.document.equals("SELECTED")&&f.owner.writes==0,"marker-only Backspace does not clear selected host text");
 f=new RenderChecks();f.buffer.append("`A");f.backspaceStep();check(f.buffer.toString().equals("`")&&f.owner.document.equals("ORIGINAL")&&f.owner.writes==0,"literal backspace edits only pending input");
 f=new RenderChecks();f.buffer.append("ni");f.updateEntryComposition(new TextWrite(f.owner,"ni"));check(f.owner.writes==0&&f.owner.document.equals("ORIGINAL")&&f.buffer.toString().equals("ni"),"unconfirmed ni never enters the document");
 f=new RenderChecks();f.buffer.append("nihao`OpenAI");f.owner.accept=false;f.updateEntryComposition(new TextWrite(f.owner,f.buffer.toString()));check(f.owner.writes==0&&f.buffer.toString().equals("nihao`OpenAI"),"pending input does not depend on host acceptance");
 f=new RenderChecks();f.buffer.append("ni");f.onUpdateSelection(1,1,1,1,-1,-1);check(f.buffer.toString().equals("ni"),"selection notification with no move preserves the pending key sequence");
 f=new RenderChecks();f.preeditSelection.reset(1,1);f.preeditSelection.expect(2);f.preeditSelection.expect(1);f.buffer.append("hao");f.onUpdateSelection(1,1,4,4,-1,-1);check(f.buffer.toString().equals("hao"),"coalesced caret feedback from earlier writes preserves newer preedit");f.onUpdateSelection(4,4,0,1,-1,-1);check(f.buffer.length()==0&&f.owner.writes==0&&f.owner.finishes==0,"new selection discards preedit without changing the document");
 f=new RenderChecks();f.hostSnapshot=new SplitSelection.Snapshot("ORIGINAL",2,2);f.buffer.append("ni");f.updateEntryComposition(new TextWrite(f.owner,"ni"));f.onUpdateSelection(0,0,2,2,-1,-1);check(f.buffer.toString().equals("ni"),"delayed selection callback with the same document snapshot preserves preedit");f.hostSnapshot=new SplitSelection.Snapshot("ORIGINAL",1,1);f.onUpdateSelection(2,2,1,1,-1,-1);check(f.buffer.length()==0&&f.owner.writes==0,"changed document snapshot discards local preedit");
 f=new RenderChecks();f.buffer.append("ni");f.pendingCommitInFlight=true;f.backspaceStep();check(f.buffer.toString().equals("ni")&&f.owner.writes==0,"reentrant backspace cannot change preedit during a pending commit");
 System.out.println("TOTAL "+n+" checks; failures="+failures+"; actual entry/update/selection production methods, synthetic Android only");if(failures>0)System.exit(1);
 }
}
'''
p=out/'RenderChecks.java';p.write_text(code,encoding='utf-8')
for label,cmd in [('compile',['java','-m','jdk.compiler/com.sun.tools.javac.Main','-d',str(out/'classes'),str(p),str(source/'android/app/src/main/java/org/wangshu/ime/MixedComposition.java'),str(source/'android/app/src/main/java/org/wangshu/ime/PreeditSelection.java'),str(source/'android/app/src/main/java/org/wangshu/ime/SplitSelection.java')]),('run',['java','-cp',str(out/'classes'),'org.wangshu.ime.RenderChecks'])]:
 x=subprocess.run(cmd,capture_output=True,text=True,encoding='utf-8');(out/(label+'.txt')).write_text(x.stdout+x.stderr,encoding='utf-8');print(x.stdout+x.stderr,end='')
 if label=='compile' and x.returncode:raise SystemExit(x.returncode)
(out/'receipt.json').write_text(json.dumps({'source_sha256':hashlib.sha256(s.encode()).hexdigest(),'fragments':{n:hashlib.sha256(v.encode()).hexdigest() for n,v in fragments.items()},'test_exit':x.returncode,'scope':'Production entry/update/selection methods extracted. Android framework/runtime NOT_RUN.'},indent=2))
raise SystemExit(x.returncode)
