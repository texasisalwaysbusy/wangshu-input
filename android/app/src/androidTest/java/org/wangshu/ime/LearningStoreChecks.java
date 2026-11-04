package org.wangshu.ime;

import android.app.Instrumentation;
import android.content.*;
import android.os.Process;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/** Real Android AtomicFile/prefs on new UUID fixtures. Never changes the real learning file/prefs. */
final class LearningStoreChecks {
    private static int checks;private static StringBuilder log;
    private static void check(boolean ok,String name){if(!ok)throw new AssertionError(name);checks++;log.append("PASS ").append(name).append('\n');}
    private static byte[] bytes(File file)throws IOException{try(InputStream in=new FileInputStream(file);ByteArrayOutputStream out=new ByteArrayOutputStream()){byte[] b=new byte[4096];int n;while((n=in.read(b))!=-1)out.write(b,0,n);return out.toByteArray();}}
    private static void write(File file,byte[] data)throws IOException{try(FileOutputStream out=new FileOutputStream(file)){out.write(data);out.getFD().sync();}}
    static String run(Instrumentation test)throws Exception{
        checks=0;log=new StringBuilder();Context context=test.getTargetContext();File folder=new File(context.getCacheDir(),"learning-check-"+UUID.randomUUID());check(folder.mkdirs(),"isolated test directory created without replacing existing data");
        File file=new File(folder,"model.tsv");LearningStore store=new LearningStore(file);check(store.size()==0&&store.revision()==0&&!file.exists(),"opening an absent model does not create or record anything");long now=35L*24*60*60*1000;
        check(store.record("nihao","你好",now)==CandidateLearning.Update.CHANGED&&file.isFile(),"verified-selection API persists one isolated record with Android AtomicFile");
        LearningStore reopened=new LearningStore(file);check(Arrays.equals(reopened.candidates("nihao",now),new String[]{"你好"}),"a new Store instance reads the exact committed candidate");
        check(store.pin("nihao","𝕏",now)==CandidateLearning.Update.CHANGED&&store.pinned("nihao","𝕏"),"explicit pin preserves mathematical Unicode without compatibility folding");
        reopened=new LearningStore(file);check(reopened.pinned("nihao","𝕏")&&reopened.candidates("nihao",now)[0].equals("𝕏"),"fixed candidate and its priority survive actual file reload");
        check(store.forget("nihao","𝕏")==CandidateLearning.Update.CHANGED&&!new LearningStore(file).pinned("nihao","𝕏")&&store.size()==1,"forget affects only the isolated matching overlay entry");
        byte[] before=bytes(file);long revision=store.revision();boolean invalid=false;try{store.record("not1pinyin","bad",now);}catch(IllegalArgumentException expected){invalid=true;}
        check(invalid&&store.revision()==revision&&Arrays.equals(before,bytes(file)),"invalid input changes neither committed model nor file bytes");
        File blocker=new File(folder,"retained-blocker");write(blocker,"owned fixture: retain".getBytes(StandardCharsets.UTF_8));byte[] original=bytes(blocker);LearningStore failed=new LearningStore(new File(blocker,"unwritable-child.tsv"));boolean refused=false;try{failed.record("nihao","你好",now);}catch(IOException expected){refused=true;}
        check(refused&&failed.size()==0&&failed.revision()==0&&Arrays.equals(original,bytes(blocker)),"failed AtomicFile start never installs the mutated copy or overwrites the blocker");
        File corrupt=new File(folder,"corrupt.tsv");write(corrupt,new byte[]{(byte)0xff,0,1});before=bytes(corrupt);refused=false;try{new LearningStore(corrupt);}catch(IOException expected){refused=true;}
        check(refused&&Arrays.equals(before,bytes(corrupt)),"corrupt existing data is rejected without replacement");
        String name="learning-policy-check-"+UUID.randomUUID();SharedPreferences prefs=context.getSharedPreferences(name,0);File isolatedFiles=new File(folder,name+"-preferences");if(!isolatedFiles.mkdir())throw new AssertionError("settings fixture folder");Context isolated=new ContextWrapper(context){@Override public Context getApplicationContext(){return this;}@Override public java.io.File getFilesDir(){return isolatedFiles;}@Override public java.io.File getDataDir(){return isolatedFiles;}@Override public SharedPreferences getSharedPreferences(String key,int mode){return super.getSharedPreferences(key.equals(LearningStore.PREFERENCES)?name:(AppPreferences.managed(key)||key.equals(AppPreferences.FILE))?name+"-"+key:key,mode);}};
        boolean previous=LearningStore.paused();try{
            LearningStore.pause(false);check(!LearningStore.mayLearn(isolated,false),"fresh isolated preferences default automatic learning off");
            check(prefs.edit().putBoolean(LearningStore.ENABLED,true).commit()&&LearningStore.mayLearn(isolated,false),"explicit isolated opt-in permits eligible learning");
            check(!LearningStore.mayLearn(isolated,true),"private or editor no-learning policy always refuses recording");
            LearningStore.pause(true);check(!LearningStore.mayLearn(isolated,false),"temporary pause overrides enabled automatic learning");LearningStore.pause(false);
            check(prefs.edit().putString(LearningStore.ENABLED,"invalid-type").commit()&&!LearningStore.mayLearn(isolated,false),"malformed preference type fails closed without exception");
        }finally{LearningStore.pause(previous);}
        check(LearningStore.paused()==previous,"the original process pause state is restored");
        return "PASS "+checks+" actual Android isolated AtomicFile/preference checks\n"+log+"PID "+Process.myPid()+"; files retained under test-owned UUID cache directory. New Store instance reload is not a separate-process claim. No actual candidate UI, real typing, user dictionary/history, microphone, phone or Windows acceptance.\n";
    }
}
