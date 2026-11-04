"""Exact old/new test bounds + new capture; explicit fake Android coordinates only."""
import argparse,hashlib,json,subprocess
from pathlib import Path

ap=argparse.ArgumentParser();ap.add_argument('--mode',choices=['red','green'],required=True);ap.add_argument('--output',type=Path,required=True);a=ap.parse_args()
project=Path(__file__).resolve().parents[1];repo=project.parents[1];app=project/'android/app/src/androidTest/java/org/wangshu/ime';out=a.output.resolve();out.mkdir(parents=True,exist_ok=False)
def method(s,prefix):
    start=s.index(prefix);opening=s.index('{',start);depth=1;end=opening+1
    while depth:depth+=(s[end]=='{')-(s[end]=='}');end+=1
    return s[start:end]
old=project/'evidence/2026-10-28-voice-screen-coordinates/old-source/VoiceHoldChecks.java'
actual=app/'VoiceHoldChecks.java'; selected=old if a.mode=='red' else actual
fakes={
'android/graphics/Rect.java':'''package android.graphics;public class Rect{
 public int left,top,right,bottom;public Rect(){}public Rect(int l,int t,int r,int b){set(l,t,r,b);}public Rect(Rect r){set(r);}public void set(Rect r){set(r.left,r.top,r.right,r.bottom);}public void set(int l,int t,int r,int b){left=l;top=t;right=r;bottom=b;}public void offset(int x,int y){left+=x;right+=x;top+=y;bottom+=y;}public int width(){return right-left;}public int height(){return bottom-top;}public int centerX(){return (left+right)/2;}public int centerY(){return (top+bottom)/2;}public boolean contains(int x,int y){return x>=left&&x<right&&y>=top&&y<bottom;}public boolean intersect(Rect r){int l=Math.max(left,r.left),t=Math.max(top,r.top),rr=Math.min(right,r.right),b=Math.min(bottom,r.bottom);if(l>=rr||t>=b)return false;set(l,t,rr,b);return true;}public boolean equals(Object o){if(!(o instanceof Rect))return false;Rect r=(Rect)o;return left==r.left&&top==r.top&&right==r.right&&bottom==r.bottom;}public String toShortString(){return "["+left+","+top+"]["+right+","+bottom+"]";}public String toString(){return toShortString();}
}''',
'android/graphics/Matrix.java':'''package android.graphics;public class Matrix{public boolean identity=true;public boolean isIdentity(){return identity;}}''',
'android/view/ViewParent.java':'package android.view;public interface ViewParent{}',
'android/view/View.java':'''package android.view;import android.graphics.*;public class View implements ViewParent{
 public boolean attached=true,shown=true,globalShown=true,localShown=true;public int width=440,height=65,scrollX,scrollY;public int[] screen={20,470},window={20,255};public Rect global=new Rect(20,255,460,320),local=new Rect(0,0,440,65);public View parent;public Matrix matrix=new Matrix();
 public boolean isAttachedToWindow(){return attached;}public boolean isShown(){return shown;}public int getWidth(){return width;}public int getHeight(){return height;}public int getScrollX(){return scrollX;}public int getScrollY(){return scrollY;}public Matrix getMatrix(){return matrix;}public ViewParent getParent(){return parent;}public View getRootView(){View r=this;while(r.parent!=null)r=r.parent;return r;}public void getLocationOnScreen(int[] dst){dst[0]=screen[0];dst[1]=screen[1];}public void getLocationInWindow(int[] dst){dst[0]=window[0];dst[1]=window[1];}public boolean getGlobalVisibleRect(Rect out){out.set(global);return globalShown;}public boolean getLocalVisibleRect(Rect out){out.set(local);return localShown;}
}''',
'android/app/Instrumentation.java':'package android.app;public class Instrumentation{public void runOnMainSync(Runnable work){work.run();}}'
}
for name,source in fakes.items():p=out/'src'/name;p.parent.mkdir(parents=True,exist_ok=True);p.write_text(source)
pkg=out/'src/org/wangshu/ime';pkg.mkdir(parents=True,exist_ok=True)
(pkg/'TestViewScreenBounds.java').write_bytes((app/'TestViewScreenBounds.java').read_bytes())
prefix='package org.wangshu.ime;import android.app.Instrumentation;import android.graphics.Rect;import android.view.View;final class Probe{static StringBuilder log=new StringBuilder();'+method(selected.read_text(),'static Rect bounds(')+'}\n'
test=r'''public final class VoiceScreenCoordinateChecks {
 static int pass,fail,commands;static android.app.Instrumentation test=new android.app.Instrumentation();interface Case{void run()throws Exception;}static void need(boolean ok,String why){if(!ok)throw new AssertionError(why);}static void run(String name,Case c){commands=0;Probe.log.setLength(0);try{c.run();pass++;System.out.println("PASS "+name);}catch(Exception|AssertionError e){fail++;System.out.println("FAIL "+name+" :: "+e.getMessage());}}
 static View key(int dx,int dy){View v=new View(),root=new View();root.parent=null;root.width=480;root.height=340;root.window=new int[]{0,0};root.screen=new int[]{dx,dy};v.parent=root;v.window=new int[]{20,255};v.screen=new int[]{20+dx,255+dy};return v;}
 static Rect read(View v)throws Exception{return Probe.bounds(test,v);}static void expect(View v,int l,int t,int r,int b)throws Exception{Rect result=read(v);need(result.equals(new Rect(l,t,r,b)),"screen expected="+new Rect(l,t,r,b)+" got="+result);}
 static void refuses(View v)throws Exception{boolean refused=false;try{read(v);commands++;}catch(AssertionError expected){refused=true;}need(refused&&commands==0,"unsafe capture allowed a modeled OS command");}
 public static void main(String[] args){
  run("actual floating-window offset215 maps key to screen470..535",()->expect(key(0,215),20,470,460,535));
  run("corrected center502 lands in actual outer-surface key interval",()->{Rect r=read(key(0,215));int outerLocal=r.centerY()-235;need(r.centerY()==502&&outerLocal>=235&&outerLocal<300,"target still resolves to blank outer local52");});
  run("old raw287 is not inside corrected screen key",()->need(!read(key(0,215)).contains(240,287),"window-local hit incorrectly treated as screen hit"));
  run("both horizontal and vertical window translation are applied",()->expect(key(60,215),80,470,520,535));
  run("partly clipped key keeps only visible screen part",()->{View v=key(0,215);v.local=new Rect(0,20,440,65);v.global=new Rect(20,275,460,320);expect(v,20,490,460,535);});
  run("unshifted full-window fixture keeps original coordinates",()->expect(key(0,0),20,255,460,320));
  run("ancestor scrolling uses updated API locations once",()->{View v=key(0,215);v.window[1]-=100;v.screen[1]-=100;v.global.offset(0,-100);expect(v,20,370,460,435);});
  run("unsupported target internal scroll refuses command",()->{View v=key(0,215);v.scrollY=40;refuses(v);});
  run("non-identity own or ancestor matrix refuses command",()->{View v=key(0,215);v.matrix.identity=false;refuses(v);v=key(0,215);v.parent.matrix.identity=false;refuses(v);});
  run("independent local/global capture mismatch refuses command",()->{View v=key(0,215);v.global.offset(0,1);refuses(v);});
  run("changed root/window offset refuses command",()->{View v=key(0,215);v.parent.screen[1]++;refuses(v);});
  run("hidden detached and zero-size targets refuse command",()->{View v=key(0,215);v.globalShown=false;refuses(v);v=key(0,215);v.attached=false;refuses(v);v=key(0,215);v.width=0;refuses(v);});
  run("command metadata explicitly separates screen and root rectangles",()->{read(key(0,215));String log=Probe.log.toString();need(log.contains("globalRoot=[20,255][460,320]")&&log.contains("locationScreen=[20, 470]")&&log.contains("locationWindow=[20, 255]")&&log.contains("windowToScreen=(0,215)")&&log.contains("screenVisible=[20,470][460,535]"),"coordinate provenance omitted");});
  System.out.println("RESULT "+pass+" PASS / "+fail+" FAIL; exact test bounds/capture on explicit fake Android coordinates; no actual View API values, OS touch, microphone or product acceptance");if(fail>0)System.exit(1);
 }
}
'''
(pkg/'VoiceScreenCoordinateChecks.java').write_text(prefix+test)
c=subprocess.run([str(repo/'.tools/jdk21/bin/javac'),'--release','17','-encoding','UTF-8','-d',str(out/'classes')]+[str(p) for p in (out/'src').rglob('*.java')],capture_output=True,text=True);(out/'javac.txt').write_text(c.stdout+c.stderr)
r=subprocess.run([str(repo/'.tools/jdk21/bin/java'),'-cp',str(out/'classes'),'org.wangshu.ime.VoiceScreenCoordinateChecks'],capture_output=True,text=True) if c.returncode==0 else None
if r:(out/'results.txt').write_text(r.stdout+r.stderr)
receipt={'mode':a.mode,'javac_exit':c.returncode,'test_exit':None if r is None else r.returncode,'source_sha256':{str(selected.relative_to(project)):hashlib.sha256(selected.read_bytes()).hexdigest(),'TestViewScreenBounds.java':hashlib.sha256((app/'TestViewScreenBounds.java').read_bytes()).hexdigest()},'case_sha256':hashlib.sha256(test.encode()).hexdigest(),'scope':'actual test coordinate code, explicit fake View API endpoints, modeled command count only; no Android action'}
(out/'receipt.json').write_text(json.dumps(receipt,indent=2)+'\n');print(json.dumps(receipt));print(c.stdout+c.stderr if r is None else r.stdout+r.stderr);raise SystemExit(c.returncode if r is None else r.returncode)
