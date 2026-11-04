"""Actual strict query/selector + Math numeric action, fake Android windows/actions only."""
import argparse,hashlib,importlib.util,json,re,subprocess
from pathlib import Path
ap=argparse.ArgumentParser();ap.add_argument('--mode',choices=['red','green'],required=True);ap.add_argument('--output',type=Path,required=True);a=ap.parse_args();project=Path(__file__).resolve().parents[1];repo=project.parents[1];app=project/'android/app/src/androidTest/java/org/wangshu/ime';out=a.output.resolve();out.mkdir(parents=True,exist_ok=False)
fixture=project/'staging/strict-toolbar-review-20261026/host/run_checks.py';spec=importlib.util.spec_from_file_location('fake_endpoints',fixture);f=importlib.util.module_from_spec(spec);spec.loader.exec_module(f);fakes=dict(f.FAKES)
fakes['android/os/SystemClock.java']=fakes['android/os/SystemClock.java'].replace('public static long now;','public static long now;public static void sleep(long millis){now+=millis;}')
fakes['android/app/UiAutomation.java']=fakes['android/app/UiAutomation.java'].replace('return windows;','return new ArrayList<>(windows);').replace('public final List<','public boolean clearCache(){return true;}public final List<')
fakes['android/view/accessibility/AccessibilityWindowInfo.java']=fakes['android/view/accessibility/AccessibilityWindowInfo.java'].replace('TYPE_INPUT_METHOD=2;','TYPE_INPUT_METHOD=2,TYPE_SYSTEM=3;')
fakes['android/graphics/Rect.java']=fakes['android/graphics/Rect.java'].replace('public String toString()', 'public String toShortString(){return "[0,0][60,60]";}public String toString()')
n=fakes['android/view/accessibility/AccessibilityNodeInfo.java'];n=n.replace('public static int totalRefresh;','public int calls,refreshes;public boolean ack=true,advertisesClick=true;public long actionMillis;public RuntimeException actionError;public static int totalRefresh;').replace('totalRefresh++;','totalRefresh++;refreshes++;').replace('return ACTION_CLICK;','return advertisesClick?ACTION_CLICK:0;')
at=n.rfind('}');n=n[:at]+'''public boolean performAction(int action){calls++;SystemClock.now+=actionMillis;if(actionError!=null)throw actionError;return ack;}'''+n[at:];fakes['android/view/accessibility/AccessibilityNodeInfo.java']=n
for name,data in fakes.items():
 p=out/'src'/name;p.parent.mkdir(parents=True,exist_ok=True);p.write_text(data)
pkg=out/'src/org/wangshu/ime';pkg.mkdir(parents=True,exist_ok=True);hashes={}
for name in ['StrictImeSelector.java','StrictImeWindowQuery.java','ImeSelectorDiagnostics.java']:
 p=app/name;hashes[name]=hashlib.sha256(p.read_bytes()).hexdigest();(pkg/name).write_bytes(p.read_bytes())
def method(s,prefix):
 start=s.index(prefix);opening=s.index('{',start);depth=1;end=opening+1
 while depth:depth+=(s[end]=='{')-(s[end]=='}');end+=1
 return s[start:end]
layout=(app/'LayoutChecks.java').read_text();case=(app/'CaseModeChecks.java').read_text();math=(app/'MathAssociationUiChecks.java').read_text();old=(project/'evidence/2026-10-28-math-text-action/old-source/MathAssociationUiChecks.java').read_text()
for name,data in [('CaseModeChecks.java',case),('MathAssociationUiChecks.java',math),('old MathAssociationUiChecks.java',old)]:hashes[name]=hashlib.sha256(data.encode()).hexdigest()
source='package org.wangshu.ime;import java.util.*;import android.os.*;import android.app.UiAutomation;import android.view.accessibility.*;\n'
source+='final class LayoutChecks{static UiAutomation auto;'+method(layout,'static AccessibilityNodeInfo find(')+method(layout,'static AccessibilityNodeInfo await(')+'}\n'
source+='final class CaseModeChecks{'+re.search(r'static StringBuilder actions[^;]*;',case).group(0)+method(case,'static void click(')+'}\n'
source+='final class MathProbe{static int nativeActions;static StringBuilder log;static String lastQuery="none";interface Condition{boolean yes();}\n'+'\n'.join(method(math,x) for x in ['private static void await(','private static AccessibilityNodeInfo nodeText(','private static void clickText(','private static void performBoundClick('])
legacy=re.search(r'try\{CaseModeChecks\.click\("123",false\);\}finally\{log\.append\(CaseModeChecks\.actions\);\}',old).group(0)
source+='static void invoke(boolean old)throws Exception{log=new StringBuilder();CaseModeChecks.actions=new StringBuilder();if(old){'+legacy+'}else{clickText("123");}}}\n'
test=r'''public final class MathTextActionChecks {
 static int pass,fail;static boolean old;interface Case{void run()throws Exception;}static void require(boolean yes,String why){if(!yes)throw new AssertionError(why);}static void test(String name,Case action){SystemClock.now=0;try{action.run();pass++;System.out.println("PASS "+name);}catch(Exception|AssertionError e){fail++;System.out.println("FAIL "+name+" :: "+e.getClass().getSimpleName()+" "+e.getMessage());}}
 static AccessibilityNodeInfo button(String owner,int window){AccessibilityNodeInfo n=new AccessibilityNodeInfo();n.pkg=owner;n.window=window;n.text="123";return n;}
 static AccessibilityWindowInfo window(int type,int id,AccessibilityNodeInfo child){AccessibilityWindowInfo w=new AccessibilityWindowInfo();w.type=type;w.id=id;w.root=new AccessibilityNodeInfo();w.root.window=id;w.root.pkg=child.pkg;w.root.children.add(child);return w;}
 static void setup(AccessibilityWindowInfo... windows){LayoutChecks.auto=new UiAutomation();LayoutChecks.auto.windows.addAll(Arrays.asList(windows));}
 static void invoke()throws Exception{MathProbe.invoke(old);}
 public static void main(String[] args){old=args[0].equals("red");
  test("duplicate application text cannot receive the IME numeric action",()->{AccessibilityNodeInfo app=button("other.app",25),ime=button("org.wangshu.ime",34);app.ack=false;setup(window(1,25,app),window(2,34,ime));invoke();require(app.calls==0&&ime.calls==1,"generic text lookup selected a foreign window");});
  test("valid exact IME numeric button receives exactly one action",()->{AccessibilityNodeInfo ime=button("org.wangshu.ime",34);setup(window(2,34,ime));invoke();require(ime.calls==1,"valid target not acted on once");});
  test("known IME false ACK is recorded with receiver metadata and elapsed time",()->{AccessibilityNodeInfo ime=button("org.wangshu.ime",34);ime.ack=false;ime.actionMillis=5000;setup(window(2,34,ime));boolean rejected=false;try{invoke();}catch(AssertionError e){rejected=e.getMessage().contains("no retry");}String log=MathProbe.log.toString();require(rejected&&ime.calls==1&&log.contains("ack=false")&&log.contains("elapsedMs=5000")&&log.contains("expectedWindow=34")&&log.contains("pkg=org.wangshu.ime"),"uncertain single action lacks strict identity/timing evidence");});
  test("application-only duplicate never substitutes for an IME control",()->{AccessibilityNodeInfo app=button("other.app",25);setup(window(1,25,app));boolean rejected=false;try{invoke();}catch(AssertionError e){rejected=true;}require(rejected&&app.calls==0,"application text was clicked without an IME window");});
  test("descriptor/root mismatch cannot authorize the numeric action",()->{AccessibilityNodeInfo ime=button("org.wangshu.ime",35);AccessibilityWindowInfo w=window(2,34,ime);w.root.window=35;setup(w);boolean rejected=false;try{invoke();}catch(AssertionError e){rejected=true;}require(rejected&&ime.calls==0,"different-window root accepted");});
  test("missing advertised CLICK action is rejected before dispatch",()->{AccessibilityNodeInfo ime=button("org.wangshu.ime",34);ime.advertisesClick=false;setup(window(2,34,ime));boolean rejected=false;try{invoke();}catch(AssertionError e){rejected=true;}require(rejected&&ime.calls==0,"unadvertised action dispatched");});
  test("uncertain action exception is preserved without second attempt",()->{AccessibilityNodeInfo ime=button("org.wangshu.ime",34);RuntimeException original=new IllegalStateException("owned action uncertainty");ime.actionError=original;setup(window(2,34,ime));Throwable seen=null;try{invoke();}catch(RuntimeException e){seen=e;}require(seen==original&&ime.calls==1&&MathProbe.log.toString().contains("THREW"),"uncertain action retried or original replaced");});
  System.out.println("RESULT "+pass+" PASS / "+fail+" FAIL; actual shared query/selector + exact numeric control methods, fake Android endpoints only; no real UI or timing-root-cause claim");if(fail>0)System.exit(1);
 }
}
'''
(pkg/'MathTextActionChecks.java').write_text(source+test);c=subprocess.run([str(repo/'.tools/jdk21/bin/javac'),'-encoding','UTF-8','-d',str(out/'classes')]+[str(p) for p in (out/'src').rglob('*.java')],text=True,capture_output=True);(out/'javac.txt').write_text(c.stdout+c.stderr)
r=subprocess.run([str(repo/'.tools/jdk21/bin/java'),'-cp',str(out/'classes'),'org.wangshu.ime.MathTextActionChecks',a.mode],text=True,capture_output=True) if c.returncode==0 else None
if r:(out/'results.txt').write_text(r.stdout+r.stderr)
v={'mode':a.mode,'source_sha256':hashes,'case_sha256':hashlib.sha256(test.encode()).hexdigest(),'javac_exit':c.returncode,'test_exit':None if r is None else r.returncode,'scope':'actual strict query + exact control methods, fake system/application/IME/action/timing nodes, not actual Android action'};(out/'receipt.json').write_text(json.dumps(v,indent=2)+'\n');print(json.dumps(v));print(c.stdout+c.stderr if r is None else r.stdout+r.stderr);raise SystemExit(r.returncode if r is not None else c.returncode)
