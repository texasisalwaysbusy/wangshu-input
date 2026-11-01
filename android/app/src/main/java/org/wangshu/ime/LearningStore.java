package org.wangshu.ime;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.AtomicFile;
import java.io.*;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** Local opt-in selection weights only. Never receives raw keys, surrounding text or audio. */
final class LearningStore {
    static final String PREFERENCES="candidate-learning",ENABLED="enabled";
    private static volatile LearningStore instance,loadedBacking;
    private static volatile boolean paused,ready,enabledValue;
    private static volatile IOException loadFailure;
    private static boolean loading;
    private static final ArrayList<Runnable> loadListeners=new ArrayList<>();
    private static final Object epochGate=new Object();
    // Identity tickets avoid numeric wraparound. Changing this cancels only unclaimed automatic jobs.
    private static Object automaticEpoch=new Object();
    private static Object preferenceEpoch=new Object();
    private static boolean preferenceFailureSuspends;
    private static final java.util.concurrent.atomic.AtomicReference<Outcome> pendingNotice=new java.util.concurrent.atomic.AtomicReference<>();
    private static SharedPreferences observedPreferences;
    private static final SharedPreferences.OnSharedPreferenceChangeListener preferenceListener=(prefs,key)->{
        if(!ENABLED.equals(key))return;
        boolean value;try{value=prefs.getBoolean(ENABLED,false);}catch(ClassCastException invalid){value=false;}
        synchronized(epochGate){preferenceEpoch=new Object();enabledValue=value&&!preferenceFailureSuspends;if(!enabledValue)automaticEpoch=new Object();}
    };
    private final AtomicFile file;
    private volatile CandidateLearning model;
    private final Object diskGate=new Object();
    private volatile boolean writeUncertain;
    private final ThreadPoolExecutor writer=new ThreadPoolExecutor(1,1,0,TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<Runnable>(16),r->{Thread t=new Thread(r,"ime-candidate-writer");t.setDaemon(true);return t;},new ThreadPoolExecutor.AbortPolicy());
    enum Action { RECORD,PIN,UNPIN,FORGET }
    enum Outcome { CHANGED,UNCHANGED,CAPACITY_BLOCKED,CANCELLED,FAILED,REJECTED }
    interface Completion {void completed(Outcome outcome);}
    static final class LoadingException extends IOException {LoadingException(){super("个人排序正在后台加载");}}

    /** Starts one process-wide background load; callback runs off the UI thread. */
    static void prepare(Context context,Runnable completion){
        Context app=context.getApplicationContext();if(app==null)app=context;
        synchronized(LearningStore.class){
            if(ready){if(completion!=null)completion.run();return;}
            if(completion!=null)loadListeners.add(completion);
            if(loading)return;loading=true;
        }
        final Context owner=app;
        Thread load=new Thread(()->{
            LearningStore loaded=null;IOException failure=null;SharedPreferences prefs=null;boolean value=false;Object preferenceTicket=null;
            try{
                prefs=owner.getSharedPreferences(PREFERENCES,0);prefs.registerOnSharedPreferenceChangeListener(preferenceListener);
                synchronized(epochGate){preferenceTicket=preferenceEpoch;}
                try{value=prefs.getBoolean(ENABLED,false);}catch(ClassCastException invalid){value=false;}
                loaded=new LearningStore(new File(owner.getFilesDir(),"candidate-learning-v1.tsv"));
            }catch(IOException|RuntimeException error){failure=new IOException("本机个人排序文件无法读取，原文件保留",error);}
            ArrayList<Runnable> notify;
            synchronized(LearningStore.class){
                // Test-owned or already initialized instance wins; never redirect an in-flight writer.
                loadedBacking=loaded;if(instance==null&&loaded!=null)instance=loaded;
                observedPreferences=prefs;loadFailure=failure;
                synchronized(epochGate){if(preferenceTicket==preferenceEpoch)enabledValue=value&&!preferenceFailureSuspends;}
                ready=true;loading=false;
                notify=new ArrayList<>(loadListeners);loadListeners.clear();
            }
            for(Runnable listener:notify)try{listener.run();}catch(RuntimeException ignored){/* Loading result remains available. */}
        },"ime-candidate-load");load.setDaemon(true);load.start();
    }
    /** Never waits for loading or persistence; an unavailable overlay does not block basic input. */
    static LearningStore get(Context context)throws IOException {
        LearningStore current=instance;if(current!=null)return current;
        if(ready&&loadedBacking!=null){synchronized(LearningStore.class){if(instance==null)instance=loadedBacking;return instance;}}
        if(!ready){prepare(context,null);throw new LoadingException();}
        if(loadFailure!=null)throw new IOException("本机个人排序文件无法读取，原文件保留",loadFailure);
        throw new IOException("本机个人排序不可用");
    }
    static boolean ready(){return ready;}
    static Outcome takeNotice(){return pendingNotice.getAndSet(null);}
    static boolean paused(){return paused;}
    static void pause(boolean value){synchronized(epochGate){paused=value;if(value)automaticEpoch=new Object();}}
    static boolean enabled(Context context){if(!ready)prepare(context,null);return ready&&enabledValue;}
    static boolean mayLearn(Context context,boolean privateInput){return !privateInput&&!paused&&enabled(context);}
    /** Cancels unclaimed automatic work on input/session/private transitions. Claimed work may complete. */
    static void cancelPendingAutomatic(){synchronized(epochGate){automaticEpoch=new Object();}}
    /** Called before the explicit off switch writes its preference. */
    static void disableAutomatic(){synchronized(epochGate){preferenceEpoch=new Object();enabledValue=false;automaticEpoch=new Object();}}
    static void enableAutomatic(){synchronized(epochGate){preferenceEpoch=new Object();preferenceFailureSuspends=false;enabledValue=true;}}
    static void preferenceSaveFailed(){synchronized(epochGate){preferenceFailureSuspends=true;preferenceEpoch=new Object();enabledValue=false;automaticEpoch=new Object();}}
    boolean writeUncertain(){return writeUncertain;}

    LearningStore(File target)throws IOException {
        file=new AtomicFile(target);
        if(target.exists()||new File(target+".bak").exists()){
            try(InputStream input=file.openRead();ByteArrayOutputStream bytes=new ByteArrayOutputStream()){
                byte[] block=new byte[4096];int length;
                while((length=input.read(block))!=-1){if(bytes.size()+length>CandidateLearning.MAX_BYTES)throw new IOException("个人排序文件超过格式限制，未覆盖");bytes.write(block,0,length);}
                model=CandidateLearning.decode(bytes.toByteArray());
            }
        }else model=new CandidateLearning();
    }
    // Published snapshots are never mutated; these bounded reads do not acquire the disk lock.
    long revision(){return model.revision();}
    int size(){return model.size();}
    String[] candidates(String key,long now){return model.candidates(key,now);}
    boolean pinned(String key,String word){return model.isPinned(key,word);}
    CandidateLearning.Update record(String key,String word,long now)throws IOException{return mutate(Action.RECORD,key,word,now);}
    CandidateLearning.Update pin(String key,String word,long now)throws IOException{return mutate(Action.PIN,key,word,now);}
    CandidateLearning.Update unpin(String key,String word,long now)throws IOException{return mutate(Action.UNPIN,key,word,now);}
    CandidateLearning.Update forget(String key,String word)throws IOException{return mutate(Action.FORGET,key,word,0);}

    private static boolean boundedIdentity(String key,String word){return key!=null&&!key.isEmpty()&&key.length()<=CandidateLearning.MAX_PINYIN_LENGTH*2&&word!=null&&!word.isEmpty()&&word.length()<=CandidateLearning.MAX_WORD_CODE_POINTS*4;}
    boolean enqueueAutomatic(String key,String word,long now,Completion completion){
        if(!boundedIdentity(key,word)||now<0){complete(completion,Outcome.FAILED);return false;}
        synchronized(epochGate){
            if(paused||!enabledValue){complete(completion,Outcome.CANCELLED);return false;}
            Object ticket=automaticEpoch;
            return submit(()->{
                synchronized(epochGate){if(ticket!=automaticEpoch||paused||!enabledValue){complete(completion,Outcome.CANCELLED);return;}}
                execute(Action.RECORD,key,word,now,completion);
            },completion);
        }
    }
    boolean enqueueManual(Action action,String key,String word,long now,Completion completion){
        if(action==null||action==Action.RECORD)throw new IllegalArgumentException("Expected explicit candidate operation");
        if(!boundedIdentity(key,word)||now<0){complete(completion,Outcome.FAILED);return false;}
        // No queued older automatic operation may resurrect a forgotten/pinned identity.
        synchronized(epochGate){automaticEpoch=new Object();return submit(()->execute(action,key,word,now,completion),completion);}
    }
    private boolean submit(Runnable action,Completion completion){
        try{writer.execute(action);return true;}catch(RejectedExecutionException full){complete(completion,Outcome.REJECTED);return false;}
    }
    private static void complete(Completion completion,Outcome result){if(result==Outcome.FAILED||result==Outcome.REJECTED||result==Outcome.CAPACITY_BLOCKED)pendingNotice.accumulateAndGet(result,(previous,next)->previous==Outcome.FAILED?previous:next);if(completion!=null)try{completion.completed(result);}catch(RuntimeException ignored){/* Never replay input or corrupt the worker. */}}
    private void execute(Action action,String key,String word,long now,Completion completion){
        try{CandidateLearning.Update result=mutate(action,key,word,now);complete(completion,Outcome.valueOf(result.name()));}
        catch(IOException|IllegalArgumentException|IllegalStateException failure){complete(completion,Outcome.FAILED);}
    }
    private CandidateLearning.Update mutate(Action action,String key,String word,long now)throws IOException {
        synchronized(diskGate){
            if(writeUncertain)throw new IOException("上次保存结果不明；请重启后检查个人排序");
            CandidateLearning next=model.copy();CandidateLearning.Update update;
            switch(action){case RECORD:update=next.recordSelection(key,word,now);break;case PIN:update=next.pin(key,word,now);break;case UNPIN:update=next.unpin(key,word,now);break;case FORGET:update=next.forget(key,word);break;default:throw new IllegalArgumentException("Unknown operation");}
            return save(next,update);
        }
    }
    private CandidateLearning.Update save(CandidateLearning next,CandidateLearning.Update update)throws IOException {
        if(update!=CandidateLearning.Update.CHANGED)return update;
        byte[] bytes=next.encode();FileOutputStream stream=null;
        try{
            stream=file.startWrite();stream.write(bytes);stream.getFD().sync();file.finishWrite(stream);stream=null;
            try(InputStream confirmed=file.openRead();ByteArrayOutputStream actual=new ByteArrayOutputStream()){
                byte[] block=new byte[4096];int length;while((length=confirmed.read(block))!=-1){if(actual.size()+length>CandidateLearning.MAX_BYTES)throw new IOException("个人排序保存结果未确认");actual.write(block,0,length);}
                if(!Arrays.equals(bytes,actual.toByteArray()))throw new IOException("个人排序保存结果未确认");
            }
            model=next;return update;
        }catch(IOException|RuntimeException failure){writeUncertain=true;if(stream!=null)try{file.failWrite(stream);}catch(RuntimeException rollback){failure.addSuppressed(rollback);}if(failure instanceof IOException)throw (IOException)failure;throw new IOException("个人排序保存结果未确认",failure);}
    }
}
