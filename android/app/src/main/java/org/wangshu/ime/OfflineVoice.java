package org.wangshu.ime;

import android.content.Context;
import android.media.*;
import android.os.*;
import com.k2fsa.sherpa.onnx.*;
import java.io.File;


/** Audio stays in RAM and is processed by the CPU-only local model; no history. */
final class OfflineVoice {
    interface Listener {void state(String text);void result(String text);void failure(String text);}
    private static OnlineRecognizer shared;
    private static final java.util.concurrent.atomic.AtomicBoolean trimQueued=new java.util.concurrent.atomic.AtomicBoolean();
    static void trimMemory(){if(!trimQueued.compareAndSet(false,true))return;new Thread(()->{try{synchronized(OfflineVoice.class){if(shared!=null){shared.release();shared=null;}}}finally{trimQueued.set(false);}},"ime-voice-release").start();}
    private static synchronized OnlineRecognizer recognizer(Context c){
        if(shared==null){File f=VoiceModels.folder(c);OnlineRecognizerConfig config=new OnlineRecognizerConfig();OnlineModelConfig model=config.getModelConfig();model.setTokens(new File(f,"tokens.txt").getPath());model.setModelType("zipformer");model.setNumThreads(2);model.setProvider("cpu");model.getTransducer().setEncoder(new File(f,"encoder.onnx").getPath());model.getTransducer().setDecoder(new File(f,"decoder.onnx").getPath());model.getTransducer().setJoiner(new File(f,"joiner.onnx").getPath());config.setEnableEndpoint(false);synchronized(VoiceModels.MODEL_FILES_LOCK){if(!VoiceModels.available(c))throw new IllegalStateException("verified model not ready");shared=new OnlineRecognizer(null,config);}}return shared;
    }
    static String transcribe(Context c,float[] samples,int sampleRate){
        synchronized(OfflineVoice.class){OnlineRecognizer r=recognizer(c);OnlineStream s=r.createStream("");try{s.acceptWaveform(samples,sampleRate);s.acceptWaveform(new float[sampleRate/2],sampleRate);s.inputFinished();while(r.isReady(s))r.decode(s);return r.getResult(s).getText().trim();}finally{s.release();}}
    }
    interface Capture extends AutoCloseable {void start();int read(short[] samples);void stop();void close();}
    interface CaptureFactory {Capture create()throws Exception;}
    private static final class Microphone implements Capture {
        private final AudioRecord audio;
        @android.annotation.SuppressLint("MissingPermission") Microphone(){int min=AudioRecord.getMinBufferSize(16000,AudioFormat.CHANNEL_IN_MONO,AudioFormat.ENCODING_PCM_16BIT);if(min<=0)throw new IllegalStateException("audio format");audio=new AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION,16000,AudioFormat.CHANNEL_IN_MONO,AudioFormat.ENCODING_PCM_16BIT,Math.max(min*2,16000));if(audio.getState()!=AudioRecord.STATE_INITIALIZED){audio.release();throw new IllegalStateException("microphone unavailable");}}
        public void start(){audio.startRecording();}public int read(short[] samples){return audio.read(samples,0,samples.length);}public void stop(){try{audio.stop();}catch(IllegalStateException ignored){}}public void close(){audio.release();}
    }
    static final class Task {
        private final Context context;private final Listener listener;private final CaptureFactory factory;private final Handler ui=new Handler(Looper.getMainLooper());
        private volatile boolean cancelled=false,finish=false;private volatile Capture recording;private Thread thread;
        Task(Context c,Listener l){this(c,l,Microphone::new);}
        // Package-private source injection tests the production session with synthetic PCM, not a physical microphone.
        Task(Context c,Listener l,CaptureFactory f){context=c.getApplicationContext();listener=l;factory=f;}
        void start(){thread=new Thread(this::run,"ime-offline-voice");thread.start();}
        boolean awaitStopped(long milliseconds)throws InterruptedException{Thread t=thread;if(t==null)return true;t.join(milliseconds);return !t.isAlive();}
        void finish(){finish=true;Capture a=recording;if(a!=null)a.stop();}
        void cancel(){cancelled=true;finish();}
        private void state(String s){ui.post(()->{if(!cancelled)listener.state(s);});}
        private void result(String text){ui.post(()->{if(!cancelled)listener.result(text);});}
        private void run(){
            Capture audio=null;
            try{
                if(cancelled)return;if(finish){result("");return;}
                state("正在准备本地语音…");
                // Serialize model use, not typing. Destroy each stream before releasing its recognizer lock.
                synchronized(OfflineVoice.class){
                    if(cancelled)return;if(finish){result("");return;}
                    OnlineRecognizer r=recognizer(context);OnlineStream stream=null;
                    try{
                        if(cancelled)return;if(finish){result("");return;}
                        audio=factory.create();if(cancelled)return;if(finish){result("");return;}
                        recording=audio;stream=r.createStream("");audio.start();if(cancelled)return;if(finish){result("");return;}
                        state("本地聆听 · 点击“完成”后核对文字");short[] pcm=new short[1600];int count=0;long deadline=SystemClock.elapsedRealtime()+30000;
                        while(!cancelled&&!finish&&SystemClock.elapsedRealtime()<deadline){int n=audio.read(pcm);if(n<0){if(finish||cancelled)break;throw new IllegalStateException("audio read");}if(n==0){SystemClock.sleep(10);continue;}if(n>pcm.length)throw new IllegalStateException("audio buffer size");float[] values=new float[n];for(int i=0;i<n;i++)values[i]=pcm[i]/32768f;stream.acceptWaveform(values,16000);count+=n;while(!cancelled&&r.isReady(stream))r.decode(stream);}
                        if(cancelled)return;state("本地识别中…");stream.acceptWaveform(new float[8000],16000);stream.inputFinished();while(!cancelled&&r.isReady(stream))r.decode(stream);if(!cancelled)result(count<1600?"":r.getResult(stream).getText().trim());
                    }finally{if(stream!=null)stream.release();}
                }
            }catch(Exception|LinkageError|OutOfMemoryError e){ui.post(()->{if(!cancelled)listener.failure("本地语音未完成（"+e.getClass().getSimpleName()+"），未转云");});}
            finally{recording=null;if(audio!=null){audio.stop();audio.close();}}
        }
    }
}
