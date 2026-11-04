"""Fault-inject the exact native-suite failure handlers on the host, without Android."""
import argparse,hashlib,json,re,subprocess
from pathlib import Path
ap=argparse.ArgumentParser();ap.add_argument('--source-dir',type=Path);ap.add_argument('--output',type=Path,required=True);args=ap.parse_args()
project=Path(__file__).resolve().parents[1];repo=project.parents[1];source=args.source_dir or project/'android/app/src/androidTest/java/org/wangshu/ime';out=args.output.resolve();out.mkdir(parents=True,exist_ok=False)
def handler(text):
 body=text.index('static String run(');start=re.search(r'catch\((?:Exception\|AssertionError|Throwable) failure\)',text[body:]).start()+body;opening=text.index('{',start);depth=1;end=opening+1
 while depth:depth+=(text[end]=='{')-(text[end]=='}');end+=1
 return text[start:end]
prefix=r'''package org.wangshu.ime;
interface Evidence {String get()throws Exception;}
final class Failures {static Evidence metadata=()->"metadata",screenshot=()->"image";static void raise(Throwable t)throws Exception{if(t instanceof Error)throw (Error)t;throw (Exception)t;}}
final class LayoutChecks {static Object auto=new Object();static void screenshot(Object c,String name)throws Exception{Failures.screenshot.get();}}
final class ImeSelectorDiagnostics {static String snapshot(Object auto)throws Exception{return Failures.metadata.get();}}
final class TestBooleanPreferenceCheckpoint {void restore(){}}
final class LearningStore {static void pause(boolean paused){}}
'''
hashes={};names=['ToolbarUiChecks','MathAssociationUiChecks','LearningUiChecks']
for name in names:
 p=source/(name+'.java');data=p.read_text();hashes[name]=hashlib.sha256(p.read_bytes()).hexdigest();catch=handler(data)
 prefix+='final class '+name+' {static int count=2;static StringBuilder log;static Object context=new Object();static java.io.File evidence=new java.io.File("test-owned-uuid");static void run(Throwable original)throws Exception{log=new StringBuilder("PASS preconditions\\n");Throwable primary=null;boolean pausedBefore=false;TestBooleanPreferenceCheckpoint checkpoint=new TestBooleanPreferenceCheckpoint();try{Failures.raise(original);}'+catch+'}}\n'
tests=r'''public final class FailureEvidenceChecks {
 static int pass,fail;interface Run {void call(Throwable original)throws Exception;}
 static void require(boolean yes,String why){if(!yes)throw new AssertionError(why);}static void test(String label,Run run,int mode){
  AssertionError original=new AssertionError("ORIGINAL owned-fixture failure");AssertionError image=new AssertionError("no screenshot"),meta=new AssertionError("no window metadata");
  Failures.metadata=()->"metadata retained\n";Failures.screenshot=()->"image";
  if(mode==0)Failures.screenshot=()->{throw image;};
  if(mode==1)Failures.metadata=()->{throw meta;};
  if(mode==2){Failures.metadata=()->{throw new IllegalStateException("metadata endpoint");};Failures.screenshot=()->{throw new java.io.IOException("screenshot endpoint");};}
  if(mode==3){Failures.metadata=()->{throw meta;};Failures.screenshot=()->{throw image;};}
  if(mode==5){Failures.metadata=()->{throw original;};Failures.screenshot=()->{throw original;};}
  Throwable observed=null;try{run.call(original);}catch(Exception|AssertionError result){observed=result;}
  try{require(observed instanceof AssertionError&&observed.getCause()==original,"secondary exception replaced original cause");require(observed.getMessage().contains("partial=2")&&observed.getMessage().contains("PASS preconditions"),"partial progress lost");
   int expected=mode==0||mode==1?1:mode==2||mode==3?2:0;require(original.getSuppressed().length==expected,"secondary error stacks not retained: "+original.getSuppressed().length);
   if(mode==0)require(observed.getMessage().contains("metadata retained")&&observed.getMessage().contains("AssertionError"),"successful metadata or failed screenshot note lost");
   pass++;System.out.println("PASS "+label);
  }catch(AssertionError failure){fail++;System.out.println("FAIL "+label+" :: "+failure.getMessage()+" observed="+(observed==null?"null":observed.getClass().getSimpleName()));}
 }
 public static void main(String[] args){
  String[] labels={"screenshot AssertionError preserves original and metadata","metadata AssertionError preserves original","two evidence Exceptions preserve original plus suppressed","two evidence AssertionErrors preserve original plus suppressed","successful evidence preserves original","self-same error never triggers invalid self-suppression"};
  for(int i=0;i<labels.length;i++){test("toolbar "+labels[i],ToolbarUiChecks::run,i);test("math "+labels[i],MathAssociationUiChecks::run,i);test("learning "+labels[i],LearningUiChecks::run,i);}
  System.out.println("RESULT "+pass+" PASS / "+fail+" FAIL; exact failure handlers, synthetic evidence endpoints only; Android screenshots NOT_RUN");if(fail>0)System.exit(1);
 }
}
'''
java=out/'FailureEvidenceChecks.java';java.write_text(prefix+tests)
c=subprocess.run([str(repo/'.tools/jdk21/bin/javac'),'-encoding','UTF-8','-d',str(out),str(java),str(project/'android/app/src/androidTest/java/org/wangshu/ime/TestCleanupSteps.java')],text=True,capture_output=True);(out/'javac.txt').write_text(c.stdout+c.stderr)
r=subprocess.run([str(repo/'.tools/jdk21/bin/java'),'-cp',str(out),'org.wangshu.ime.FailureEvidenceChecks'],text=True,capture_output=True) if c.returncode==0 else None
if r:(out/'results.txt').write_text(r.stdout+r.stderr)
receipt={'handler_source_sha256':hashes,'case_sha256':hashlib.sha256(tests.encode()).hexdigest(),'javac_exit':c.returncode,'test_exit':None if r is None else r.returncode,'scope':'exact native-suite catch blocks, fake metadata/screenshot functions, no Android execution'};(out/'receipt.json').write_text(json.dumps(receipt,indent=2)+'\n');print(json.dumps(receipt));print(c.stdout+c.stderr if r is None else r.stdout+r.stderr);raise SystemExit(r.returncode if r is not None else c.returncode)
