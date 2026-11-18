package org.wangshu.ime;

import android.content.Context;
import android.media.*;
import android.os.*;
import com.k2fsa.sherpa.onnx.*;
import java.io.File;


/** Audio stays in RAM and is processed by the CPU-only local model; no history. */
final class OfflineVoice {
    interface Listener {
        void state(String text);
        default void partial(String text) { }
        void result(String text);
        void failure(String text);
    }
    private static OnlineRecognizer shared;
    private static final java.util.concurrent.atomic.AtomicBoolean trimQueued=new java.util.concurrent.atomic.AtomicBoolean();
    static void trimMemory(){if(!trimQueued.compareAndSet(false,true))return;new Thread(()->{try{synchronized(OfflineVoice.class){if(shared!=null){shared.release();shared=null;}}}finally{trimQueued.set(false);}},"ime-voice-release").start();}
    private static synchronized OnlineRecognizer recognizer(Context c){
        if(shared==null){
            File folder=VoiceModels.folder(c);
            OnlineRecognizerConfig config=new OnlineRecognizerConfig();
            OnlineModelConfig model=config.getModelConfig();
            model.setTokens(new File(folder,"tokens.txt").getPath());
            model.setModelType("zipformer");
            model.setNumThreads(2);
            model.setProvider("cpu");
            model.getTransducer().setEncoder(new File(folder,"encoder.onnx").getPath());
            model.getTransducer().setDecoder(new File(folder,"decoder.onnx").getPath());
            model.getTransducer().setJoiner(new File(folder,"joiner.onnx").getPath());
            config.setEnableEndpoint(false);
            config.setDecodingMethod("modified_beam_search");
            config.setMaxActivePaths(4);
            synchronized(VoiceModels.MODEL_FILES_LOCK){
                if(!VoiceModels.available(c))throw new IllegalStateException("verified model not ready");
                shared=new OnlineRecognizer(null,config);
            }
        }
        return shared;
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
        private volatile boolean cancelled=false,finish=false;private Thread thread;
        private final Object captureLock = new Object();
        private Capture recording;
        private boolean captureStarted;
        private final VoicePreviewDelivery preview;
        Task(Context c,Listener l){this(c,l,Microphone::new);}
        // Package-private source injection tests the production session with synthetic PCM, not a physical microphone.
        Task(Context c,Listener l,CaptureFactory f){
            context=c.getApplicationContext();listener=l;factory=f;
            preview=new VoicePreviewDelivery(action->ui.post(action),text->{
                if(!cancelled&&!finish)listener.partial(text);
            });
        }
        void start(){thread=new Thread(this::run,"ime-offline-voice");thread.start();}
        boolean awaitStopped(long milliseconds)throws InterruptedException{Thread t=thread;if(t==null)return true;t.join(milliseconds);return !t.isAlive();}
        void finish(){
            finish=true;
            preview.close();
            synchronized(captureLock){stopCaptureLocked();}
        }
        void cancel(){cancelled=true;finish();}
        private boolean startCapture(){
            synchronized(captureLock){
                if(cancelled||finish)return false;
                // A failed start can still leave native state requiring cleanup.
                captureStarted=true;
                recording.start();
                return true;
            }
        }
        private void stopCaptureLocked(){
            if(captureStarted){
                recording.stop();
                captureStarted=false;
            }
        }
        private void releaseCapture(){
            synchronized(captureLock){
                if(recording==null)return;
                try{stopCaptureLocked();}
                finally{
                    Capture audio=recording;
                    recording=null;
                    captureStarted=false;
                    audio.close();
                }
            }
        }
        private void state(String s){ui.post(()->{if(!cancelled)listener.state(s);});}
        private void result(String text){ui.post(()->{if(!cancelled)listener.result(text);});}
        private void run(){
            String transcript=null;
            String failure=null;
            try{
                transcript=recognize();
            }catch(Exception|LinkageError|OutOfMemoryError e){
                failure="本地语音未完成（"+e.getClass().getSimpleName()+"），未转云";
            }finally{
                preview.close();
                try{releaseCapture();}
                catch(RuntimeException cleanup){failure="本地录音释放未确认，请核对系统麦克风指示";}
            }
            // A final UI callback is enqueued only after capture cleanup completes.
            if(failure!=null){String message=failure;ui.post(()->{if(!cancelled)listener.failure(message);});}
            else if(transcript!=null)result(transcript);
        }

        private String recognize() throws Exception {
            if(cancelled||finish)return "";
            state("正在准备本地语音…");
            synchronized(OfflineVoice.class){
                if(cancelled||finish)return "";
                OnlineRecognizer recognizer=recognizer(context);
                if(cancelled||finish)return "";
                Capture audio=factory.create();
                synchronized(captureLock){recording=audio;}
                if(cancelled||finish)return "";
                OnlineStream stream=recognizer.createStream("");
                try{
                    if(!startCapture())return "";
                    if(cancelled||finish)return "";
                    state("本地聆听 · 点击“完成”后核对文字");
                    short[] pcm=new short[1600];
                    int count=0;
                    long deadline=SystemClock.elapsedRealtime()+30000;
                    long nextPreview=0;
                    String lastPreview="";
                    while(!cancelled&&!finish&&SystemClock.elapsedRealtime()<deadline){
                        int size=audio.read(pcm);
                        if(size<0){if(finish||cancelled)break;throw new IllegalStateException("audio read");}
                        if(size==0){SystemClock.sleep(10);continue;}
                        if(size>pcm.length)throw new IllegalStateException("audio buffer size");
                        float[] samples=new float[size];
                        for(int i=0;i<size;i++)samples[i]=pcm[i]/32768f;
                        stream.acceptWaveform(samples,16000);
                        count+=size;
                        while(!cancelled&&recognizer.isReady(stream))recognizer.decode(stream);
                        long now=SystemClock.elapsedRealtime();
                        if(!cancelled&&!finish&&now>=nextPreview){
                            String hypothesis=recognizer.getResult(stream).getText().trim();
                            if(!hypothesis.equals(lastPreview)){preview.offer(hypothesis);lastPreview=hypothesis;}
                            nextPreview=now+200;
                        }
                    }
                    preview.close();
                    synchronized(captureLock){stopCaptureLocked();}
                    if(cancelled)return "";
                    state("本地识别中…");
                    stream.acceptWaveform(new float[8000],16000);
                    stream.inputFinished();
                    while(!cancelled&&recognizer.isReady(stream))recognizer.decode(stream);
                    return count<1600?"":recognizer.getResult(stream).getText().trim();
                }finally{stream.release();}
            }
        }
    }
}
