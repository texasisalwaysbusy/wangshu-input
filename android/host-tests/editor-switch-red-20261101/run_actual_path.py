"""Extract exact production adapter snippets into synthetic host. No user data or device APIs."""
from pathlib import Path
import sys,subprocess
root=Path(sys.argv[1]); out=Path(sys.argv[2]); src=root/'android/app/src/main/java/org/wangshu/ime'
ime=(src/'WangshuIme.java').read_text(); panel=(src/'PersonalPanel.java').read_text()
def line(prefix,text):
 hits=[x.strip() for x in text.splitlines() if x.strip().startswith(prefix)]
 if len(hits)!=1:raise RuntimeError((prefix,len(hits)))
 return hits[0]
owner=line('private static final class SplitOwner ',ime)
methods='\n'.join(line(p,ime) for p in ['public Object splitOwner()', 'public boolean splitCurrent(', 'public SplitSelection.Snapshot snapshotSplit(', 'public boolean replaceSplit('])
for lifecycle in ['onStartInput(EditorInfo info,boolean restarting)', 'onFinishInput()', 'onWindowHidden()', 'onDestroy()']:
 assert line('@Override public void '+lifecycle,ime).startswith('@Override public void '+lifecycle+'{inputSession++;')
panel_line=line('private void startSplit(',panel)
anonymous=panel_line.split('split=new SplitSelection(',1)[1].split(');host.redraw();',1)[0]
send=line('public boolean send(Object expected,String text)',ime)
assert 'session!=inputSession||generation!=emojiViewGeneration||selection!=emojiSelectionRevision' in send
code='''package org.wangshu.ime;
public class BoundActualChecks {
 int inputSession=1;long emojiViewGeneration=1,emojiSelectionRevision=1;int target=0,calls=0,commits=0;boolean changeOnSnapshot,changeOnSelection,changeOnConnection,changeAfterCommit,silentRetarget,failBatch,throwConnection,failEnd,throwEnd;String[] text={"ROOT","ROOTx"};int caret=4,start=4,end=4;InputConnection connection=new InputConnection();BoundActualChecks host=this;
 boolean privateInput(){return false;}boolean emojiPanelActive(){return true;}boolean isInputViewShown(){return true;}
 void cancelBackspace(){} void cancelHoldForOtherInput(){} boolean hasVoiceWork(){return false;}void cancelVoice(){}
 class Before {void invalidate(){}} Before beforeCaret=null;
 InputConnection getCurrentInputConnection(){if(throwConnection)throw new IllegalStateException();calls++;if(changeOnConnection&&calls==3){target=1;inputSession++;}return connection;}
 class InputConnection {boolean beginBatchEdit(){return !failBatch;} boolean endBatchEdit(){if(throwEnd)throw new IllegalStateException();return !failEnd;} boolean setSelection(int s,int e){start=s;end=e;caret=e;if(changeOnSelection){changeOnSelection=false;target=1;inputSession++;}return true;}boolean commitText(String value,int cursor){commits++;if(silentRetarget)target=1;text[target]=text[target].substring(0,start)+value+text[target].substring(end);caret=start=end=start+value.length();if(changeAfterCommit){target=1;inputSession++;}return true;}}
 SplitSelection.Snapshot snapshotFrom(InputConnection owner){SplitSelection.Snapshot s=new SplitSelection.Snapshot(text[target],caret,caret);if(changeOnSnapshot){changeOnSnapshot=false;target=1;inputSession++;}return s;}
 '''+owner+'\n'+methods+'''
 SplitSelection make(){final Object owner=host.splitOwner();return new SplitSelection('''+anonymous+''');}
 EmojiSelection.Host emoji(){final int session=inputSession;final long generation=emojiViewGeneration,selection=emojiSelectionRevision;final InputConnection editor=connection;
 return new EmojiSelection.Host(){public long session(){return inputSession;}public long generation(){return emojiViewGeneration;}public long selectionRevision(){return emojiSelectionRevision;}public Object connection(){return getCurrentInputConnection();}public boolean active(){return emojiPanelActive();}
 '''+send+''' };}
 static void check(boolean b,String m){if(!b)throw new AssertionError(m);}
 SplitSelection inserted(){SplitSelection s=make();check(s.toggle(1,"x")&&text[0].equals("ROOTx"),"initial insertion");return s;}
 void splitBefore(){SplitSelection s=inserted();target=1;caret=5;inputSession++;check(!s.toggle(1,"x")&&commits==1&&text[1].equals("ROOTx"),"same connection new session");}
 void splitDuringRead(){SplitSelection s=inserted();changeOnSnapshot=true;check(!s.toggle(1,"x")&&commits==1&&text[1].equals("ROOTx"),"switch during snapshot");}
 void splitDuringSelection(){SplitSelection s=inserted();changeOnSelection=true;check(!s.toggle(1,"x")&&commits==1&&text[1].equals("ROOTx"),"switch during selection");}
 void splitAfterCommit(){SplitSelection s=inserted();changeAfterCommit=true;check(!s.toggle(1,"x")&&commits==2&&text[0].equals("ROOT")&&text[1].equals("ROOTx"),"switch after already-applied commit reports uncertain");check(!s.toggle(1,"x")&&commits==2,"uncertain edit not retried");}
 void batchRejected(){SplitSelection s=make();failBatch=true;check(!s.toggle(1,"x")&&commits==0&&text[0].equals("ROOT"),"false beginBatch stops edit");}
 void connectionThrows(){throwConnection=true;SplitSelection s=make();check(!s.toggle(1,"x")&&commits==0,"owner creation failure closed");}
 void endFailure(boolean throwing){SplitSelection s=make();failEnd=!throwing;throwEnd=throwing;check(!s.toggle(1,"x")&&commits==1&&text[0].equals("ROOTx"),"endBatch failure after write reports uncertain");check(!s.toggle(1,"x")&&commits==1,"uncertain endBatch cannot retry");}
 void hiddenRetarget(){SplitSelection s=inserted();silentRetarget=true;boolean accepted=s.toggle(1,"x");check(accepted&&text[1].equals("ROOT"),"fixture demonstrates undetectable silent wrapper retarget");System.out.println("KNOWN LIMITATION: silent same-connection retarget during commit edited second synthetic editor");}
 void emojiSwitch(){EmojiSelection.Host h=emoji();EmojiSelection.Token t=EmojiSelection.capture(h);changeOnConnection=true;EmojiSelection.Outcome r=EmojiSelection.select(h,t,"x");check(r!=EmojiSelection.Outcome.SUBMITTED&&commits==0,"switch in connection getter");}
 public static void main(String[] args){new BoundActualChecks().splitBefore();new BoundActualChecks().splitDuringRead();new BoundActualChecks().splitDuringSelection();new BoundActualChecks().splitAfterCommit();new BoundActualChecks().batchRejected();new BoundActualChecks().connectionThrows();new BoundActualChecks().endFailure(false);new BoundActualChecks().endFailure(true);new BoundActualChecks().emojiSwitch();new BoundActualChecks().hiddenRetarget();System.out.println("PASS 9 extracted actual-adapter synthetic checks plus one deliberate known limitation; Android not run");}
}'''
java=out/'BoundActualChecks.java';java.write_text(code)
cmd=[str(root.parent.parent/'.tools/jdk21/bin/javac'),'-d',str(out),str(src/'SplitSelection.java'),str(src/'EmojiSelection.java'),str(java)]
subprocess.run(cmd,check=True)
subprocess.run([str(root.parent.parent/'.tools/jdk21/bin/java'),'-XX:-UsePerfData','-cp',str(out),'org.wangshu.ime.BoundActualChecks'],check=True)
