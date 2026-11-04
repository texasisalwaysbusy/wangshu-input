from pathlib import Path
import sys,subprocess,json,hashlib
r=Path(__file__).resolve().parents[1]; src=Path(sys.argv[1]).resolve() if len(sys.argv)>1 else r/'source'; out=Path(sys.argv[2]).resolve(); out.mkdir(parents=True,exist_ok=False)
original=(Path(__file__).resolve().parent/'LocalControllerChecks.java').read_text()
s=original.replace('public class LocalControllerChecks {','public class PrivacyReentryChecks {').replace('static int checks;','static int checks,failures;').replace('if(!value)throw new AssertionError(label);checks++;System.out.println("PASS "+label);','checks++;if(!value)failures++;System.out.println((value?"PASS ":"FAIL ")+label);')
s=s.replace('boolean allowed=true,','boolean privatizeOnInsert,privatizeOnRetract,privatizeOnHide,clearOnThirdConnection;int connectionCalls; boolean allowed=true,')
s=s.replace('public InputConnection connection(){return owner;}','public InputConnection connection(){connectionCalls++;if(clearOnThirdConnection&&connectionCalls==8){controller.clear();allowed=false;session++;}return owner;}')
s=s.replace('public void show(PersonalPhraseAssociation.Display value){visible=value;}', 'public void show(PersonalPhraseAssociation.Display value){visible=value;if(value==null&&privatizeOnHide){privatizeOnHide=false;allowed=false;session++;}}')
s=s.replace('if(swapOnInsert){','if(privatizeOnInsert){allowed=false;}if(swapOnInsert){')
s=s.replace('deletes++;if(refuse)','deletes++;if(privatizeOnRetract)allowed=false;if(refuse)')
start=s.index(' public static void main(String[]args)')
s=s[:start]+''' public static void main(String[]args)throws Exception{
 try(F f=new F()){f.request();f.controller.accept(f.visible);int before=f.readCalls;f.allowed=false;f.controller.undo();check(f.readCalls==before,"undo does not read a now-private editor (reads delta="+(f.readCalls-before)+")");check(f.deletes==0,"private undo does not delete");}
 try(F f=new F()){f.request();f.controller.accept(f.visible);int before=f.readCalls;f.owner=new InputConnection(){};f.session++;f.controller.undo();check(f.readCalls==before,"undo does not read old editor after session/owner replacement (reads delta="+(f.readCalls-before)+")");}
 try(F f=new F()){f.request();int before=f.readCalls;f.privatizeOnInsert=true;f.controller.accept(f.visible);check(f.readCalls-before==2,"post-insert privacy transition stops readback (reads delta="+(f.readCalls-before)+")");check(!f.controller.hasUndo(),"private insert readback has no undo token");}
 try(F f=new F()){f.request();f.controller.accept(f.visible);int before=f.readCalls;f.privatizeOnRetract=true;f.controller.undo();check(f.readCalls-before==1,"post-delete privacy transition stops readback (reads delta="+(f.readCalls-before)+")");}
 try(F f=new F()){f.request();int before=f.readCalls;f.privatizeOnHide=true;f.controller.accept(f.visible);check(f.readCalls-before==1&&f.inserts==0,"hide callback privacy/session transition stops second snapshot read (reads delta="+(f.readCalls-before)+")");}
 try(F f=new F()){f.request();f.connectionCalls=0;f.clearOnThirdConnection=true;f.controller.accept(f.visible);check(f.inserts==0,"final owner lookup reentry cannot insert into invalidated private scope (inserts="+f.inserts+")");}
 System.out.println("TOTAL "+checks+" checks; failures="+failures+"; actual production controller, synthetic host only");if(failures>0)System.exit(1);}
}
'''
p=out/'PrivacyReentryChecks.java';p.write_text(s)
java=src/'android/app/src/main/java/org/wangshu/ime';fake=Path(__file__).resolve().parent/'fixtures';ime=(java/'WangshuIme.java').read_text();a=ime.index('static boolean sameVoiceSnapshot(');b=ime.index('}',a)+1;stub=out/'WangshuIme.java';stub.write_text('package org.wangshu.ime; final class WangshuIme {'+ime[a:b]+'}');sources=[java/n for n in ['PersonalPhraseAssociation.java','PersonalPhraseSuggestions.java','SplitSelection.java','MixedComposition.java']]+list(fake.rglob('*.java'))+[stub,p]
for label,cmd in [('compile',['java','-m','jdk.compiler/com.sun.tools.javac.Main','-d',str(out/'classes'),*map(str,sources)]),('run',['java','-cp',str(out/'classes'),'org.wangshu.ime.PrivacyReentryChecks'])]:
 x=subprocess.run(cmd,capture_output=True,text=True);(out/(label+'.txt')).write_text(x.stdout+x.stderr);print(x.stdout+x.stderr,end='');
 if label=='compile' and x.returncode:raise SystemExit(x.returncode)
(out/'receipt.json').write_text(json.dumps({'sources':{str(p):hashlib.sha256(p.read_bytes()).hexdigest() for p in sources},'test_exit':x.returncode,'scope':'Production controller, generated fake Android scheduler and editor. No Android runtime.'},indent=2))
raise SystemExit(x.returncode)
