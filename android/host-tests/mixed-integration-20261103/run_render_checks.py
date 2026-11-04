from pathlib import Path
import subprocess,sys,hashlib,json
r=Path(__file__).resolve().parents[1];source=Path(sys.argv[1]).resolve() if len(sys.argv)>1 else r/'source';out=Path(sys.argv[2]).resolve();out.mkdir(exist_ok=False,parents=True)
s=(source/'android/app/src/main/java/org/wangshu/ime/WangshuIme.java').read_text()
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
fragments={n:take(s,sig) for n,sig in {'entry':'private void updateEntryComposition(', 'normal':'private void updateComposition()', 'selection':'@Override public void onUpdateSelection(', 'backspace':'private BackspaceGesture.DeleteResult backspaceStep('}.items()}
if 'private void reconcileLocalPreeditSelection()' in s:fragments['selection_reconcile']=take(s,'private void reconcileLocalPreeditSelection()')
code='''package org.wangshu.ime;
class Base {public void onUpdateSelection(int a,int b,int c,int d,int e,int f){}}
public class RenderChecks extends Base {
 static final class Connection {String document="";boolean accept=true;int writes;boolean setComposingText(String value,int p){writes++;if(accept)document=value;return accept;}boolean finishComposingText(){return true;}}
 SplitSelection.Snapshot preeditSnapshot;Connection preeditConnection;int preeditSession,preeditStart=-1,preeditEnd=-1;boolean localCommitInFlight,pendingCommitInFlight,candidateChoiceInFlight,inputEntryInFlight;final Connection owner=new Connection();StringBuilder buffer=new StringBuilder();int inputSession,page,refreshes;boolean compositionWriteWarned,editing;long emojiSelectionRevision;
 Connection getCurrentInputConnection(){return owner;}void toast(String s){}void refresh(){refreshes++;}void refreshAfterCandidate(boolean b){refresh();}boolean textWriteCurrent(TextWrite s,String p){return s.owner==owner&&buffer.toString().equals(p);}void rebuild(){}boolean emojiPanelActive(){return false;}void cancelBackspace(){}
 static final class TextWrite {Connection owner;int session;String raw;TextWrite(RenderChecks h){owner=h.owner;raw=h.buffer.toString();session=h.inputSession;}TextWrite(Connection c,String s){owner=c;raw=s;}}
 static final class Association {void request(){}} Association mathAssociation,phraseAssociation;
 static final class Undo {boolean hasUndo(){return false;}void invalidate(){}}Undo beforeCaret=new Undo();boolean hasVoiceWork(){return false;}void cancelVoice(){}
 static final class BackspaceGesture {static final class DeleteResult {DeleteResult(boolean a,boolean b){}}enum Phase {BULK_PREVIEW,IDLE}Phase phase(){return Phase.IDLE;}}BackspaceGesture backspaceGesture;
 static final class EditorActions {boolean selecting;static final class Deletion {boolean accepted=true,changed,newline;}Deletion deleteDetails(boolean b){return new Deletion();}void selection(int a,int b){}}EditorActions editorActions=new EditorActions();
 static final class Snapshot {int start,end;}static final class SplitSelection {static final class Snapshot {String text;int start,end;}}SplitSelection.Snapshot snapshotFrom(Connection c){return null;}static final class HeldVoice {Snapshot before;boolean contextChanged;}HeldVoice heldVoice;
 interface InputConnection {} // replaced below to use the fake typed connection
'''+ '\n'.join(fragments.values()).replace('InputConnection c=getCurrentInputConnection()','Connection c=getCurrentInputConnection()').replace('InputConnection connection=getCurrentInputConnection()','Connection connection=getCurrentInputConnection()')+'''
 static int failures,n;static void check(boolean value,String label){n++;if(!value)failures++;System.out.println((value?"PASS ":"FAIL ")+label);}
 public static void main(String[]args){
 RenderChecks f=new RenderChecks();f.buffer.append("nihao`OpenAI`zhongwen");f.updateEntryComposition(new TextWrite(f.owner,f.buffer.toString()));check(f.owner.document.isEmpty()&&f.owner.writes==0,"mixed preedit never enters editor text: "+f.owner.document);check(f.buffer.toString().equals("nihao`OpenAI`zhongwen"),"internal grammar remains exact");f.onUpdateSelection(19,19,0,0,0,19);check(f.buffer.length()==0,"caret-away production callback clears internal composition");check(f.owner.document.isEmpty()&&f.owner.writes==0,"caret-away cancellation cannot leak raw text or delimiter metadata: "+f.owner.document);
 f=new RenderChecks();f.buffer.append("nihao`OpenAI");f.updateComposition();check(f.owner.document.isEmpty()&&f.owner.writes==0,"general composition refresh never writes raw text: "+f.owner.document);
 f=new RenderChecks();f.buffer.append("`");f.owner.document="SELECTED";f.backspaceStep();check(f.buffer.length()==0&&f.owner.document.equals("SELECTED")&&f.owner.writes==0,"marker-only Backspace does not clear selected host text");
 f=new RenderChecks();f.buffer.append("`A");f.owner.document="A";f.backspaceStep();check(f.buffer.toString().equals("`")&&f.owner.document.equals("A")&&f.owner.writes==0,"deleting final local literal leaves existing host text unchanged");
 f=new RenderChecks();f.buffer.append("nihao");f.updateComposition();check(f.owner.document.isEmpty()&&f.buffer.toString().equals("nihao"),"ordinary pinyin remains local until confirmation");
 f=new RenderChecks();f.buffer.append("nihao`OpenAI");f.owner.accept=false;f.updateEntryComposition(new TextWrite(f.owner,f.buffer.toString()));check(f.owner.writes==0&&f.buffer.toString().equals("nihao`OpenAI"),"host composition refusal is irrelevant to local preedit");
 System.out.println("TOTAL "+n+" checks; failures="+failures+"; actual entry/update/selection production methods, synthetic Android only");if(failures>0)System.exit(1);
 }
}
'''
p=out/'RenderChecks.java';p.write_text(code)
for label,cmd in [('compile',['java','-m','jdk.compiler/com.sun.tools.javac.Main','-d',str(out/'classes'),str(p),str(source/'android/app/src/main/java/org/wangshu/ime/MixedComposition.java')]),('run',['java','-cp',str(out/'classes'),'org.wangshu.ime.RenderChecks'])]:
 x=subprocess.run(cmd,capture_output=True,text=True);(out/(label+'.txt')).write_text(x.stdout+x.stderr);print(x.stdout+x.stderr,end='')
 if label=='compile' and x.returncode:raise SystemExit(x.returncode)
(out/'receipt.json').write_text(json.dumps({'source_sha256':hashlib.sha256(s.encode()).hexdigest(),'fragments':{n:hashlib.sha256(v.encode()).hexdigest() for n,v in fragments.items()},'test_exit':x.returncode,'scope':'Production entry/update/selection methods extracted. Android framework/runtime NOT_RUN.'},indent=2))
raise SystemExit(x.returncode)
