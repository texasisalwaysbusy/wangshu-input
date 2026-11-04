package org.wangshu.ime;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

/** Deliberately unavailable preview backend. Never opens a capture or returns recognition. */
final class OfflineVoice {
    interface Listener {void state(String text);void result(String text);void failure(String text);}
    interface Capture extends AutoCloseable {void start();int read(short[] samples);void stop();void close();}
    interface CaptureFactory {Capture create() throws Exception;}
    static void trimMemory() { }
    static String transcribe(Context context,float[] samples,int sampleRate) {
        throw new UnsupportedOperationException(PreviewFeatures.VOICE_UNAVAILABLE);
    }
    static final class Task {
        private final Listener listener;
        private volatile boolean cancelled;
        Task(Context context,Listener listener) {this.listener=listener;}
        // Retain the app boundary for test compilation; the factory is never invoked.
        Task(Context context,Listener listener,CaptureFactory unused) {this(context,listener);}
        void start() {new Handler(Looper.getMainLooper()).post(()->{if(!cancelled)listener.failure(PreviewFeatures.VOICE_UNAVAILABLE);});}
        void finish() { }
        void cancel() {cancelled=true;}
        boolean awaitStopped(long milliseconds) {return true;}
    }
}
