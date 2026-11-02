#!/usr/bin/env python3
# Modified 2026-11-02: standalone source-extracting host regression.
from pathlib import Path
import hashlib,json,subprocess,datetime,sys

tests=Path(__file__).resolve().parent
root=tests.parents[2]
rel='android/app/src/main/java/org/wangshu/ime/SettingsActivity.java'
out=Path(sys.argv[1]) if len(sys.argv)>1 else tests/('results-'+datetime.datetime.now(datetime.timezone.utc).strftime('%Y%m%dT%H%M%S%fZ'))
out.mkdir(exist_ok=False)
version=subprocess.run(['java','-XX:-UsePerfData','-Dfile.encoding=UTF-8','-version'],text=True,encoding='utf-8',capture_output=True,timeout=15)
if version.returncode!=0:raise RuntimeError('Java version check failed: '+version.stderr)
(out/'java-version.txt').write_text(version.stdout+version.stderr,encoding='utf-8')
def block(source,start):
    begin=source.index(start);brace=source.index('{',begin);end=brace+1;depth=1
    while depth:
        depth+=(source[end]=='{')-(source[end]=='}');end+=1
    return source[begin:end]
old=(tests/'old-layout-listener.java.inc').read_text(encoding='utf-8')
new=(root/rel).read_text(encoding='utf-8')
common='''import java.util.*;
class RevealChecks {
 static int checks;
 static void check(boolean b,String m){if(!b)throw new AssertionError(m);checks++;}
 static class Rect {int l,t,r,b;Rect(int l,int t,int r,int b){this.l=l;this.t=t;this.r=r;this.b=b;}}
 interface Layout {void run(Object v,int l,int t,int r,int b,int ol,int ot,int or,int ob);}
 static class FrameLayout {boolean requested;boolean isLayoutRequested(){return requested;}}
 static class ScrollView extends FrameLayout {
  boolean attached=true;Layout listener;ArrayDeque<Runnable> queue=new ArrayDeque<>();
  void addOnLayoutChangeListener(Layout l){listener=l;}
  boolean isAttachedToWindow(){return attached;}
  boolean post(Runnable r){queue.add(r);return true;}
  void layout(int height,int old){listener.run(this,0,0,100,height,0,0,100,old);}
  void flush(){int n=0;while(!queue.isEmpty()){if(++n>50)throw new AssertionError("unbounded self repost");queue.remove().run();}}
 }
 static class EditText {boolean focus=true,window=true,result=true;int w=30,h=20,calls;Runnable hook;
  boolean hasFocus(){return focus;}boolean hasWindowFocus(){return window;}
  int getWidth(){return w;}int getHeight(){return h;}
  boolean requestRectangleOnScreen(Rect r,boolean immediate){check(r.l==0&&r.t==0&&r.r==w&&r.b==h&&immediate,"whole measured local rect, immediate");calls++;if(hook!=null)hook.run();return result;}
 }
 final ScrollView scroll=new ScrollView();final FrameLayout viewport=new FrameLayout();EditText testEditor=new EditText();
'''
def create(source,legacy):
    if legacy:
        state='';start=source.index('        final long[] viewportGeneration={0};')
    else:
        state=block(source,'static final class RevealRequest')+'\nfinal RevealRequest trialReveal=new RevealRequest();\n'
        start=source.index('        scroll.addOnLayoutChangeListener')
    end=len(source) if legacy else source.index('        setContentView(viewport);',start)
    listener=source[start:end].replace('new android.graphics.Rect','new Rect')
    return common+state+'void install(){\n'+listener+'}\nRevealChecks(){install();}\n'
legacy=create(old,True)+'''public static void main(String[] x){RevealChecks a=new RevealChecks();a.scroll.layout(100,200);a.scroll.layout(100,100);a.scroll.flush();check(a.testEditor.calls==1,"shrink then same-height layout must retain reveal");}}'''
cases='''
 public static void main(String[] x){
  RevealChecks a=new RevealChecks();a.scroll.layout(100,200);a.scroll.flush();check(a.testEditor.calls==1,"single shrink");
  a=new RevealChecks();a.scroll.layout(100,200);a.scroll.layout(100,100);a.scroll.flush();check(a.testEditor.calls==1,"non-shrink preserves pending");
  a=new RevealChecks();a.scroll.layout(100,200);a.viewport.requested=true;a.scroll.flush();check(a.testEditor.calls==0&&a.scroll.queue.isEmpty(),"layout pending waits without repost");a.viewport.requested=false;a.scroll.layout(100,100);a.scroll.flush();check(a.testEditor.calls==1,"later real layout retries");
  a=new RevealChecks();a.scroll.layout(180,200);a.scroll.layout(140,180);a.scroll.layout(100,140);a.scroll.flush();check(a.testEditor.calls==1,"only newest shrink acts");
  a=new RevealChecks();a.scroll.layout(100,200);a.scroll.layout(200,100);a.scroll.flush();check(a.testEditor.calls==0,"growth cancels old request");a.scroll.layout(100,200);a.scroll.flush();check(a.testEditor.calls==1,"new shrink is fresh");
  a=new RevealChecks();a.trialReveal.ime(true,true);a.scroll.layout(100,200);a.trialReveal.ime(false,true);a.trialReveal.ime(true,true);a.scroll.flush();check(a.testEditor.calls==0,"hide-show invalidates old post");a.scroll.layout(100,100);a.scroll.flush();check(a.testEditor.calls==1,"new show arms new request");
  a=new RevealChecks();a.scroll.layout(100,200);a.testEditor.focus=false;a.trialReveal.cancel();a.testEditor.focus=true;a.scroll.flush();check(a.testEditor.calls==0,"focus loss-regain cannot revive stale post");a.scroll.layout(80,100);a.scroll.flush();check(a.testEditor.calls==1,"focus later shrink is fresh");
  a=new RevealChecks();a.scroll.layout(100,200);a.testEditor.window=false;a.trialReveal.cancel();a.testEditor.window=true;a.scroll.flush();check(a.testEditor.calls==0,"window focus loss-regain invalidates");
  a=new RevealChecks();a.scroll.layout(100,200);a.scroll.attached=false;a.scroll.flush();a.scroll.attached=true;a.scroll.layout(100,100);a.scroll.flush();check(a.testEditor.calls==0,"detach cancels rather than defers");
  a=new RevealChecks();EditText first=a.testEditor;a.scroll.layout(100,200);a.testEditor=new EditText();a.scroll.flush();check(first.calls==0&&a.testEditor.calls==0,"replacement not targeted by old request");
  a=new RevealChecks();a.scroll.layout(100,200);a.testEditor.window=false;a.scroll.flush();check(a.testEditor.calls==0,"current window unfocused cancels");
  a=new RevealChecks();a.testEditor.focus=false;a.scroll.layout(100,200);a.scroll.flush();check(a.testEditor.calls==0,"unfocused shrink not armed");
  a=new RevealChecks();a.testEditor.w=0;a.scroll.layout(100,200);a.scroll.flush();check(a.testEditor.calls==0&&a.scroll.queue.isEmpty(),"unmeasured rect remains dormant");a.testEditor.w=30;a.scroll.layout(100,100);a.scroll.flush();check(a.testEditor.calls==1,"measured on later layout");
  a=new RevealChecks();final RevealChecks reentrant=a;a.testEditor.hook=()->reentrant.scroll.layout(100,100);a.scroll.layout(100,200);a.scroll.flush();check(a.testEditor.calls==1,"clear before synchronous callback");
  a=new RevealChecks();a.testEditor.result=false;a.scroll.layout(100,200);a.scroll.flush();a.scroll.layout(100,100);a.scroll.flush();check(a.testEditor.calls==1&&a.scroll.queue.isEmpty(),"false does not cause retry loop");
  a=new RevealChecks();a.trialReveal.ime(true,true);a.scroll.layout(100,100);a.scroll.flush();check(a.testEditor.calls==1,"fresh visible-IME event survives same-height layout");a.trialReveal.ime(true,true);a.scroll.layout(100,100);a.scroll.flush();check(a.testEditor.calls==1,"unchanged visibility does not rearm");
  System.out.println("PASS "+checks+" actual-listener host scheduling assertions; Android lifecycle/UI NOT RUN");
 }
}
'''
def run(name,source):
    d=out/name;d.mkdir();p=d/'RevealChecks.java';p.write_text(source,encoding='utf-8')
    result=subprocess.run(['java','-XX:-UsePerfData','-Dfile.encoding=UTF-8',str(p)],text=True,encoding='utf-8',capture_output=True,timeout=60)
    (d/'result.txt').write_text(result.stdout+result.stderr,encoding='utf-8')
    return result
red=run('old-v2',legacy)
assert red.returncode!=0 and 'shrink then same-height layout must retain reveal' in red.stderr
green=run('new-v3',create(new,False)+cases);assert green.returncode==0,green.stderr
# These checks establish the real Activity hook wiring, not Android callback delivery.
assert 'test.setOnFocusChangeListener((v,focused)->{if(!focused)trialReveal.cancel();})' in new
assert 'onWindowFocusChanged(boolean hasFocus){super.onWindowFocusChanged(hasFocus);if(!hasFocus)trialReveal.cancel();}' in new
assert 'trialReveal.ime(insets.isVisible(android.view.WindowInsets.Type.ime()),testEditor!=null&&testEditor.hasFocus())' in new
assert new.index('trialReveal.complete(token)')<new.index('editor.requestRectangleOnScreen')
paths=[str(root/rel)]
syntax=subprocess.run(['java','-XX:-UsePerfData','-Dfile.encoding=UTF-8',str(tests/'ParseJava.java')]+paths,text=True,encoding='utf-8',capture_output=True,timeout=60)
(out/'syntax.txt').write_text(syntax.stdout+syntax.stderr,encoding='utf-8');assert syntax.returncode==0
receipt={'host_java_version':(version.stdout+version.stderr).strip(),'syntax_source_level':17,'old_v2_counterexample_reproduced':True,'new_result':green.stdout.strip(),'syntax':syntax.stdout.strip(),'scope':'actual extracted source state/listener with minimal host View doubles plus static hook wiring; no Android build or runtime','settings_sha256':hashlib.sha256((root/rel).read_bytes()).hexdigest()}
(out/'receipt.json').write_text(json.dumps(receipt,indent=2)+'\n',encoding='utf-8');print(json.dumps(receipt,indent=2))
