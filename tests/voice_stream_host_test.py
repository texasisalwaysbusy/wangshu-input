#!/usr/bin/env python3
"""Compile actual voice Task with deterministic Android/decoder doubles. No audio or model I/O."""
from pathlib import Path
import hashlib
import json
import subprocess
import sys

root = Path(__file__).resolve().parents[1]
out = Path(sys.argv[1]).resolve()
if not out.is_relative_to(root / 'reports') or out.exists():
    raise SystemExit('Expected a new reports/ directory')
out.mkdir(parents=True)
sources = {
    'android/content/Context.java': 'package android.content; public class Context {public Context getApplicationContext(){return this;}}',
    'android/annotation/SuppressLint.java': 'package android.annotation; public @interface SuppressLint {String[] value();}',
    'android/os/Looper.java': 'package android.os; public class Looper {public static Looper getMainLooper(){return new Looper();}}',
    'android/os/Handler.java': '''package android.os; import java.util.concurrent.ConcurrentLinkedQueue;
public class Handler { public static final ConcurrentLinkedQueue<Runnable> queue=new ConcurrentLinkedQueue<>();
public Handler(Looper loop){} public boolean post(Runnable action){queue.add(action);return true;}
public static void drain(){Runnable action;while((action=queue.poll())!=null)action.run();}}''',
    'android/os/SystemClock.java': '''package android.os; public class SystemClock {
public static long elapsedRealtime(){return System.nanoTime()/1000000;}
public static void sleep(long millis){try{Thread.sleep(millis);}catch(InterruptedException e){Thread.currentThread().interrupt();}}}''',
    'android/media/AudioFormat.java': 'package android.media; public class AudioFormat {public static final int CHANNEL_IN_MONO=1,ENCODING_PCM_16BIT=2;}',
    'android/media/MediaRecorder.java': 'package android.media; public class MediaRecorder {public static class AudioSource {public static final int VOICE_RECOGNITION=1;}}',
    'android/media/AudioRecord.java': '''package android.media; public class AudioRecord {
public static final int STATE_INITIALIZED=1;public AudioRecord(int a,int b,int c,int d,int e){throw new AssertionError("No microphone permitted");}
public static int getMinBufferSize(int a,int b,int c){return 16000;}public int getState(){return 1;}
public void startRecording(){}public int read(short[] a,int b,int c){return -1;}public void stop(){}public void release(){}}''',
    'com/k2fsa/sherpa/onnx/OnlineModelConfig.java': '''package com.k2fsa.sherpa.onnx;
public class OnlineModelConfig {public void setTokens(String s){}public void setModelType(String s){}
public void setNumThreads(int n){}public void setProvider(String s){}public OnlineModelConfig getTransducer(){return this;}
public void setEncoder(String s){}public void setDecoder(String s){}public void setJoiner(String s){}}''',
    'com/k2fsa/sherpa/onnx/OnlineRecognizerConfig.java': '''package com.k2fsa.sherpa.onnx;
public class OnlineRecognizerConfig {
    String decoding;
    int paths;
    boolean endpoint=true;
    public OnlineModelConfig getModelConfig(){return new OnlineModelConfig();}
    public void setEnableEndpoint(boolean enabled){endpoint=enabled;}
    public void setDecodingMethod(String method){decoding=method;}
    public void setMaxActivePaths(int limit){paths=limit;}
}''',
    'com/k2fsa/sherpa/onnx/OnlineStream.java': '''package com.k2fsa.sherpa.onnx;
public class OnlineStream {int pending;public void acceptWaveform(float[] samples,int rate){pending++;}
public void inputFinished(){}public void release(){}}''',
    'com/k2fsa/sherpa/onnx/OnlineRecognizer.java': '''package com.k2fsa.sherpa.onnx;
public class OnlineRecognizer {
public static Runnable beforeCreateStream;
public OnlineRecognizer(Object assets,OnlineRecognizerConfig config){
    if(!"modified_beam_search".equals(config.decoding)||config.paths!=4||config.endpoint)
        throw new AssertionError("Offline decoder candidate configuration");
}
public OnlineStream createStream(String s){if(beforeCreateStream!=null)beforeCreateStream.run();return new OnlineStream();}
public boolean isReady(OnlineStream stream){return stream.pending>0;}
public void decode(OnlineStream stream){stream.pending--;}public Result getResult(OnlineStream stream){return new Result();}
public void release(){}public static class Result {public String getText(){return "synthetic hypothesis";}}}''',
    'org/wangshu/ime/VoiceModels.java': '''package org.wangshu.ime; import android.content.Context; import java.io.File;
class VoiceModels {static final Object MODEL_FILES_LOCK=new Object();static boolean available(Context c){return true;}
static File folder(Context c){return new File("unused-model-double");}}''',
    'org/wangshu/ime/VoiceStreamChecks.java': '''package org.wangshu.ime;
import android.content.Context;import android.os.Handler;import android.os.SystemClock;
import com.k2fsa.sherpa.onnx.OnlineRecognizer;
public class VoiceStreamChecks {
 static void require(boolean value,String message){if(!value)throw new AssertionError(message);}
 static class Source implements OfflineVoice.Capture {
  volatile boolean closed,stopped;int starts,reads,stops;boolean fail;
  public void start(){starts++;} public int read(short[] samples){reads++;if(fail)return -3;if(reads==1)return samples.length;SystemClock.sleep(2);return 0;}
  public void stop(){stops++;stopped=true;}public void close(){closed=true;}
 }
 static class Events implements OfflineVoice.Listener {
  Source source;int partials,results,failures;
  public void state(String s){}public void partial(String text){partials++;require(!source.closed,"Preview before cleanup");}
  public void result(String text){results++;require(source.closed&&(source.stopped||source.starts==0),"Final result follows recorder cleanup");
   if(source.starts==0)require(text.isEmpty(),"Early finish returns no text");}
  public void failure(String text){failures++;require(source.closed,"Failure follows recorder cleanup");}
 }
 static void preview(Events events)throws Exception {
  long deadline=SystemClock.elapsedRealtime()+3000;
  while(events.partials==0&&SystemClock.elapsedRealtime()<deadline){Handler.drain();Thread.sleep(5);}
  require(events.partials==1,"Actual Task publishes a partial while capture is active");
 }
 static void endDuringStreamCreation(Context context,boolean cancel)throws Exception {
  Source source=new Source();Events events=new Events();events.source=source;
  OfflineVoice.Task task=new OfflineVoice.Task(context,events,()->source);
  OnlineRecognizer.beforeCreateStream=cancel?task::cancel:task::finish;
  try {
   task.start();require(task.awaitStopped(3000),"Early end stopped worker");Handler.drain();
   require(source.starts==0&&source.reads==0,"Ending during decoder setup cannot start capture");
   require(source.closed&&source.stops==0&&events.partials==0&&events.failures==0,"Unstarted capture only closes");
   require(events.results==(cancel?0:1),"Only early finish delivers an empty result");
  } finally {OnlineRecognizer.beforeCreateStream=null;}
 }
 public static void main(String[] args)throws Exception {
  Context context=new Context();endDuringStreamCreation(context,true);endDuringStreamCreation(context,false);
  Source source=new Source();Events events=new Events();events.source=source;
  OfflineVoice.Task task=new OfflineVoice.Task(context,events,()->source);task.start();preview(events);
  require(events.results==0,"Partial must not finalize");task.finish();require(task.awaitStopped(3000),"Finish stopped worker");Handler.drain();
  require(events.results==1&&events.failures==0&&source.stops==1,"Exactly one stop and final after explicit finish");
  Source cancelled=new Source();Events ce=new Events();ce.source=cancelled;
  OfflineVoice.Task cancellation=new OfflineVoice.Task(context,ce,()->cancelled);cancellation.start();preview(ce);
  cancellation.cancel();require(cancellation.awaitStopped(3000),"Cancel stopped worker");Handler.drain();
  require(cancelled.closed&&cancelled.stops==1&&ce.results==0&&ce.failures==0,"Cancel releases capture once and suppresses terminal result");
  Source broken=new Source();broken.fail=true;Events be=new Events();be.source=broken;
  OfflineVoice.Task bad=new OfflineVoice.Task(context,be,()->broken);bad.start();require(bad.awaitStopped(3000),"Read failure stopped worker");Handler.drain();
  require(be.failures==1&&be.results==0&&broken.closed,"Read failure has one failure and cleanup");
  System.out.println("VoiceStreamChecks PASS: streaming, finish, cancellation, early end during stream setup, read failure; synthetic decoder/capture only");
 }
}''',
}
java_sources = []
for name, text in sources.items():
    target = out / name
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_text(text, encoding='utf-8')
    java_sources.append(str(target))
production = root / 'android/app/src/main/java/org/wangshu/ime'
inputs = [production / 'OfflineVoice.java', production / 'VoicePreviewDelivery.java',
          root / 'tests/jvm/VoicePreviewDeliveryChecks.java']
classes = out / 'classes'
classes.mkdir()
subprocess.run(['java', '-XX:-UsePerfData', 'com.sun.tools.javac.Main', '-encoding', 'UTF-8',
                '-d', str(classes), *java_sources, *map(str, inputs)], check=True)
for name in ['VoicePreviewDeliveryChecks', 'VoiceStreamChecks']:
    subprocess.run(['java', '-XX:-UsePerfData', '-cp', str(classes), 'org.wangshu.ime.' + name], check=True)
(out / 'RESULT.json').write_text(json.dumps(dict(
    status='PASS', scope='Production Task with synthetic Android/decoder/capture; not voice quality or phone acceptance',
    sources={str(path.relative_to(root)): hashlib.sha256(path.read_bytes()).hexdigest() for path in inputs}
), indent=2), encoding='utf-8')
