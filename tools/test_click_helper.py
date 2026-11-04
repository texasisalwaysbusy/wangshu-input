"""Exact shared native-click method with fake accessibility/clock endpoints, no Android actions."""
import argparse,hashlib,json,re,subprocess
from pathlib import Path
ap=argparse.ArgumentParser();ap.add_argument('--mode',choices=['red','green'],required=True);ap.add_argument('--output',type=Path,required=True);a=ap.parse_args()
project=Path(__file__).resolve().parents[1];repo=project.parents[1];path=(project/'evidence/2026-10-28-click-helper/old-source' if a.mode=='red' else project/'android/app/src/androidTest/java/org/wangshu/ime')/'CaseModeChecks.java';text=path.read_text();out=a.output.resolve();out.mkdir(parents=True,exist_ok=False)
start=text.index('static void click(');opening=text.index('{',start);depth=1;end=opening+1
while depth:depth+=(text[end]=='{')-(text[end]=='}');end+=1
method=text[start:end];field=re.search(r'static StringBuilder actions[^;]*;',text).group(0)
prefix=r'''package org.wangshu.ime;import java.util.*;
final class SystemClock{static long now;static long elapsedRealtime(){return now;}static void sleep(long millis){now+=millis;}}
final class AccessibilityNodeInfo {
 static final int ACTION_CLICK=16;String text="123",desc="tool";boolean visible=true,enabled=true,fresh=true,ack=true;int calls,refreshes;Runnable onRefresh=()->{};RuntimeException actionError;
 boolean refresh(){refreshes++;onRefresh.run();return fresh;}CharSequence getText(){return text;}CharSequence getContentDescription(){return desc;}boolean isVisibleToUser(){return visible;}boolean isEnabled(){return enabled;}
 boolean performAction(int action){calls++;if(actionError!=null)throw actionError;return ack;}
}
final class LayoutChecks {static AccessibilityNodeInfo[] nodes;static int reads;static AccessibilityNodeInfo await(String name,boolean description){return nodes[Math.min(reads++,nodes.length-1)];}}
final class CaseModeChecks {FIELD METHOD}
'''.replace('FIELD',field).replace('METHOD',method)
test=r'''public final class ClickHelperChecks {
 static int pass,fail;interface Case{void run()throws Exception;}static void test(String label,Case action){SystemClock.now=0;try{action.run();pass++;System.out.println("PASS "+label);}catch(Exception|AssertionError e){fail++;System.out.println("FAIL "+label+" :: "+e.getClass().getSimpleName()+" "+e.getMessage());}}
 static void require(boolean yes,String why){if(!yes)throw new AssertionError(why);}static void target(AccessibilityNodeInfo...nodes){LayoutChecks.nodes=nodes;LayoutChecks.reads=0;}static AccessibilityNodeInfo fresh(){return new AccessibilityNodeInfo();}
 public static void main(String[] args){
  test("first shared-helper call without CaseMode.run logs one accepted action",()->{AccessibilityNodeInfo n=fresh();target(n);CaseModeChecks.click("123",false);require(n.calls==1&&CaseModeChecks.actions.toString().contains("acknowledged=true")&&CaseModeChecks.actions.toString().contains("repeated=false"),"standalone call failed or did not record ACK");});
  test("explicit null action sink is prepared before sending an action",()->{CaseModeChecks.actions=null;AccessibilityNodeInfo n=fresh();target(n);CaseModeChecks.click("123",false);require(n.calls==1&&CaseModeChecks.actions!=null,"null log not handled before action");});
  test("existing action history remains intact",()->{CaseModeChecks.actions=new StringBuilder("earlier\n");AccessibilityNodeInfo n=fresh();target(n);CaseModeChecks.click("123",false);require(n.calls==1&&CaseModeChecks.actions.toString().startsWith("earlier\nACTION 123"),"existing evidence overwritten");});
  test("rejected ACK reports failure after exactly one attempt and no retry",()->{CaseModeChecks.actions=new StringBuilder();AccessibilityNodeInfo n=fresh();n.ack=false;target(n);boolean rejected=false;try{CaseModeChecks.click("123",false);}catch(AssertionError error){rejected=error.getMessage().contains("no retry");}require(rejected&&n.calls==1&&CaseModeChecks.actions.toString().contains("acknowledged=false"),"refused action silently continued or retried");});
  test("stale refresh deadline sends zero actions",()->{CaseModeChecks.actions=new StringBuilder();AccessibilityNodeInfo n=fresh();n.fresh=false;target(n);boolean rejected=false;try{CaseModeChecks.click("123",false);}catch(AssertionError error){rejected=error.getMessage().contains("no action sent");}require(rejected&&n.calls==0&&LayoutChecks.reads<=42,"stale target was clicked or wait was unbounded");});
  test("stale node may be reacquired before the sole actual action",()->{CaseModeChecks.actions=new StringBuilder();AccessibilityNodeInfo stale=fresh(),current=fresh();stale.fresh=false;target(stale,current);CaseModeChecks.click("123",false);require(stale.calls==0&&current.calls==1&&LayoutChecks.reads==2,"node polling repeated the action");});
  test("label changed by refresh is rejected without an action",()->{CaseModeChecks.actions=new StringBuilder();AccessibilityNodeInfo n=fresh();n.onRefresh=()->n.text="changed";target(n);boolean rejected=false;try{CaseModeChecks.click("123",false);}catch(AssertionError expected){rejected=true;}require(rejected&&n.calls==0,"refreshed wrong label was clicked");});
  test("hidden or disabled targets are not acted on",()->{for(int kind=0;kind<2;kind++){CaseModeChecks.actions=new StringBuilder();AccessibilityNodeInfo n=fresh();if(kind==0)n.visible=false;else n.enabled=false;target(n);boolean rejected=false;try{CaseModeChecks.click("123",false);}catch(AssertionError expected){rejected=true;}require(rejected&&n.calls==0,"ineligible target clicked");}});
  test("uncertain action exception preserves object and records one attempt",()->{CaseModeChecks.actions=new StringBuilder();AccessibilityNodeInfo n=fresh();RuntimeException original=new IllegalStateException("owned fake action uncertainty");n.actionError=original;target(n);Throwable seen=null;try{CaseModeChecks.click("123",false);}catch(RuntimeException e){seen=e;}require(seen==original&&n.calls==1&&CaseModeChecks.actions.toString().contains("acknowledged=THREW"),"uncertain action lost original evidence or retried");});
  test("description matching works without visible text",()->{CaseModeChecks.actions=new StringBuilder();AccessibilityNodeInfo n=fresh();n.text=null;n.desc="数学";target(n);CaseModeChecks.click("数学",true);require(n.calls==1,"description target not acted on exactly once");});
  System.out.println("RESULT "+pass+" PASS / "+fail+" FAIL; exact shared helper, fake accessibility action and clock only; Android/OS pointer NOT_RUN");if(fail>0)System.exit(1);
 }
}
'''
java=out/'ClickHelperChecks.java';java.write_text(prefix+test)
c=subprocess.run([str(repo/'.tools/jdk21/bin/javac'),'-encoding','UTF-8','-d',str(out),str(java)],text=True,capture_output=True);(out/'javac.txt').write_text(c.stdout+c.stderr)
r=subprocess.run([str(repo/'.tools/jdk21/bin/java'),'-cp',str(out),'org.wangshu.ime.ClickHelperChecks'],text=True,capture_output=True) if c.returncode==0 else None
if r:(out/'results.txt').write_text(r.stdout+r.stderr)
v={'mode':a.mode,'source_sha256':hashlib.sha256(path.read_bytes()).hexdigest(),'case_sha256':hashlib.sha256(test.encode()).hexdigest(),'javac_exit':c.returncode,'test_exit':None if r is None else r.returncode,'scope':'exact click+field initializer, fake node action counters, metadata and clock; no Android action'};(out/'receipt.json').write_text(json.dumps(v,indent=2)+'\n');print(json.dumps(v));print(c.stdout+c.stderr if r is None else r.stdout+r.stderr);raise SystemExit(r.returncode if r is not None else c.returncode)
