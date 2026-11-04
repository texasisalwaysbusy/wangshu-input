"""Test-only readiness policy and delivered-event journal, not Android UI/injection."""
import argparse, hashlib, json, subprocess
from pathlib import Path

ap=argparse.ArgumentParser(); ap.add_argument('--output',type=Path,required=True); args=ap.parse_args()
project=Path(__file__).resolve().parents[1]; repo=project.parents[1]
app=project/'android/app/src/androidTest/java/org/wangshu/ime'; out=args.output.resolve(); out.mkdir(parents=True,exist_ok=False)
pkg=out/'src/org/wangshu/ime';pkg.mkdir(parents=True)
source=(app/'MathImePrecondition.java').read_text()
def method(prefix):
    start=source.index(prefix); opening=source.index('{',start);depth=1;end=opening+1
    while depth:depth+=(source[end]=='{')-(source[end]=='}');end+=1
    return source[start:end]
policy='package org.wangshu.ime;final class Policy{'+method('static boolean recoveryAllowed(')+method('static boolean shortTapDelivered(')+'}\n'
(pkg/'Policy.java').write_text(policy)
(pkg/'VoiceTouchTrace.java').write_bytes((app/'VoiceTouchTrace.java').read_bytes())
start=source.index('Throwable downFailure=null;');end=source.index('            long deliveredEnd=',start)
tap=source[start:end]
(pkg/'TapProbe.java').write_text('''package org.wangshu.ime;final class TapProbe {
 static final class MotionEvent{static final int ACTION_DOWN=0,ACTION_UP=1;}
 static int downs,ups;static RuntimeException downError,upError;static boolean downAck=true,upAck=true;
 static boolean inject(Object auto,long down,int action,int x,int y){if(action==0){downs++;if(downError!=null)throw downError;return downAck;}ups++;if(upError!=null)throw upError;return upAck;}
 static boolean[] send(){Object auto=null;long down=100;int x=1,y=1;boolean[] acks={false,false};'''+tap+'''return acks;}
 static void reset(){downs=ups=0;downError=upError=null;downAck=upAck=true;}
}''')
test=r'''package org.wangshu.ime;
public final class ImePreconditionChecks {
 static int pass,fail;interface Check{void run();}static void need(boolean ok,String why){if(!ok)throw new AssertionError(why);}static void test(String label,Check check){try{check.run();pass++;System.out.println("PASS "+label);}catch(Exception|AssertionError e){fail++;System.out.println("FAIL "+label+" :: "+e.getMessage());}}
 static boolean eligible(int actions,int attempts,boolean ready,boolean known,boolean visible,boolean guard,boolean ime){return Policy.recoveryAllowed(actions,attempts,ready,known,visible,guard,ime);}
 static void event(VoiceTouchTrace t,int action,long stream,long time,long delivered,int pointers){t.record(action,stream,time,delivered,pointers,0,12,20,"IDLE",true);}
 static VoiceTouchTrace complete(){VoiceTouchTrace t=new VoiceTouchTrace("owned-fixture");event(t,0,100,100,110,1);event(t,1,100,120,130,1);return t;}
 public static void main(String[] ignored){
  test("safe absence before any native action permits one separate recovery",()->need(eligible(0,0,true,true,false,true,false),"valid recovery rejected"));
  test("any prior keyboard action prohibits recovery",()->need(!eligible(1,0,true,true,false,true,false),"post-action recovery allowed"));
  test("a previous recovery cannot be retried",()->need(!eligible(0,1,true,true,false,true,false),"second tap allowed"));
  test("unknown insets cannot be treated as keyboard absence",()->need(!eligible(0,0,true,false,false,true,false),"unknown treated as absence"));
  test("incomplete system guard or existing IME prohibits recovery",()->need(!eligible(0,0,true,true,false,false,false)&&!eligible(0,0,true,true,false,true,true),"unsafe windows allowed"));
  test("unfocused editor or already visible insets prohibits recovery",()->need(!eligible(0,0,false,true,false,true,false)&&!eligible(0,0,true,true,true,true,false),"unsafe editor allowed"));
  test("both command ACKs and actual complete short tap required",()->need(Policy.shortTapDelivered(true,true,100,120,110,130,500,false)&&!Policy.shortTapDelivered(false,true,100,120,110,130,500,false)&&!Policy.shortTapDelivered(true,true,100,-1,110,-1,500,false),"command completion substituted for delivered UP"));
  test("cancel and delayed delivery reject a recovery short tap",()->need(!Policy.shortTapDelivered(true,true,100,120,110,130,500,true)&&!Policy.shortTapDelivered(true,true,100,120,110,610,500,false),"cancel/delay silently accepted"));
  test("DOWN exception retained and one UP attempts pointer release",()->{TapProbe.reset();RuntimeException original=new IllegalStateException("down uncertain");TapProbe.downError=original;Throwable got=null;try{TapProbe.send();}catch(RuntimeException e){got=e;}need(got==original&&TapProbe.downs==1&&TapProbe.ups==1,"down error replaced or retried");});
  test("UP exception suppressed behind original DOWN error",()->{TapProbe.reset();RuntimeException down=new IllegalStateException("down"),up=new IllegalStateException("up");TapProbe.downError=down;TapProbe.upError=up;Throwable got=null;try{TapProbe.send();}catch(RuntimeException e){got=e;}need(got==down&&got.getSuppressed().length==1&&got.getSuppressed()[0]==up&&TapProbe.downs==1&&TapProbe.ups==1,"primary injection failure lost");});
  test("false DOWN ACK is retained with exactly one UP and no retry",()->{TapProbe.reset();TapProbe.downAck=false;boolean[] result=TapProbe.send();need(!result[0]&&result[1]&&TapProbe.downs==1&&TapProbe.ups==1,"false ACK retried or lost");});
  test("journal accepts a delivered matching short DOWN/UP",()->need(complete().completeShortTap(500),"valid event pair rejected"));
  test("DOWN-only event stream stays UNKNOWN and fails short tap",()->{VoiceTouchTrace t=new VoiceTouchTrace("outer");event(t,0,100,100,110,1);need(!t.completeShortTap(500)&&t.summary().contains("eventSpan=UNKNOWN"),"missing UP fabricated");});
  test("delivered CANCEL preserved and rejects pair",()->{VoiceTouchTrace t=complete();event(t,3,100,125,135,1);need(!t.completeShortTap(500)&&t.cancelled&&t.history.toString().contains("action=3"),"cancel lost");});
  test("UP from different physical stream cannot finish DOWN",()->{VoiceTouchTrace t=new VoiceTouchTrace("outer");event(t,0,100,100,110,1);event(t,1,99,120,130,1);need(!t.completeShortTap(500)&&t.mismatchedUp&&t.up==-1,"stale UP authorized pair");});
  test("multi pointer and late delivery remain failure",()->{VoiceTouchTrace t=complete();event(t,2,100,125,140,2);need(!t.completeShortTap(500),"multi pointer silently passed");VoiceTouchTrace late=new VoiceTouchTrace("outer");event(late,0,100,100,110,1);event(late,1,100,120,610,1);need(!late.completeShortTap(500),"delivery time ignored");});
  test("journal is bounded while preserving latest completion state",()->{VoiceTouchTrace t=new VoiceTouchTrace("outer");event(t,0,100,100,110,1);for(int i=0;i<300;i++)event(t,2,100,101,111,1);event(t,1,100,120,130,1);need(t.total==302&&t.history.toString().lines().count()==VoiceTouchTrace.LIMIT&&t.completeShortTap(500),"journal unbounded or state lost");});
  test("negative event/delivery spans never pass",()->{VoiceTouchTrace t=new VoiceTouchTrace("outer");event(t,0,100,100,110,1);event(t,1,100,99,109,1);need(!t.completeShortTap(500),"negative span passed");});
  test("a repeated DOWN or UP cannot be reported as one short tap",()->{VoiceTouchTrace t=complete();event(t,1,100,125,135,1);need(!t.completeShortTap(500),"duplicate UP accepted");VoiceTouchTrace repeated=new VoiceTouchTrace("outer");event(repeated,0,100,100,110,1);event(repeated,0,101,101,111,1);event(repeated,1,101,120,130,1);need(!repeated.completeShortTap(500),"duplicate DOWN accepted");});
  System.out.println("RESULT "+pass+" PASS / "+fail+" FAIL; actual test-only pure policy, exact DOWN/UP exception block and delivered-event journal, synthetic inputs only; no Android visibility/touch/microphone acceptance");if(fail>0)System.exit(1);
 }
}
'''
(pkg/'ImePreconditionChecks.java').write_text(test)
c=subprocess.run([str(repo/'.tools/jdk21/bin/javac'),'--release','17','-encoding','UTF-8','-d',str(out/'classes')]+[str(p) for p in (out/'src').rglob('*.java')],capture_output=True,text=True);(out/'javac.txt').write_text(c.stdout+c.stderr)
r=subprocess.run([str(repo/'.tools/jdk21/bin/java'),'-cp',str(out/'classes'),'org.wangshu.ime.ImePreconditionChecks'],capture_output=True,text=True) if c.returncode==0 else None
if r:(out/'results.txt').write_text(r.stdout+r.stderr)
receipt={'javac_exit':c.returncode,'test_exit':None if r is None else r.returncode,'source_sha256':{n:hashlib.sha256((app/n).read_bytes()).hexdigest() for n in ['MathImePrecondition.java','VoiceTouchTrace.java','StrictImeWindowQuery.java','MathAssociationUiChecks.java','VoiceHoldChecks.java']},'case_sha256':hashlib.sha256(test.encode()).hexdigest(),'scope':'pure test policy + exact injection exception block and event journal on explicit synthetic host inputs, no device/UI/recognition'}
(out/'receipt.json').write_text(json.dumps(receipt,indent=2)+'\n');print(json.dumps(receipt));print(c.stdout+c.stderr if r is None else r.stdout+r.stderr);raise SystemExit(c.returncode if r is None else r.returncode)
