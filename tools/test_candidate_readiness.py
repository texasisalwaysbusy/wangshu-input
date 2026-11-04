"""Exact display/space/selection methods; fake dictionary/editor/refresh boundaries.

No Android, JNI, real personal data or native UI acceptance.
"""
import argparse,hashlib,json,re,subprocess
from pathlib import Path
ap=argparse.ArgumentParser();ap.add_argument('--source',type=Path);ap.add_argument('--output',type=Path,required=True);args=ap.parse_args()
project=Path(__file__).resolve().parents[1];repo=project.parents[1];source=args.source or project/'android/app/src/main/java/org/wangshu/ime/WangshuIme.java';text=source.read_text();out=args.output.resolve();out.mkdir(parents=True,exist_ok=False)
def method(name):
    pattern=r'private\s+boolean\s+choose\(int\s+index\)' if name=='choose' else r'(?:private|public)\s+(?:static\s+)?\S+\s+'+re.escape(name)+r'\(';start=re.search(pattern,text).start();opening=text.index('{',start);end=opening+1;depth=1
    while depth:depth+=(text[end]=='{')-(text[end]=='}');end+=1
    return text[start:end]
names=['snapshotCandidateDisplay','displayedCandidate','choose','chooseBound','space','finishRaw','commitPending','candidatesReady']
methods='\n'.join(method(n) for n in names)
stage=re.search(r'(?:shownDictionaryReady=DataFiles\.ready;)?current=!password&&[^;]+;snapshotCandidateDisplay\(\);',method('refresh')).group(0)
wrapper=r'''package org.wangshu.ime;import java.util.*;
public final class CandidateReadinessChecks {
 interface InputConnection {boolean commitText(String value,int cursor);}
 static class DataFiles{static boolean ready;}
 static class FuzzySettingsActivity{static Set<String> enabledPairs(Object ignored){return Collections.emptySet();}}
 static class SplitSelection{static class Snapshot{}}
 static class LearningStore{static boolean mayLearn(Object ignored,boolean restricted){return false;}static LearningStore get(Object ignored)throws java.io.IOException{return new LearningStore();}void enqueueAutomatic(String key,String value,long time,Object completion)throws java.io.IOException{}}
 static class Undo{void invalidate(){}}final Undo beforeCaret=new Undo();
 final StringBuilder buffer=new StringBuilder("nihao");String shownInput="";int inputSession,shownSession,shownPage,page,shownModes;long shownGeneration,shownRevision,shownLearningRevision,candidateRevision,candidateLearningRevision;boolean password,shownFuzzy,currentPageFuzzy,learningWriteWarned,shownDictionaryReady,pendingCommitInFlight;
 Object candidatePages;Set<String> candidateRules=Collections.emptySet(),shownRules=Collections.emptySet();String[] current=new String[0];List<String> commits=new ArrayList<>();int refreshCount;Runnable duringRefresh=()->{};
 InputConnection connection=(text,cursor)->{commits.add(text);return true;};InputConnection getCurrentInputConnection(){return connection;}int candidateModes(){return 0;}long personalRevision(){return 0;}long learningRevision(){return 0;}boolean privateInput(){return false;}SplitSelection.Snapshot snapshot(){return null;}boolean verifiedCandidateInsertion(SplitSelection.Snapshot a,SplitSelection.Snapshot b,String raw,String word){return false;}
 boolean hasVoiceWork(){return false;}void cancelVoice(){}void toast(String value){}Object learningCompletion(int session,boolean manual){return null;}boolean commit(String text){return connection!=null&&connection.commitText(text,1);}
 String[] lookup(String input,int page){candidateRevision=0;if(input.equals("nihao"))return new String[]{"你好","native"};if(input.equals("hao"))return new String[]{"好","native"};if(input.equals("\\alpha"))return new String[]{"α","alias"};return new String[0];}
 void refresh(){refreshCount++;DISPLAY_ASSIGNMENT;duringRefresh.run();}
 METHOD_BODIES
 static int pass,fail;interface Case{void run();}static void require(boolean ok,String why){if(!ok)throw new AssertionError(why);}static void test(String name,Case value){DataFiles.ready=false;try{value.run();pass++;System.out.println("PASS "+name);}catch(Exception|AssertionError e){fail++;System.out.println("FAIL "+name+" :: "+e.getClass().getSimpleName()+" "+e.getMessage());}}
 static CandidateReadinessChecks loading(long oldRevision){CandidateReadinessChecks x=new CandidateReadinessChecks();x.candidateRevision=oldRevision;x.refresh();require(x.current.length==0,"fixture is not loading");return x;}
 public static void main(String[] args){
  test("loading dictionary leaves composition pending and commits nothing",()->{CandidateReadinessChecks x=loading(0);x.space();require(x.commits.isEmpty()&&x.buffer.toString().equals("nihao"),"loading committed text");});
  test("one Space after readiness chooses Chinese despite old cached revision",()->{CandidateReadinessChecks x=loading(0);DataFiles.ready=true;x.space();require(x.commits.equals(Arrays.asList("你好"))&&x.buffer.length()==0,"ready transition submitted raw or required extra Space: "+x.commits);});
  test("one Space after initial load chooses Chinese despite no earlier query",()->{CandidateReadinessChecks x=loading(-1);DataFiles.ready=true;x.space();require(x.commits.equals(Arrays.asList("你好")),"initial ready transition did not confirm first candidate: "+x.commits);});
  test("normal already-visible candidate is selected exactly once",()->{CandidateReadinessChecks x=new CandidateReadinessChecks();DataFiles.ready=true;x.refresh();x.space();require(x.commits.equals(Arrays.asList("你好")),"normal selection changed");});
  test("real empty ready lookup preserves explicit raw fallback",()->{CandidateReadinessChecks x=loading(0);x.buffer.setLength(0);x.buffer.append("qqqq");x.refresh();DataFiles.ready=true;x.space();require(x.commits.equals(Arrays.asList("qqqq")),"empty ready lookup did not confirm raw once");});
  test("same loading-to-ready rule supports mathematical aliases",()->{CandidateReadinessChecks x=loading(0);x.buffer.setLength(0);x.buffer.append("\\alpha");x.refresh();DataFiles.ready=true;x.space();require(x.commits.equals(Arrays.asList("α")),"alias loading transition submitted raw alias");});
  test("unrelated old displayed callback cannot adopt newly refreshed generation",()->{CandidateReadinessChecks x=new CandidateReadinessChecks();DataFiles.ready=true;x.refresh();Runnable old=x.displayedCandidate(0);x.refresh();old.run();require(x.commits.isEmpty()&&x.buffer.toString().equals("nihao"),"stale callback submitted new-generation candidate");});
  test("Space refresh never commits to a reentrant replacement input session",()->{CandidateReadinessChecks x=loading(0);DataFiles.ready=true;x.duringRefresh=()->{x.duringRefresh=()->{};x.inputSession++;x.buffer.setLength(0);x.buffer.append("hao");x.connection=(value,cursor)->{x.commits.add("NEW:"+value);return true;};x.refresh();};x.space();require(x.commits.isEmpty()&&x.buffer.toString().equals("hao"),"old Space committed to replacement editor: "+x.commits);});
  System.out.println("RESULT "+pass+" PASS / "+fail+" FAIL; exact production methods and candidate assignment, fake dictionary/editor only; native Android NOT_RUN");if(fail>0)System.exit(1);
 }
}
'''.replace('METHOD_BODIES',methods).replace('DISPLAY_ASSIGNMENT',stage)
(out/'CandidateReadinessChecks.java').write_text(wrapper);component=project/'android/app/src/main/java/org/wangshu/ime/PendingTextCommit.java'
c=subprocess.run([str(repo/'.tools/jdk21/bin/javac'),'-encoding','UTF-8','-d',str(out),str(component),str(out/'CandidateReadinessChecks.java')],capture_output=True,text=True);(out/'javac.txt').write_text(c.stdout+c.stderr)
r=subprocess.run([str(repo/'.tools/jdk21/bin/java'),'-cp',str(out),'org.wangshu.ime.CandidateReadinessChecks'],capture_output=True,text=True) if c.returncode==0 else None
if r:(out/'results.txt').write_text(r.stdout+r.stderr)
receipt={'source':str(source),'source_sha256':hashlib.sha256(source.read_bytes()).hexdigest(),'exact_methods':names,'display_assignment':stage,'case_sha256':hashlib.sha256(wrapper[wrapper.index('public static void main'):].encode()).hexdigest(),'javac_exit':c.returncode,'test_exit':None if r is None else r.returncode,'scope':'fake dictionary/editor plus exact production bodies; no real JNI, Binder, UI or timing evidence'};(out/'receipt.json').write_text(json.dumps(receipt,indent=2)+'\n');print(json.dumps(receipt));print(c.stdout+c.stderr if r is None else r.stdout+r.stderr)
raise SystemExit(r.returncode if r is not None else c.returncode)
