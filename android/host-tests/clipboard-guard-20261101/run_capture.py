from pathlib import Path
import subprocess,sys
root=Path(sys.argv[1]);tmp=Path(sys.argv[2]);src=root/'android/app/src/main/java/org/wangshu/ime/WangshuIme.java'
lines=[x.strip() for x in src.read_text().splitlines() if x.strip().startswith('private void captureClipboard(boolean userRequested)')]
assert len(lines)==1
helpers=[x.strip() for x in src.read_text().splitlines() if x.strip().startswith('private boolean clipboardCaptureCurrent(')]
assert len(helpers)==1
code='''package org.wangshu.ime;
class ClipboardStore {
 static boolean blocked=true;static int adds;static Runnable onAdd;
 static class UnconfirmedSaveException extends Exception {UnconfirmedSaveException(){super("SYNTHETIC PAUSED");}}
 static ClipboardStore get(CaptureChecks c)throws UnconfirmedSaveException{if(blocked)throw new UnconfirmedSaveException();return new ClipboardStore();}
 static String allowedText(String clip,boolean enabled,boolean privateEditor){return clip;}
 boolean add(String s,long t,int d){adds++;if(onAdd!=null)onAdd.run();return true;}
}
public class CaptureChecks {
 boolean clipboardListening=true,suppressClipboardUntilChange=false,clipboardPausedWarned=false,phrasesOpen=false,privateNow=false,enabled=true;Object clipboardRegistration=new Object(),clipboardListener=new Object();String lastClipboardText;int notices,inputSession=1;Runnable onRead;
 class ClipboardManager {int reads;String getPrimaryClip(){reads++;if(onRead!=null)onRead.run();return "SYNTHETIC";}}ClipboardManager clipboardManager=new ClipboardManager();
 class Prefs {boolean getBoolean(String k,boolean d){return enabled;}int getInt(String k,int d){return 7;}}
 Prefs getSharedPreferences(String s,int m){return new Prefs();}boolean isInputViewShown(){return true;}boolean privateInput(){return privateNow;}void toast(String s){notices++;}void rebuild(){}
 '''+helpers[0]+'\n'+lines[0].replace('android.content.ClipData clip','String clip')+'''
 static void check(boolean v,String m){if(!v)throw new AssertionError(m);}
 public static void main(String[] args){CaptureChecks c=new CaptureChecks();c.captureClipboard(false);c.captureClipboard(false);check(c.clipboardManager.reads==0&&ClipboardStore.adds==0,"locked capture must not access clipboard or retry save");check(c.notices==1,"paused notice once");ClipboardStore.blocked=false;c.captureClipboard(false);check(c.clipboardManager.reads==1&&ClipboardStore.adds==1,"normal capture");
  ClipboardStore.adds=0;CaptureChecks s=new CaptureChecks();s.onRead=()->s.inputSession++;s.captureClipboard(false);check(ClipboardStore.adds==0,"session switch during read must not save");
  ClipboardStore.adds=0;CaptureChecks p=new CaptureChecks();p.onRead=()->p.privateNow=true;p.captureClipboard(false);check(ClipboardStore.adds==0,"private field during read must not save");
  ClipboardStore.adds=0;CaptureChecks e=new CaptureChecks();e.onRead=()->e.enabled=false;e.captureClipboard(false);check(ClipboardStore.adds==0,"disabled during read must not save");
  ClipboardStore.adds=0;CaptureChecks a=new CaptureChecks();ClipboardStore.onAdd=()->a.inputSession++;a.captureClipboard(false);check(ClipboardStore.adds==1&&a.lastClipboardText==null,"session change during already-started add does not cache old-session text");
  System.out.println("PASS 7 extracted capture checks, fake manager and storage; Android NOT RUN");}
}'''
java=tmp/'CaptureChecks.java';java.write_text(code)
jdk=root.parent.parent/'.tools/jdk21/bin'
subprocess.run([str(jdk/'javac'),'-d',str(tmp),str(java)],check=True)
subprocess.run([str(jdk/'java'),'-XX:-UsePerfData','-cp',str(tmp),'org.wangshu.ime.CaptureChecks'],check=True)
