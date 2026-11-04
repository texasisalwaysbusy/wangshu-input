"""Extract Math/Learning node/await/click methods; explicit strict-window-query mock, no Android."""
import argparse,hashlib,json,re,subprocess
from pathlib import Path
ap=argparse.ArgumentParser();ap.add_argument('--mode',choices=['red','green'],required=True);ap.add_argument('--output',type=Path,required=True);a=ap.parse_args();project=Path(__file__).resolve().parents[1];repo=project.parents[1];out=a.output.resolve();out.mkdir(parents=True,exist_ok=False)
base=project/'evidence/2026-10-28-math-learning-query/old-source' if a.mode=='red' else project/'android/app/src/androidTest/java/org/wangshu/ime'
def method(s,name):
 hit=re.search(r'private static \S+ '+name+r'\(',s);start=hit.start();opening=s.index('{',start);depth=1;end=opening+1
 while depth:depth+=(s[end]=='{')-(s[end]=='}');end+=1
 return s[start:end]
prefix=r'''package org.wangshu.ime;import java.util.*;
final class Build{static class VERSION{static int SDK_INT=36;}}final class SystemClock{static long now;static long elapsedRealtime(){return now;}static void sleep(long millis){now+=millis;}}
final class AccessibilityNodeInfo {
 static final int ACTION_CLICK=16;int window=34,refreshes,calls;String pkg="org.wangshu.ime",desc,text="中";boolean visible=true,enabled=true,clickable=true,ack=true;int mutation;RuntimeException actionError;
 boolean refresh(){refreshes++;if(refreshes==2){if(mutation==1)return false;if(mutation==2)window=99;if(mutation==3)desc="changed";if(mutation==4)pkg="other.app";}return true;}
 int getWindowId(){return window;}CharSequence getPackageName(){return pkg;}CharSequence getContentDescription(){return desc;}CharSequence getText(){return text;}boolean isVisibleToUser(){return visible;}boolean isEnabled(){return enabled;}boolean isClickable(){return clickable;}List<AccessibilityNodeInfo> findAccessibilityNodeInfosByText(String value){return Collections.emptyList();}
 CharSequence getClassName(){return "android.widget.Button";}int getActions(){return ACTION_CLICK;}void getBoundsInScreen(android.graphics.Rect out){}
 boolean performAction(int action){calls++;if(actionError!=null)throw actionError;return ack;}
}
final class AccessibilityWindowInfo{static final int TYPE_INPUT_METHOD=2;int getType(){return 2;}int getId(){return 34;}AccessibilityNodeInfo getRoot(){AccessibilityNodeInfo root=new AccessibilityNodeInfo();return root;}}
final class Automation{int clears,windowReads;void clearCache(){clears++;}List<AccessibilityWindowInfo> getWindows(){windowReads++;return Arrays.asList(new AccessibilityWindowInfo());}}
final class LayoutChecks{static Automation auto;static AccessibilityNodeInfo find(AccessibilityNodeInfo root,String label,boolean description){return null;}}
final class ImeSelectorDiagnostics{static AccessibilityNodeInfo currentRoot(AccessibilityWindowInfo w){return w.getRoot();}}
final class Endpoint{static String description;static AccessibilityNodeInfo node;static int misses,lookups;static AccessibilityNodeInfo lookup(){lookups++;if(misses>0){misses--;return null;}return node.refresh()?node:null;}}
final class StrictImeSelector{static AccessibilityNodeInfo findOwned(AccessibilityNodeInfo root,String description){return Endpoint.lookup();}}
final class StrictImeWindowQuery{
 static int calls;static class Result{AccessibilityNodeInfo node;public String toString(){return "MOCK strict IME window34";}}
 static void beginObservation(Automation auto){auto.clearCache();}
 static Result find(Automation auto,int type,String owner,String label,boolean description){if(type!=2||!owner.equals("org.wangshu.ime")||!label.equals(Endpoint.description)||!description)throw new AssertionError("strict delegation parameters changed");calls++;Result r=new Result();r.node=Endpoint.lookup();return r;}
}
'''
hashes={}
for name in ['MathAssociationUiChecks','LearningUiChecks']:
 p=base/(name+'.java');s=p.read_text();hashes[name]=hashlib.sha256(p.read_bytes()).hexdigest();names=['await','node','click']+(['awaitNode'] if name.startswith('Learning') else [])
 if 'private static void performBoundClick(' in s:names.append('performBoundClick')
 condition='interface Condition{boolean yes()throws Exception;}' if name.startswith('Learning') else 'interface Condition{boolean yes();}'
 prefix+='final class '+name+'{static int nativeActions;static StringBuilder log=new StringBuilder();static String lastQuery="old";'+condition+'\n'+'\n'.join(method(s,n) for n in names)+'\nstatic void invoke()throws Exception{click(Endpoint.description);}}\n'
tests=r'''public final class SuiteWindowQueryChecks {
 static int pass,fail;interface Run{void call()throws Exception;}interface Case{void run()throws Exception;}static void require(boolean yes,String why){if(!yes)throw new AssertionError(why);}static void test(String label,Case action){try{action.run();pass++;System.out.println("PASS "+label);}catch(Exception|AssertionError e){fail++;System.out.println("FAIL "+label+" :: "+e.getClass().getSimpleName()+" "+e.getMessage());}}
 static AccessibilityNodeInfo setup(String suite){LayoutChecks.auto=new Automation();SystemClock.now=0;StrictImeWindowQuery.calls=0;Endpoint.lookups=Endpoint.misses=0;Endpoint.description=suite.equals("math")?"数学联想 精确结果 3":"中英文切换";AccessibilityNodeInfo n=new AccessibilityNodeInfo();n.desc=Endpoint.description;Endpoint.node=n;MathAssociationUiChecks.log=new StringBuilder();LearningUiChecks.log=new StringBuilder();return n;}
 static String log(String suite){return (suite.equals("math")?MathAssociationUiChecks.log:LearningUiChecks.log).toString();}
 public static void main(String[] args){for(String suite:new String[]{"math","learning"}){Run run=suite.equals("math")?MathAssociationUiChecks::invoke:LearningUiChecks::invoke;
  test(suite+" delegates each poll strictly and clears observation cache once per await",()->{AccessibilityNodeInfo n=setup(suite);Endpoint.misses=2;run.call();require(n.calls==1&&StrictImeWindowQuery.calls==3&&LayoutChecks.auto.clears==1&&LayoutChecks.auto.windowReads==0,"old window path or per-poll clear retained");});
  test(suite+" accepted exact target is acted on once and logs ACK",()->{AccessibilityNodeInfo n=setup(suite);run.call();require(n.calls==1&&log(suite).contains("ack=true"),"accepted single action evidence missing");});
  test(suite+" false ACK fails immediately without retry",()->{AccessibilityNodeInfo n=setup(suite);n.ack=false;boolean rejected=false;try{run.call();}catch(AssertionError error){rejected=error.getMessage().contains("no retry");}require(rejected&&n.calls==1&&log(suite).contains("ack=false"),"false ACK treated as success or retried");});
  test(suite+" obsolete/window/label/owner mutation after query prevents action",()->{for(int kind=1;kind<=4;kind++){AccessibilityNodeInfo n=setup(suite);n.mutation=kind;boolean rejected=false;try{run.call();}catch(AssertionError error){rejected=error.getMessage().contains("no action sent");}require(rejected&&n.calls==0,"post-query mutated target acted on kind="+kind);}});
  test(suite+" action exception is retained with one-attempt receipt",()->{AccessibilityNodeInfo n=setup(suite);RuntimeException original=new IllegalStateException("owned fake action uncertainty");n.actionError=original;Throwable seen=null;try{run.call();}catch(RuntimeException error){seen=error;}require(seen==original&&n.calls==1&&log(suite).contains("ack=THREW"),"uncertain action not recorded or original replaced");});
 }
 System.out.println("RESULT "+pass+" PASS / "+fail+" FAIL; exact suite node/await/click, explicit strict-window-helper mock only; no Android UI/actual window execution");if(fail>0)System.exit(1);
 }
}
'''
java=out/'SuiteWindowQueryChecks.java';java.write_text(prefix+tests);rect=out/'Rect.java';rect.write_text('package android.graphics;public class Rect{public String toShortString(){return "[0,0][10,10]";}}');c=subprocess.run([str(repo/'.tools/jdk21/bin/javac'),'-encoding','UTF-8','-d',str(out),str(java),str(rect)],text=True,capture_output=True);(out/'javac.txt').write_text(c.stdout+c.stderr)
r=subprocess.run([str(repo/'.tools/jdk21/bin/java'),'-cp',str(out),'org.wangshu.ime.SuiteWindowQueryChecks'],text=True,capture_output=True) if c.returncode==0 else None
if r:(out/'results.txt').write_text(r.stdout+r.stderr)
receipt={'mode':a.mode,'source_sha256':hashes,'case_sha256':hashlib.sha256(tests.encode()).hexdigest(),'javac_exit':c.returncode,'test_exit':None if r is None else r.returncode,'scope':'actual suite methods, explicit shared-query mock validates type/package/exact description/delegation; no Android window, UI or prefs IO'};(out/'receipt.json').write_text(json.dumps(receipt,indent=2)+'\n');print(json.dumps(receipt));print(c.stdout+c.stderr if r is None else r.stdout+r.stderr);raise SystemExit(r.returncode if r is not None else c.returncode)
