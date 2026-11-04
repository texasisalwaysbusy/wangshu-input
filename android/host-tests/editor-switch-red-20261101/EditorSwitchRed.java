package org.wangshu.ime;

/** Synthetic host switch/reentrancy; runs actual SplitSelection and EmojiSelection. */
public class EditorSwitchRed {
 static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
 static class Edit implements SplitSelection.Editor {
  String[] editors={"ROOT","ROOTx"};int focused=0,caret=4,session=1;boolean switchOnRead;
  public SplitSelection.Snapshot read(){
   SplitSelection.Snapshot result=new SplitSelection.Snapshot(editors[focused],caret,caret);
   if(switchOnRead){switchOnRead=false;focused=1;caret=5;session++;}
   return result;
  }
  public boolean replace(int s,int e,String value,int after){String v=editors[focused];editors[focused]=v.substring(0,s)+value+v.substring(e);caret=after;return true;}
 }
 static void split(boolean async){
  Edit h=new Edit();SplitSelection s=new SplitSelection(h);check(s.toggle(3,"x"),"initial insertion");
  if(async){h.switchOnRead=true;}else{h.focused=1;h.caret=5;h.session++;}
  boolean result=s.toggle(3,"x");
  check(!result&&h.editors[1].equals("ROOTx"),"cross-editor deletion must be rejected; B="+h.editors[1]);
 }
 static class EmojiHost implements EmojiSelection.Host {
  int session=1,connectionCalls,writes;boolean switchOnAdapterRead,recheck,retargetDuringCommit;final Object reused=new Object();int dest=0;
  public long session(){return session;}public long generation(){return 1;}public long selectionRevision(){return 1;}
  public Object connection(){connectionCalls++;if(switchOnAdapterRead&&connectionCalls==3){session++;dest=1;}return reused;}
  public boolean active(){return true;}
  public boolean send(Object expected,String text){int capturedSession=1;Object captured=reused;
   if(capturedSession!=session||expected!=captured||captured!=connection()||!active())return false;
   if(recheck&&capturedSession!=session)return false;
   if(retargetDuringCommit)dest=1; // Host-internal retarget; no observable session/connection change.
   writes++;return true;
  }
 }
 static void emoji(boolean recheck,boolean hidden){EmojiHost h=new EmojiHost();EmojiSelection.Token t=EmojiSelection.capture(h);h.switchOnAdapterRead=!hidden;h.recheck=recheck;h.retargetDuringCommit=hidden;
  EmojiSelection.Outcome outcome=EmojiSelection.select(h,t,"x");
  check(h.writes==0&&outcome!=EmojiSelection.Outcome.SUBMITTED,"reentrant switch in adapter must not submit to new editor; dest="+h.dest+" outcome="+outcome);
 }
 public static void main(String[] args){switch(args[0]){case "split":split(false);break;case "split-async":split(true);break;case "emoji":emoji(false,false);break;case "emoji-recheck":emoji(true,false);System.out.println("PASS synthetic final session recheck before send");break;case "emoji-hidden":emoji(true,true);break;default:throw new IllegalArgumentException();}}
}
