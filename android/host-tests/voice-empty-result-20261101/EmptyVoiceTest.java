package org.wangshu.ime;

public class EmptyVoiceTest {
    static final class Clock implements VoiceHoldController.Scheduler {
        Runnable run;
        public VoiceHoldController.Handle after(long delay,Runnable r){run=r;return ()->{};}
    }
    static final class Host implements VoiceHoldController.Host {
        boolean held; int began,finished,cancelled,committed,discarded; String editor="PRIOR";
        public boolean beginVoice(VoiceHoldController.Token t){if(held)return false;held=true;began++;return true;}
        public void finishVoice(VoiceHoldController.Token t){finished++;}
        public void cancelVoice(VoiceHoldController.Token t,VoiceHoldController.CancelReason r){held=false;cancelled++;}
        public void tapSpace(long input){}
        public void commitPreview(VoiceHoldController.Token t,String text,VoiceHoldController.Completion c){held=false;committed++;editor+=text+(c.appendNewline()?"\n":"");}
        public void discardPreview(VoiceHoldController.Token t,VoiceHoldController.CancelReason r){discarded++;}
        public void update(VoiceHoldController.State state){}
        public void failed(VoiceHoldController.Token t,String m){}
    }
    static void check(boolean condition,String message){if(!condition)throw new AssertionError(message);}
    static void run(boolean beforeUp){
        Clock clock=new Clock();Host host=new Host();VoiceHoldController c=new VoiceHoldController(host,clock,500,8);c.bindInputSession(1);
        VoiceHoldController.Token t=c.down(7,150,180,0);clock.run.run();
        c.partial(t,"preview only");host.editor+="|EXTERNAL";
        if(beforeUp)c.result(t,"");
        c.up(t,7,150,180,700);
        if(!beforeUp)c.result(t,"");
        c.result(t,"late");c.up(t,7,150,180,701);
        check(!host.held,"empty center result must release HeldVoice resource");
        check(host.editor.equals("PRIOR|EXTERNAL"),"preview cleanup must preserve existing and externally modified text");
        check(host.committed==0,"empty center result must not write host editor");
        check(host.cancelled==1&&host.discarded==1,"cleanup exactly once");
        t=c.down(7,150,180,1000);clock.run.run();
        check(host.held&&host.began==2,"next hold starts after empty result");
        c.cancel(VoiceHoldController.CancelReason.ACTION_CANCEL);
        check(host.cancelled==2&&!host.held,"system cancellation releases next hold");
    }
    static void rightEmpty(){
        Clock clock=new Clock();Host host=new Host();VoiceHoldController c=new VoiceHoldController(host,clock,500,8);c.bindInputSession(1);
        VoiceHoldController.Token t=c.down(7,150,180,0);clock.run.run();c.targets(t,new VoiceHoldController.Region(0,0,100,100),new VoiceHoldController.Region(200,0,300,100));
        c.up(t,7,220,20,700);c.result(t,"");check(host.editor.equals("PRIOR\n")&&host.committed==1&&!host.held,"empty right result still inserts exactly one newline");
    }
    static void switchedSession(){
        Clock clock=new Clock();Host host=new Host();VoiceHoldController c=new VoiceHoldController(host,clock,500,8);c.bindInputSession(1);
        VoiceHoldController.Token t=c.down(7,150,180,0);clock.run.run();c.up(t,7,150,180,700);c.bindInputSession(2);c.result(t,"");c.result(t,"late");
        check(host.cancelled==1&&host.committed==0&&host.editor.equals("PRIOR")&&!host.held,"input switch cancels and stale result is inert");
    }
    public static void main(String[] args){run(true);run(false);rightEmpty();switchedSession();System.out.println("PASS 4 synthetic result/session cases; real Android/mic not tested");}
}
