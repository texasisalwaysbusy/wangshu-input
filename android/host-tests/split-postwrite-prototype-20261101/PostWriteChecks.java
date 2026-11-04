package org.wangshu.ime;
/** Synthetic host; no real InputConnection or user content. */
public final class PostWriteChecks {
 static class Host implements SplitSelection.Editor {
  String[] text={"A|END","A|END"};int caret=2,which,reads,writes;boolean noOp,hideAfter,late,wrongTarget,slowAfter;String lateText;int lateCaret;
  public SplitSelection.Snapshot read(){reads++;if(slowAfter&&writes>0)try{Thread.sleep(120);}catch(InterruptedException e){Thread.currentThread().interrupt();throw new IllegalStateException(e);}return hideAfter&&writes>0?null:new SplitSelection.Snapshot(text[which],caret,caret);}
  public boolean replace(int start,int end,String value,int after){writes++;int target=wrongTarget?1:which;String updated=text[target].substring(0,start)+value+text[target].substring(end);if(late){lateText=updated;lateCaret=after;}else if(!noOp){text[target]=updated;which=target;caret=after;}return true;}
 }
 static int n;static void check(boolean x,String label){if(!x)throw new AssertionError(label);n++;System.out.println("PASS "+label);}
 public static void main(String[] args){
  Host h=new Host();h.noOp=true;SplitSelection s=new SplitSelection(h);boolean accepted=s.toggle(1,"X");System.out.println("OBSERVE ack_without_mutation accepted="+accepted+" selected="+s.selected(1)+" writes="+h.writes+" text="+h.text[0]);check(!accepted&&!s.selected(1)&&h.writes==1,"acknowledged no-op must not be recorded as confirmed insert");check(!s.toggle(1,"X")&&h.writes==1,"unconfirmed operation freezes session, no retry");
  h=new Host();h.hideAfter=true;s=new SplitSelection(h);check(!s.toggle(1,"X")&&h.text[0].equals("A|XEND")&&h.writes==1,"read failure after applied edit is uncertain, preserves actual change");check(!s.toggle(1,"X")&&h.writes==1,"uncertain read never sends rollback");
  h=new Host();h.late=true;s=new SplitSelection(h);check(!s.toggle(1,"X")&&h.writes==1,"delayed valid edit is conservatively unconfirmed");h.text[0]=h.lateText;h.caret=h.lateCaret;check(!s.toggle(1,"X")&&h.writes==1,"later applied edit not blindly reversed");
  h=new Host();s=new SplitSelection(h);check(s.toggle(1,"X")&&s.toggle(2,"Y")&&h.text[0].equals("A|XYEND"),"normal immediate insert and click order preserved");check(s.toggle(1,"X")&&h.text[0].equals("A|YEND")&&s.selected(2),"normal identity-specific withdrawal preserved");check(h.reads==7,"one extra synchronous snapshot per successful mutation, including setup/read counts");
  h=new Host();h.text[0]="q".repeat(32767);h.caret=32767;s=new SplitSelection(h);check(s.toggle(1,"x")&&h.text[0].length()==32768,"bounded snapshot at 32768 still confirms");check(s.toggle(1,"x")&&h.text[0].length()==32767,"bounded snapshot can withdraw one proven insertion");
  h=new Host();h.slowAfter=true;s=new SplitSelection(h);long start=System.nanoTime();check(s.toggle(1,"X"),"slow but valid observation eventually confirms");long ms=java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-start);check(ms>=100,"extra synchronous read blocks caller when fake editor is slow");System.out.println("OBSERVE fake_post_read_delay_ms="+ms+"; not real Android timing");
  h=new Host();h.wrongTarget=true;s=new SplitSelection(h);boolean same=s.toggle(1,"X");check(same&&h.text[0].equals("A|END")&&h.text[1].equals("A|XEND"),"fixture demonstrates unobservable wrong editor write");System.out.println("KNOWN LIMITATION: identical snapshots, reused connection and silent target change remain indistinguishable; wrong editor already changed");
  System.out.println("RESULT "+n+" synthetic checks; Android NOT_RUN");
 }
}
