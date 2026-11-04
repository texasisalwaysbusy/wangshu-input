package org.wangshu.ime;
import android.content.Context;
import android.os.SystemClock;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** Actual production recorder session with fake PCM capture; no microphone permission or hardware. */
final class VoiceSessionChecks {
    static final class Events implements OfflineVoice.Listener {
        final CountDownLatch complete=new CountDownLatch(1);final AtomicInteger callbacks=new AtomicInteger();volatile String text=null,error=null;
        public void state(String s){}public void result(String s){text=s;callbacks.incrementAndGet();complete.countDown();}public void failure(String s){error=s;callbacks.incrementAndGet();complete.countDown();}
    }
    static final class Source implements OfflineVoice.Capture {
        volatile boolean started=false,closed=false;volatile int reads=0;boolean fail=false;final CountDownLatch read=new CountDownLatch(1);
        public void start(){started=true;}public int read(short[] data){reads++;read.countDown();if(fail)return -3;if(reads==1){java.util.Arrays.fill(data,(short)0);return data.length;}return 0;}public void stop(){}public void close(){closed=true;}
    }
    private static void require(boolean value,String label){if(!value)throw new AssertionError(label);}
    static String run(Context context)throws Exception{
        StringBuilder log=new StringBuilder();
        Events e=new Events();AtomicInteger created=new AtomicInteger();OfflineVoice.Task task=new OfflineVoice.Task(context,e,()->{created.incrementAndGet();return new Source();});task.cancel();task.start();require(task.awaitStopped(10000),"cancelled-before-start worker exits");SystemClock.sleep(200);require(created.get()==0&&e.callbacks.get()==0,"cancelled-before-start must neither create capture nor return text");log.append("PASS cancellation before model readiness never opens capture or emits a result\n");
        Source source=new Source();Events events=new Events();CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);OfflineVoice.Task delayed=new OfflineVoice.Task(context,events,()->{entered.countDown();if(!release.await(10,TimeUnit.SECONDS))throw new IllegalStateException("fixture timeout");return source;});delayed.start();require(entered.await(10,TimeUnit.SECONDS),"capture factory reached");delayed.cancel();release.countDown();require(delayed.awaitStopped(10000),"cancelled factory exits");require(source.closed&&!source.started&&events.callbacks.get()==0,"cancel during capture preparation must close without starting");log.append("PASS cancellation during capture preparation closes resources without starting capture\n");
        Source finishedSource=new Source();Events finishedEvents=new Events();CountDownLatch start=new CountDownLatch(1),go=new CountDownLatch(1);OfflineVoice.Task finishEarly=new OfflineVoice.Task(context,finishedEvents,()->{start.countDown();go.await(10,TimeUnit.SECONDS);return finishedSource;});finishEarly.start();require(start.await(10,TimeUnit.SECONDS),"finish factory reached");finishEarly.finish();go.countDown();require(finishEarly.awaitStopped(10000)&&finishedEvents.complete.await(10,TimeUnit.SECONDS),"finish-before-record callback");require(!finishedSource.started&&finishedSource.closed&&"".equals(finishedEvents.text),"finish while preparing must return empty without microphone start");log.append("PASS finish while preparing returns empty review text without starting capture\n");
        Source broken=new Source();broken.fail=true;Events failed=new Events();OfflineVoice.Task failure=new OfflineVoice.Task(context,failed,()->broken);failure.start();require(failure.awaitStopped(10000)&&failed.complete.await(10,TimeUnit.SECONDS),"read failure exits");require(broken.started&&broken.closed&&failed.error!=null&&failed.text==null,"read failure must close capture and report failure, not text");log.append("PASS capture failure closes resources and never emits text or invokes cloud fallback\n");
        Source silence=new Source();Events silent=new Events();OfflineVoice.Task normal=new OfflineVoice.Task(context,silent,()->silence);normal.start();require(silence.read.await(10,TimeUnit.SECONDS),"silence read reached");normal.finish();OfflineVoice.trimMemory();require(normal.awaitStopped(60000)&&silent.complete.await(10,TimeUnit.SECONDS),"silence finalization exits");require(silence.closed&&"".equals(silent.text)&&silent.error==null,"silence and concurrent trim must finish empty without release-order crash");log.append("PASS silent recording, finish, and concurrent memory trim preserve stream/recognizer lifetime\n");
        log.append("Scope: synthetic capture adapter -> production task -> actual local model; real microphone NOT RUN\n");return log.toString();
    }
}
