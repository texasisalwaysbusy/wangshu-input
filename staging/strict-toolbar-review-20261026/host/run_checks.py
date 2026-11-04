#!/usr/bin/env python3
"""Compile the exact Android helpers against explicit fake node/clock endpoints.

This is traversal/fault-injection evidence, never actual Android accessibility.
No emulator, dependencies, Gradle, files from the real app or real user text.
"""
import hashlib, json, pathlib, subprocess, sys

ROOT=pathlib.Path(__file__).resolve().parents[5]
HERE=pathlib.Path(__file__).resolve().parent
SOURCE=ROOT/'projects/input-method/android/app/src/androidTest/java/org/wangshu/ime'
JDK=ROOT/'.tools/jdk21/bin'
FAKES={
'android/os/SystemClock.java':'''package android.os; public final class SystemClock {public static long now;public static long elapsedRealtime(){return now;}}''',
'android/os/Build.java':'''package android.os; public final class Build {public static class VERSION {public static int SDK_INT=36;}}''',
'android/graphics/Rect.java':'''package android.graphics; public class Rect {public String toString(){return "Rect(0, 0 - 480, 800)";}}''',
'android/accessibilityservice/AccessibilityServiceInfo.java':'''package android.accessibilityservice; public class AccessibilityServiceInfo {public int flags=65618;}''',
'android/view/accessibility/AccessibilityNodeInfo.java':'''package android.view.accessibility;
import java.util.*;import android.graphics.Rect;import android.os.SystemClock;
public class AccessibilityNodeInfo {
 public static final int ACTION_CLICK=16,FLAG_PREFETCH_DESCENDANTS_BREADTH_FIRST=16;
 public static int totalRefresh;public static long refreshCost;
 public String desc,text,pkg="org.wangshu.ime";public int window=8;public boolean visible=true,enabled=true,fresh=true,important=true,clickable=true;public Runnable onRefresh;
 public final List<AccessibilityNodeInfo> children=new ArrayList<>(),fast=new ArrayList<>();
 public boolean refresh(){totalRefresh++;SystemClock.now+=refreshCost;if(onRefresh!=null)onRefresh.run();return fresh;}
 public CharSequence getContentDescription(){return desc;}public CharSequence getText(){return text;}public CharSequence getPackageName(){return pkg;}public CharSequence getClassName(){return "android.widget.ImageButton";}
 public boolean isVisibleToUser(){return visible;}public boolean isEnabled(){return enabled;}public boolean isImportantForAccessibility(){return important;}public boolean isClickable(){return clickable;}public int getActions(){return ACTION_CLICK;}public int getWindowId(){return window;}
 public int getChildCount(){return children.size();}public AccessibilityNodeInfo getChild(int i){return children.get(i);}public void getBoundsInScreen(Rect r){}
 public List<AccessibilityNodeInfo> findAccessibilityNodeInfosByText(String value){return fast;}
}''',
'android/view/accessibility/AccessibilityWindowInfo.java':'''package android.view.accessibility;import android.graphics.Rect;
public class AccessibilityWindowInfo {public static final int TYPE_INPUT_METHOD=2;public int type=2,id=8;public boolean active;public AccessibilityNodeInfo root;public int getType(){return type;}public int getId(){return id;}public AccessibilityNodeInfo getRoot(){return root;}public AccessibilityNodeInfo getRoot(int flags){return root;}public boolean isActive(){return active;}public boolean isFocused(){return active;}public void getBoundsInScreen(Rect r){}}''',
'android/app/UiAutomation.java':'''package android.app;import java.util.*;import android.accessibilityservice.AccessibilityServiceInfo;import android.view.accessibility.AccessibilityWindowInfo;
public class UiAutomation {public final List<AccessibilityWindowInfo> windows=new ArrayList<>();public List<AccessibilityWindowInfo> getWindows(){return windows;}public AccessibilityServiceInfo getServiceInfo(){return new AccessibilityServiceInfo();}}''',
}
TEST='''package org.wangshu.ime;
import android.view.accessibility.*;import android.app.UiAutomation;import android.os.SystemClock;
public final class SelectorContractChecks {
 static int pass,fail;static final String LABEL="数学";interface Case{void run();}
 static AccessibilityNodeInfo leaf(){AccessibilityNodeInfo n=new AccessibilityNodeInfo();n.desc=LABEL;return n;}
 static AccessibilityNodeInfo root(AccessibilityNodeInfo... children){AccessibilityNodeInfo n=new AccessibilityNodeInfo();for(AccessibilityNodeInfo c:children)n.children.add(c);return n;}
 static void require(boolean ok,String reason){if(!ok)throw new AssertionError(reason);}
 static void check(String label,Case c){AccessibilityNodeInfo.totalRefresh=0;AccessibilityNodeInfo.refreshCost=0;SystemClock.now=0;try{c.run();pass++;System.out.println("PASS "+label);}catch(AssertionError|StackOverflowError e){fail++;System.out.println("FAIL "+label+" :: "+e.getClass().getSimpleName()+" "+e.getMessage());}}
 public static void main(String[] args){
  check("exact owned visible enabled fresh leaf",()->{AccessibilityNodeInfo n=leaf();require(StrictImeSelector.findOwned(root(n),LABEL)==n,"not found");});
  check("text alone never substitutes description",()->{AccessibilityNodeInfo n=leaf();n.desc=null;n.text=LABEL;require(StrictImeSelector.findOwned(root(n),LABEL)==null,"text-only accepted");});
  check("wrong owner duplicate does not shadow valid sibling",()->{AccessibilityNodeInfo wrong=leaf(),n=leaf();wrong.pkg="another.app";require(StrictImeSelector.findOwned(root(wrong,n),LABEL)==n,"owner boundary");});
  check("disabled duplicate does not shadow valid sibling",()->{AccessibilityNodeInfo wrong=leaf(),n=leaf();wrong.enabled=false;require(StrictImeSelector.findOwned(root(wrong,n),LABEL)==n,"enabled boundary");});
  check("hidden target is rejected",()->{AccessibilityNodeInfo n=leaf();n.visible=false;require(StrictImeSelector.findOwned(root(n),LABEL)==null,"visibility boundary");});
  check("obsolete leaf is rejected after refresh",()->{AccessibilityNodeInfo n=leaf();n.fresh=false;require(StrictImeSelector.findOwned(root(n),LABEL)==null,"stale leaf accepted");});
  check("obsolete root is rejected",()->{AccessibilityNodeInfo n=root(leaf());n.fresh=false;require(StrictImeSelector.findOwned(n,LABEL)==null,"stale root accepted");});
  check("label changed during final refresh is rejected",()->{AccessibilityNodeInfo n=leaf();n.onRefresh=()->n.desc="字母";require(StrictImeSelector.findOwned(root(n),LABEL)==null,"old label accepted");});
  check("owner changed during final refresh is rejected",()->{AccessibilityNodeInfo n=leaf();n.onRefresh=()->n.pkg="another.app";require(StrictImeSelector.findOwned(root(n),LABEL)==null,"changed owner accepted");});
  check("window changed during final refresh is rejected",()->{AccessibilityNodeInfo n=leaf();n.onRefresh=()->n.window=99;require(StrictImeSelector.findOwned(root(n),LABEL)==null,"node migrated to another window");});
  check("cached leaf from a different window is rejected",()->{AccessibilityNodeInfo n=leaf();n.window=99;require(StrictImeSelector.findOwned(root(n),LABEL)==null,"wrong window accepted");});
  check("root refresh exposes rebuilt toolbar rather than old children",()->{AccessibilityNodeInfo n=leaf(),r=root();r.onRefresh=()->{r.children.clear();r.children.add(n);};require(StrictImeSelector.findOwned(r,LABEL)==n,"rebuilt ancestor ignored");});
  check("fallback inspects 80 containers with at most root plus leaf refresh",()->{AccessibilityNodeInfo n=leaf(),r=root();for(int i=0;i<80;i++)r.children.add(root());r.children.add(n);require(StrictImeSelector.findOwned(r,LABEL)==n,"target absent");require(AccessibilityNodeInfo.totalRefresh<=2,"per-container refresh count="+AccessibilityNodeInfo.totalRefresh);});
  check("cyclic child IDs terminate without stack overflow",()->{AccessibilityNodeInfo r=root();r.children.add(r);require(StrictImeSelector.findOwned(r,LABEL)==null,"cycle returned target");});
  check("pathological 10000-level ancestry is bounded",()->{AccessibilityNodeInfo r=root(),last=r;for(int i=0;i<10000;i++){AccessibilityNodeInfo next=root();last.children.add(next);last=next;}require(StrictImeSelector.findOwned(r,LABEL)==null,"absent target returned");require(AccessibilityNodeInfo.totalRefresh<=2,"pathological remote refresh storm");});
  check("null child remains safe and does not hide valid sibling",()->{AccessibilityNodeInfo n=leaf();require(StrictImeSelector.findOwned(root(null,n),LABEL)==n,"null child failure");});
  check("failure diagnostic reaches IME before system-window budget exhaustion",()->{
   UiAutomation auto=new UiAutomation();for(int w=0;w<2;w++){AccessibilityWindowInfo sys=new AccessibilityWindowInfo();sys.id=w;sys.type=3;sys.root=root();sys.root.pkg="com.android.systemui";for(int i=0;i<24;i++)sys.root.children.add(root());auto.windows.add(sys);}
   AccessibilityWindowInfo ime=new AccessibilityWindowInfo();ime.root=root(leaf());auto.windows.add(ime);AccessibilityNodeInfo.refreshCost=100;
   String report=ImeSelectorDiagnostics.snapshot(auto);require(report.contains("TARGET ")&&report.indexOf("window id=8")<report.indexOf("window id=0"),"IME omitted or delayed behind system windows");
   System.out.println("MODEL_DIAGNOSTIC "+report.replace('\\n','|'));
  });
  System.out.println("RESULT "+pass+" PASS / "+fail+" FAIL; exact helper source; fake node topology and virtual refresh costs only, NOT Android runtime");if(fail>0)System.exit(1);
 }
}
'''
def main():
    label=sys.argv[1]
    output=HERE/label;output.mkdir(exist_ok=False)
    source=(HERE.parent/'source-before') if label=='red' else SOURCE
    hashes={}
    for name in ['StrictImeSelector.java','ImeSelectorDiagnostics.java']:
        p=source/name;data=p.read_bytes();hashes[name]=hashlib.sha256(data).hexdigest()
        dest=output/'src/org/wangshu/ime'/name;dest.parent.mkdir(parents=True,exist_ok=True);dest.write_bytes(data)
    for name,data in FAKES.items():
        p=output/'src'/name;p.parent.mkdir(parents=True,exist_ok=True);p.write_text(data)
    (output/'src/org/wangshu/ime/SelectorContractChecks.java').write_text(TEST)
    (output/'classes').mkdir()
    compile=subprocess.run([str(JDK/'javac'),'-encoding','UTF-8','-d',str(output/'classes')]+[str(p) for p in (output/'src').rglob('*.java')],text=True,capture_output=True)
    (output/'javac.txt').write_text(compile.stdout+compile.stderr)
    run=None
    if compile.returncode==0:
        run=subprocess.run([str(JDK/'java'),'-Xss512k','-cp',str(output/'classes'),'org.wangshu.ime.SelectorContractChecks'],text=True,capture_output=True,timeout=30)
        (output/'results.txt').write_text(run.stdout+run.stderr)
    receipt={'helper_sha256':hashes,'unchanged_case_sha256':hashlib.sha256(TEST.encode()).hexdigest(),'javac_exit':compile.returncode,'test_exit':None if run is None else run.returncode,'scope':'exact helpers, explicit synthetic nodes/clock; not Android accessibility or latency measurement'}
    (output/'verification.json').write_text(json.dumps(receipt,indent=2)+'\n')
    print(json.dumps(receipt));print('COMPILE',compile.stdout+compile.stderr);print('RUN',None if run is None else run.stdout)
if __name__=='__main__': main()
