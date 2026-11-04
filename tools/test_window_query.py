"""Window-generation and strict-query checks using actual test methods + fake Android endpoints."""
import argparse,hashlib,importlib.util,json,re,subprocess
from pathlib import Path
ap=argparse.ArgumentParser();ap.add_argument('--mode',choices=['red','green'],required=True);ap.add_argument('--output',type=Path,required=True);a=ap.parse_args()
project=Path(__file__).resolve().parents[1];repo=project.parents[1];app=project/'android/app/src/androidTest/java/org/wangshu/ime';out=a.output.resolve();out.mkdir(parents=True,exist_ok=False)
prior=project/'staging/strict-toolbar-review-20261026/host/run_checks.py';spec=importlib.util.spec_from_file_location('explicit_fake_endpoints',prior);module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module)
fakes=dict(module.FAKES)
fakes['android/app/UiAutomation.java']=fakes['android/app/UiAutomation.java'].replace('return windows;','return new ArrayList<>(windows);').replace('public final List<','public boolean clearCache(){return true;}public final List<')
fakes['android/view/accessibility/AccessibilityWindowInfo.java']=fakes['android/view/accessibility/AccessibilityWindowInfo.java'].replace('TYPE_INPUT_METHOD=2;','TYPE_INPUT_METHOD=2,TYPE_SYSTEM=3;')
for name,data in fakes.items():
 p=out/'src'/name;p.parent.mkdir(parents=True,exist_ok=True);p.write_text(data)
pkg=out/'src/org/wangshu/ime';pkg.mkdir(parents=True,exist_ok=True)
hashes={}
for name in ['StrictImeSelector.java','ImeSelectorDiagnostics.java','StrictImeWindowQuery.java']:
 p=app/name;hashes[name]=hashlib.sha256(p.read_bytes()).hexdigest();(pkg/name).write_bytes(p.read_bytes())
def method(s,name):
 start=s.index('private static '+name);opening=s.index('{',start);depth=1;end=opening+1
 while depth:depth+=(s[end]=='{')-(s[end]=='}');end+=1
 return s[start:end]
old=project/'evidence/2026-10-28-window-query/old-source/ToolbarUiChecks.java';s=old.read_text();hashes['old ToolbarUiChecks']=hashlib.sha256(old.read_bytes()).hexdigest()
legacy='package org.wangshu.ime;import java.util.*;import android.os.Build;import android.app.UiAutomation;import android.view.accessibility.*;final class LayoutChecks{static UiAutomation auto;}final class LegacyQuery {static String lastQuery;'+method(s,'boolean matches(')+method(s,'void noAnr(')+method(s,'AccessibilityNodeInfo node(')+'static AccessibilityNodeInfo find(int type,String owner,String label,boolean description){return node(type,owner,label,description);}}'
(pkg/'LegacyQuery.java').write_text(legacy)
test=r'''package org.wangshu.ime;import java.util.*;import android.app.UiAutomation;import android.os.SystemClock;import android.view.accessibility.*;
public final class WindowQueryChecks {
 static boolean old;static int pass,fail;static String detail;static final String LABEL="数学",OWNER="org.wangshu.ime";static UiAutomation auto;
 static class Window extends AccessibilityWindowInfo {int reads;Runnable beforeRoot=()->{};@Override public AccessibilityNodeInfo getRoot(){reads++;beforeRoot.run();return root;}@Override public AccessibilityNodeInfo getRoot(int flags){return getRoot();}}
 static AccessibilityNodeInfo node(String owner,String label,int window){AccessibilityNodeInfo n=new AccessibilityNodeInfo();n.pkg=owner;n.desc=label;n.window=window;return n;}
 static Window window(int type,int id,String owner,AccessibilityNodeInfo... children){Window w=new Window();w.id=id;w.type=type;w.root=node(owner,null,id);w.root.children.addAll(Arrays.asList(children));return w;}
 static Window sys(){return window(3,31,"android");}static AccessibilityNodeInfo target(){return node(OWNER,LABEL,34);}static Window ime(AccessibilityNodeInfo n){return window(2,34,OWNER,n);}
 static void setup(Window... windows){auto=new UiAutomation();auto.windows.addAll(Arrays.asList(windows));LayoutChecks.auto=auto;LegacyQuery.lastQuery="label=独立应用编辑器 window=25 outcome=STALE_ROOT";SystemClock.now=0;}
 static AccessibilityNodeInfo query(){if(old){AccessibilityNodeInfo n=LegacyQuery.find(2,OWNER,LABEL,true);detail=LegacyQuery.lastQuery;return n;}StrictImeWindowQuery.Result r=StrictImeWindowQuery.find(auto,2,OWNER,LABEL,true);detail=r.toString();return r.node;}
 static void require(boolean yes,String why){if(!yes)throw new AssertionError(why+" detail="+detail);}interface Case{void run();}static void test(String label,Case run){try{run.run();pass++;System.out.println("PASS "+label);}catch(Exception|AssertionError e){fail++;System.out.println("FAIL "+label+" :: "+e.getMessage());}}
 public static void main(String[] args){old=args[0].equals("red");
  test("IME created during system guard is found in a fresh window generation",()->{Window sys=sys(),host=window(1,25,"org.wangshu.scan.test");AccessibilityNodeInfo math=target();Window current=ime(math);setup(sys,host);sys.beforeRoot=()->{sys.beforeRoot=()->{};auto.windows.clear();auto.windows.add(sys);auto.windows.add(window(1,33,"org.wangshu.scan.test"));auto.windows.add(current);};require(query()==math,"held stale pre-guard window list");});
  test("no IME reports current requested label and absence instead of prior editor error",()->{setup(sys(),window(1,25,"org.wangshu.scan.test"));require(query()==null&&detail.contains("label=数学")&&detail.contains("NO_REQUESTED_WINDOW"),"misleading stale lastQuery");});
  test("root and descriptor window mismatch is rejected with its actual reason",()->{Window bad=ime(node(OWNER,LABEL,35));bad.root.window=35;setup(sys(),bad);require(query()==null&&detail.contains("ROOT_WINDOW_MISMATCH"),"foreign-window root accepted or silently omitted");});
  test("descriptor mutation during root retrieval is rejected and recorded",()->{Window changed=ime(target());changed.beforeRoot=()->changed.id=35;setup(sys(),changed);require(query()==null&&detail.contains("DESCRIPTOR_CHANGED"),"changed descriptor not diagnosed");});
  test("unrelated application root is never fetched before a valid IME target",()->{AccessibilityNodeInfo math=target();Window irrelevant=window(1,33,"org.wangshu.scan.test");irrelevant.beforeRoot=()->SystemClock.now+=30000;setup(irrelevant,sys(),ime(math));require(query()==math&&irrelevant.reads==0,"unrelated root starved requested-type query, reads="+irrelevant.reads);});
  test("visible exact ANR blocks target lookup before its root is read",()->{Window active=ime(target()),sys=sys();sys.root.text="System UI isn't responding";setup(active,sys);boolean rejected=false;try{query();}catch(AssertionError e){rejected=e.getMessage().contains("system ANR");}require(rejected&&active.reads==0,"dialog guard was late or absent");});
  test("new system window after guard prevents target authorization",()->{Window sys=sys(),active=ime(target()),dialog=window(3,99,"android");dialog.root.text="Quickstep isn't responding";setup(sys,active);sys.beforeRoot=()->{sys.beforeRoot=()->{};auto.windows.add(dialog);};require(query()==null&&detail.contains("SYSTEM_WINDOWS_CHANGED_AFTER_GUARD"),"new dialog was absent from held guard list");boolean rejected=false;try{query();}catch(AssertionError e){rejected=e.getMessage().contains("system ANR");}require(rejected,"next observation did not inspect the new dialog");});
  test("wrong-type math description cannot replace a TYPE_INPUT_METHOD window",()->{setup(sys(),window(1,34,OWNER,target()));require(query()==null,"type guard weakened");});
  test("valid owned enabled visible same-window math is still found",()->{AccessibilityNodeInfo math=target(),wrong=node("other.package",LABEL,34);setup(sys(),window(2,34,OWNER,wrong,math));require(query()==math,"valid strict sibling missed");});
  test("excessive window count is bounded before requesting roots",()->{Window[] many=new Window[17];for(int i=0;i<many.length;i++)many[i]=window(1,100+i,"other");setup(many);boolean rejected=false;try{query();}catch(AssertionError e){rejected=e.getMessage().contains("window-count limit");}require(rejected&&Arrays.stream(many).mapToInt(w->w.reads).sum()==0,"window limit missing");});
  System.out.println("RESULT "+pass+" PASS / "+fail+" FAIL; actual query code, explicit mutable fake window snapshots only; NOT Android runtime/root-cause timing proof");if(fail>0)System.exit(1);
 }
}
'''
(pkg/'WindowQueryChecks.java').write_text(test)
c=subprocess.run([str(repo/'.tools/jdk21/bin/javac'),'-encoding','UTF-8','-d',str(out/'classes')]+[str(p) for p in (out/'src').rglob('*.java')],text=True,capture_output=True);(out/'javac.txt').write_text(c.stdout+c.stderr)
r=subprocess.run([str(repo/'.tools/jdk21/bin/java'),'-cp',str(out/'classes'),'org.wangshu.ime.WindowQueryChecks',a.mode],text=True,capture_output=True) if c.returncode==0 else None
if r:(out/'results.txt').write_text(r.stdout+r.stderr)
receipt={'mode':a.mode,'source_sha256':hashes,'case_sha256':hashlib.sha256(test.encode()).hexdigest(),'javac_exit':c.returncode,'test_exit':None if r is None else r.returncode,'scope':'actual window query + strict leaf selector, fake system/application/IME root transitions; no actual Android process, timing or UI action'};(out/'receipt.json').write_text(json.dumps(receipt,indent=2)+'\n');print(json.dumps(receipt));print(c.stdout+c.stderr if r is None else r.stdout+r.stderr);raise SystemExit(r.returncode if r is not None else c.returncode)
