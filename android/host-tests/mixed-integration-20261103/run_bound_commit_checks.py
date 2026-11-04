from pathlib import Path
import subprocess,sys,json,hashlib
r=Path(__file__).resolve().parents[1];source=Path(sys.argv[1]).resolve() if len(sys.argv)>1 else r/'source';out=Path(sys.argv[2]).resolve();out.mkdir(exist_ok=False,parents=True)
helper=(Path(__file__).resolve().parent/'run_render_checks.py').read_text();ns={};exec(helper[helper.index('def take('):helper.index('fragments=')],ns);take=ns['take'];java=source/'android/app/src/main/java/org/wangshu/ime';s=(java/'WangshuIme.java').read_text();sigs=['private static final class TextWrite','private boolean textWriteFields(','private boolean textWriteCurrent(','private boolean prepareTextWrite(','private PendingTextCommit.Result writeBound(','private boolean commitText(','private boolean commitPending(','private boolean finishRaw(','private boolean commitCandidate('];parts={sig:take(s,sig) for sig in sigs}
code='''package org.wangshu.ime;
public class BoundCommitChecks {
 interface InputConnection {boolean commitText(String text,int c);}
 Object preeditSnapshot;InputConnection preeditConnection;int preeditSession;boolean localCommitInFlight;String document="SELECTED";int writes,inputSession,page;long shownGeneration;boolean reject,pendingCommitInFlight,candidateChoiceInFlight,inputEntryInFlight;StringBuilder buffer=new StringBuilder();InputConnection owner=(text,c)->{writes++;if(reject)return false;document=text;return true;};Runnable onOwner=()->{};
 InputConnection getCurrentInputConnection(){onOwner.run();return owner;}int candidateModes(){return 0;}boolean hasVoiceWork(){return false;}void cancelVoice(){}void refresh(){}void refreshAfterCandidate(boolean b){}void toast(String s){}void textWriteWarning(PendingTextCommit.Result r){}
 static final class Undo {boolean hasUndo(){return false;}void invalidate(){}}Undo beforeCaret=new Undo();
 static final class CandidateClick {InputConnection owner;String raw;int session;CandidateClick(BoundCommitChecks h){owner=h.owner;raw=h.buffer.toString();session=h.inputSession;}}
 boolean candidateClickCurrent(CandidateClick c){return c.owner==owner&&c.session==inputSession&&c.raw.equals(buffer.toString());}
'''+ '\n'.join(parts.values()).replace('TextWrite(WangshuIme host)','TextWrite(BoundCommitChecks host)')+'''
 static int checks,failures;static void check(boolean b,String label){checks++;if(!b)failures++;System.out.println((b?"PASS ":"FAIL ")+label);}
 public static void main(String[]args){
 BoundCommitChecks h=new BoundCommitChecks();h.buffer.append("`");check(h.finishRaw()&&h.buffer.length()==0&&h.writes==0&&h.document.equals("SELECTED"),"marker-only raw finalize clears local state without deleting selection");
 h=new BoundCommitChecks();h.buffer.append("``");check(h.commitPending("")&&h.buffer.length()==0&&h.writes==0&&h.document.equals("SELECTED"),"marker-only Escape cancellation preserves selection");
 h=new BoundCommitChecks();h.buffer.append("`");check(h.commitCandidate(new CandidateClick(h),"")&&h.buffer.length()==0&&h.writes==0&&h.document.equals("SELECTED"),"marker-only no-candidate confirmation preserves selection");
 h=new BoundCommitChecks();h.buffer.append("nihao`OpenAI`");check(h.finishRaw()&&h.document.equals("nihaoOpenAI")&&h.buffer.length()==0&&h.writes==1,"raw finalize commits rendered text once and clears raw provenance");
 h=new BoundCommitChecks();h.buffer.append("nihao`OpenAI`");check(h.commitCandidate(new CandidateClick(h),"你好OpenAI")&&h.document.equals("你好OpenAI")&&h.buffer.length()==0&&h.writes==1,"selected mixed candidate commits once through actual bound adapter");
 h=new BoundCommitChecks();h.buffer.append("nihao`OpenAI`");h.reject=true;check(!h.finishRaw()&&h.buffer.toString().equals("nihao`OpenAI`")&&h.writes==1,"rejected raw finalize preserves grammar without retry");
 h=new BoundCommitChecks();h.buffer.append("nihao`OpenAI`");h.reject=true;check(!h.commitCandidate(new CandidateClick(h),"你好OpenAI")&&h.buffer.toString().equals("nihao`OpenAI`")&&h.writes==1,"rejected candidate preserves pending grammar without retry");
 h=new BoundCommitChecks();h.buffer.append("`");final BoundCommitChecks target=h;h.onOwner=()->{target.inputSession++;};check(!h.finishRaw()&&h.buffer.toString().equals("`")&&h.writes==0,"marker-only stale session cannot clear a newer pending binding");
 h=new BoundCommitChecks();h.buffer.append("nihao`OpenAI`");check(h.commitPending("")&&h.document.equals("SELECTED")&&h.writes==0&&h.buffer.length()==0,"Escape cancels local preedit without touching selected host text");
 System.out.println("TOTAL "+checks+" checks; failures="+failures+"; actual TextWrite/PendingTextCommit/commitCandidate bodies, synthetic editor only");if(failures>0)System.exit(1);
 }
}
'''
p=out/'BoundCommitChecks.java';p.write_text(code)
for label,cmd in [('compile',['java','-m','jdk.compiler/com.sun.tools.javac.Main','-d',str(out/'classes'),str(p),str(java/'MixedComposition.java'),str(java/'PendingTextCommit.java')]),('run',['java','-cp',str(out/'classes'),'org.wangshu.ime.BoundCommitChecks'])]:
 x=subprocess.run(cmd,capture_output=True,text=True);(out/(label+'.txt')).write_text(x.stdout+x.stderr);print(x.stdout+x.stderr,end='')
 if label=='compile' and x.returncode:raise SystemExit(x.returncode)
(out/'receipt.json').write_text(json.dumps({'source_sha256':hashlib.sha256(s.encode()).hexdigest(),'fragments':{n:hashlib.sha256(v.encode()).hexdigest() for n,v in parts.items()},'test_exit':x.returncode,'scope':'Production TextWrite/bound commit bodies; actual PendingTextCommit class. Android framework NOT_RUN.'},indent=2))
raise SystemExit(x.returncode)
