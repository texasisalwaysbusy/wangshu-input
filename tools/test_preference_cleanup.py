"""Real checkpoint file IO + exact suite finally blocks with fake preferences/UI endpoints."""
import argparse,hashlib,json,re,subprocess
from pathlib import Path
ap=argparse.ArgumentParser();ap.add_argument('--mode',choices=['red','green'],required=True);ap.add_argument('--output',type=Path,required=True);a=ap.parse_args()
project=Path(__file__).resolve().parents[1];repo=project.parents[1];app=project/'android/app/src/androidTest/java/org/wangshu/ime';out=a.output.resolve();out.mkdir(parents=True,exist_ok=False);src=out/'src/org/wangshu/ime';src.mkdir(parents=True)
fake=out/'src/android/content/SharedPreferences.java';fake.parent.mkdir(parents=True)
fake.write_text('package android.content;import java.util.Map;public interface SharedPreferences {Map<String,?> getAll();Editor edit();interface Editor {Editor putBoolean(String key,boolean value);Editor remove(String key);boolean commit();}}')
hashes={}
for name in ['TestBooleanPreferenceCheckpoint.java','TestCleanupSteps.java']:
 p=app/name;hashes[name]=hashlib.sha256(p.read_bytes()).hexdigest();(src/name).write_bytes(p.read_bytes())
def finally_block(s):
 start=s.index('\n        finally',s.index('static String run('));opening=s.index('{',start);depth=1;end=opening+1
 while depth:depth+=(s[end]=='{')-(s[end]=='}');end+=1
 return s[start:end]
base=project/'evidence/2026-10-28-preference-cleanup/old-source' if a.mode=='red' else app
for name in ['LearningUiChecks','MathAssociationUiChecks']:
 p=base/(name+'.java');s=p.read_text();hashes[name+'.java']=hashlib.sha256(p.read_bytes()).hexdigest()
 learning=name.startswith('Learning');declarations='''SharedPreferences preferences=state.prefs;Map<String,?> previous=state.before;Object enabledBefore=previous.get("enabled");boolean pausedBefore=state.oldPaused;final LearningStore original=state.originalStore;Object field=new Object();boolean swapped=true,swapAttempted=true;''' if learning else '''SharedPreferences prefs=state.prefs;Object original=state.before.get("math");'''
 code='''package org.wangshu.ime;import java.util.*;import android.content.SharedPreferences;
final class NAMEProbe {
 static Instrumentation test=new Instrumentation();static void swap(Object field,LearningStore original){State.current.currentStore=original;}static void finishOwned(Activity activity){if(activity!=null)activity.finish();}
 static void run(State state,Throwable primary)throws Exception{Activity activity=new Activity();TestBooleanPreferenceCheckpoint checkpoint=state.checkpoint;DECLARATIONS
  try{if(primary!=null)Failures.raise(primary);}FINALLY
 }
}
'''.replace('NAME',name).replace('DECLARATIONS',declarations).replace('FINALLY',finally_block(s))
 (src/(name+'Probe.java')).write_text(code)
test=r'''package org.wangshu.ime;
import java.util.*;import java.io.*;import java.nio.file.*;import android.content.SharedPreferences;
final class Failures {static void raise(Throwable error)throws Exception{if(error instanceof Error)throw (Error)error;throw (Exception)error;}}
final class Prefs implements SharedPreferences {
 final Map<String,Object> values=new HashMap<>(),disk=new HashMap<>();boolean ack=true;int commits;
 public Map<String,?> getAll(){return new HashMap<>(values);}public Editor edit(){return new Editor(){String key;Boolean value;boolean remove;public Editor putBoolean(String k,boolean v){key=k;value=v;return this;}public Editor remove(String k){key=k;remove=true;return this;}public boolean commit(){commits++;if(remove)values.remove(key);else values.put(key,value);if(ack){disk.clear();disk.putAll(values);}return ack;}};}
 void set(String key,Object value){values.put(key,value);disk.put(key,value);}
}
final class State {
 static State current;Prefs prefs=new Prefs();Map<String,?> before;TestBooleanPreferenceCheckpoint checkpoint;File directory;boolean oldPaused,paused=true,failFinish,failPause;int finishCalls,pauseCalls;LearningStore originalStore=new LearningStore(),currentStore=new LearningStore();
}
final class LearningStore {static final String ENABLED="enabled";static void pause(boolean value){State s=State.current;s.pauseCalls++;if(s.failPause&&s.pauseCalls==1)throw new AssertionError("owned pause endpoint failure");s.paused=value;}}
final class Activity {void finish(){State s=State.current;s.finishCalls++;if(s.failFinish)throw new AssertionError("owned UI finish AssertionError");}}
final class Instrumentation {void runOnMainSync(Runnable action){action.run();}}
public final class PreferenceCleanupChecks {
 static int pass,fail,serial;static File root;interface Case{void run()throws Exception;}static void require(boolean yes,String why){if(!yes)throw new AssertionError(why);}static void test(String label,Case action){try{action.run();pass++;System.out.println("PASS "+label);}catch(Exception|AssertionError error){fail++;System.out.println("FAIL "+label+" :: "+error.getClass().getSimpleName()+" "+error.getMessage());}}
 static File folder(String tag)throws IOException{File file=new File(root,(++serial)+"-"+tag);if(!file.mkdirs())throw new IOException("owned fixture folder unavailable");return file;}
 static State prepare(String suite,Object original)throws Exception{State s=new State();State.current=s;s.directory=folder(suite);String key=suite.equals("learning")?"enabled":"math";if(original!=null)s.prefs.set(key,original);s.prefs.set("untouched","owned-nonexport-fixture");s.before=s.prefs.getAll();s.checkpoint=new TestBooleanPreferenceCheckpoint(s.prefs,new File(s.directory,"preferences-before.tsv"),suite,key,suite.equals("learning")?s.oldPaused:null);s.prefs.edit().putBoolean(key,true).commit();return s;}
 static Throwable run(String suite,State state,Throwable primary){try{if(suite.equals("learning"))LearningUiChecksProbe.run(state,primary);else MathAssociationUiChecksProbe.run(state,primary);return null;}catch(Throwable failure){return failure;}}
 public static void main(String[] args)throws Exception{root=new File(args[0]);if(!root.mkdirs())throw new IOException("host evidence directory not fresh");
  test("checkpoint distinguishes missing scalar from false and records paused before mutation",()->{State s=prepare("learning",null);String text=Files.readString(new File(s.directory,"preferences-before.tsv").toPath());require(text.contains("present\t0\nvalue\tabsent")&&text.contains("paused\tfalse")&&!text.contains("owned-nonexport-fixture"),"checkpoint not exact or leaked unrelated value");s.checkpoint.restore();require(!s.prefs.values.containsKey("enabled"),"missing key became false");s.checkpoint.recordRestored();});
  test("checkpoint restores both explicit Boolean values exactly",()->{for(boolean original:new boolean[]{false,true}){State s=prepare("math",original);s.prefs.edit().putBoolean("math",!original).commit();s.checkpoint.restore();require(s.prefs.values.containsKey("math")&&s.prefs.values.get("math").equals(original)&&s.prefs.disk.equals(s.prefs.values),"Boolean value/presence not restored");}});
  test("malformed existing type rejects before checkpoint or editor mutation",()->{Prefs p=new Prefs();p.set("enabled","false");File journal=new File(folder("malformed"),"before.tsv");boolean rejected=false;try{new TestBooleanPreferenceCheckpoint(p,journal,"learning","enabled",false);}catch(IOException expected){rejected=true;}require(rejected&&p.commits==0&&!journal.exists()&&p.values.get("enabled").equals("false"),"malformed original overwritten");});
  test("existing journal is never replaced and no fixture mutation occurs",()->{Prefs p=new Prefs();File journal=new File(folder("existing"),"before.tsv");Files.writeString(journal.toPath(),"owned old evidence");boolean rejected=false;try{new TestBooleanPreferenceCheckpoint(p,journal,"math","math",null);}catch(IOException expected){rejected=true;}require(rejected&&p.commits==0&&Files.readString(journal.toPath()).equals("owned old evidence"),"existing evidence replaced");});
  test("checkpoint IO failure prevents fixture edit",()->{Prefs p=new Prefs();boolean rejected=false;try{new TestBooleanPreferenceCheckpoint(p,new File(folder("io"),"absent-parent/before.tsv"),"math","math",null);}catch(IOException expected){rejected=true;}require(rejected&&p.commits==0,"IO failure permitted preference mutation");});
  test("restore false commit never acknowledges disk or emits restored receipt",()->{State s=prepare("math",false);s.prefs.ack=false;boolean rejected=false;try{s.checkpoint.restore();}catch(IOException expected){rejected=true;}boolean receiptRejected=false;try{s.checkpoint.recordRestored();}catch(IOException expected){receiptRejected=true;}require(rejected&&receiptRejected&&!new File(s.directory,"preferences-restored.tsv").exists()&&s.prefs.disk.get("math").equals(true),"false commit was reported durable");});
  test("unrelated field change is detected and is never reset",()->{State s=prepare("math",false);s.prefs.set("untouched","new independent value");s.checkpoint.restore();boolean rejected=false;try{s.checkpoint.verifyUnrelated();}catch(AssertionError expected){rejected=true;}require(rejected&&s.prefs.values.get("untouched").equals("new independent value"),"unrelated data overwritten");});
  for(String suite:new String[]{"learning","math"}){
   test(suite+" exact finally restores before UI AssertionError without masking primary",()->{State s=prepare(suite,false);s.failFinish=true;AssertionError primary=new AssertionError("ORIGINAL primary");Throwable result=run(suite,s,primary);require(result==primary&&s.checkpoint.matches()&&s.prefs.disk.equals(s.prefs.values)&&s.finishCalls==1,"primary/restoration/owned close not preserved");require(primary.getSuppressed().length>=1,"secondary UI failure not retained");if(suite.equals("learning"))require(s.currentStore==s.originalStore&&!s.paused,"original store/pause not restored");});
   test(suite+" exact finally false restore ACK cannot mask original test error",()->{State s=prepare(suite,false);s.prefs.ack=false;AssertionError primary=new AssertionError("ORIGINAL primary");Throwable result=run(suite,s,primary);require(result==primary&&primary.getSuppressed().length>=1,"restore failure replaced primary");if(suite.equals("learning"))require(s.currentStore==s.originalStore,"failed restore prevented singleton restoration");});
   test(suite+" success path restoration failure becomes a failure",()->{State s=prepare(suite,false);s.prefs.ack=false;require(run(suite,s,null)!=null,"cleanup failure falsely returned success");});
   test(suite+" clean successful finally restores absent preference and writes receipt",()->{State s=prepare(suite,null);require(run(suite,s,null)==null&&s.checkpoint.matches()&&!s.prefs.values.containsKey(suite.equals("learning")?"enabled":"math")&&new File(s.directory,"preferences-restored.tsv").isFile()&&s.finishCalls==1,"clean scalar/receipt/Activity cleanup incomplete");});
  }
  test("learning pause AssertionError does not skip scalar or singleton restoration",()->{State s=prepare("learning",false);s.failPause=true;AssertionError primary=new AssertionError("ORIGINAL primary");Throwable result=run("learning",s,primary);require(result==primary&&s.checkpoint.matches()&&s.currentStore==s.originalStore&&!s.paused,"pause failure skipped later cleanup");});
  System.out.println("RESULT "+pass+" PASS / "+fail+" FAIL; actual checkpoint IO and exact suite finally blocks, fake SharedPreferences/UI, NOT Android runtime");if(fail>0)System.exit(1);
 }
}
'''
(src/'PreferenceCleanupChecks.java').write_text(test)
c=subprocess.run([str(repo/'.tools/jdk21/bin/javac'),'-encoding','UTF-8','-d',str(out/'classes')]+[str(p) for p in (out/'src').rglob('*.java')],text=True,capture_output=True);(out/'javac.txt').write_text(c.stdout+c.stderr)
r=subprocess.run([str(repo/'.tools/jdk21/bin/java'),'-cp',str(out/'classes'),'org.wangshu.ime.PreferenceCleanupChecks',str(out/'owned-files')],text=True,capture_output=True) if c.returncode==0 else None
if r:(out/'results.txt').write_text(r.stdout+r.stderr)
receipt={'mode':a.mode,'source_sha256':hashes,'case_sha256':hashlib.sha256(test.encode()).hexdigest(),'javac_exit':c.returncode,'test_exit':None if r is None else r.returncode,'wiring':{n:(base/(n+'.java')).read_text().find('new TestBooleanPreferenceCheckpoint(')>=0 for n in ['LearningUiChecks','MathAssociationUiChecks']},'scope':'host FileOutputStream/fd.sync/readback + exact finally blocks; fake preferences/pause/singleton/UI, no Android runtime'};(out/'receipt.json').write_text(json.dumps(receipt,indent=2)+'\n');print(json.dumps(receipt));print(c.stdout+c.stderr if r is None else r.stdout+r.stderr);raise SystemExit(r.returncode if r is not None else c.returncode)
