package org.wangshu.ime;

import android.content.SharedPreferences;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Test-only scalar journal. Construct and verify BEFORE any fixture changes. */
final class TestBooleanPreferenceCheckpoint {
    private final SharedPreferences preferences;
    private final String name,key;
    private final boolean present;
    private final Boolean before,paused;
    private final Map<String,Object> unrelated=new HashMap<>();
    private final File journal;
    private boolean restored;
    TestBooleanPreferenceCheckpoint(SharedPreferences preferences,File journal,String name,String key,Boolean paused)throws IOException{
        if(name==null||key==null||!name.matches("[A-Za-z0-9_.-]{1,100}")||!key.matches("[A-Za-z0-9_.-]{1,100}"))throw new IOException("Invalid fixture preference identity");
        this.preferences=preferences;this.journal=journal;this.name=name;this.key=key;this.paused=paused;
        Map<String,?> values=preferences.getAll();present=values.containsKey(key);Object original=values.get(key);
        if(present&&!(original instanceof Boolean))throw new IOException("Malformed original Boolean preference preserved; fixture not started");
        before=present?(Boolean)original:null;
        for(Map.Entry<String,?> entry:values.entrySet())if(!entry.getKey().equals(key))unrelated.put(entry.getKey(),copy(entry.getValue()));
        writeNew(journal,payload("before"));
        if(!matches())throw new IOException("Preference changed while recording checkpoint; fixture not started");
    }
    private static Object copy(Object value){return value instanceof Set?new HashSet<>((Set<?>)value):value;}
    private byte[] payload(String stage){return ("wangshu-test-boolean-v1\npreferences\t"+name+"\nkey\t"+key+"\npresent\t"+(present?"1":"0")+"\nvalue\t"+(present?before.toString():"absent")+"\npaused\t"+(paused==null?"not-applicable":paused.toString())+"\nstage\t"+stage+"\n").getBytes(StandardCharsets.UTF_8);}
    private static void writeNew(File path,byte[] expected)throws IOException{
        if(!path.createNewFile())throw new IOException("Fixture checkpoint already exists; not overwritten");
        try(FileOutputStream out=new FileOutputStream(path)){out.write(expected);out.getFD().sync();}
        if(path.length()!=expected.length||expected.length>1024)throw new IOException("Fixture checkpoint size not confirmed");
        byte[] read=new byte[expected.length];try(InputStream in=new FileInputStream(path)){int at=0,n;while(at<read.length&&(n=in.read(read,at,read.length-at))!=-1)at+=n;if(at!=read.length||in.read()!=-1)throw new IOException("Fixture checkpoint read-back incomplete");}
        if(!Arrays.equals(expected,read))throw new IOException("Fixture checkpoint bytes not confirmed");
    }
    boolean matches(){Map<String,?> now=preferences.getAll();return now.containsKey(key)==present&&Objects.equals(now.get(key),before);}
    void restore()throws IOException{
        restored=false;SharedPreferences.Editor edit=preferences.edit();if(present)edit.putBoolean(key,before);else edit.remove(key);
        if(!edit.commit()||!matches())throw new IOException("Exact Boolean preference restoration not confirmed");restored=true;
    }
    void verifyUnrelated(){Map<String,Object> now=new HashMap<>();for(Map.Entry<String,?> entry:preferences.getAll().entrySet())if(!entry.getKey().equals(key))now.put(entry.getKey(),copy(entry.getValue()));if(!unrelated.equals(now))throw new AssertionError("Unrelated preferences changed; not overwritten by fixture cleanup");}
    void recordRestored()throws IOException{if(!restored||!matches())throw new IOException("No confirmed preference restore receipt");writeNew(new File(journal.getParentFile(),"preferences-restored.tsv"),payload("restored-commit-ack-and-exact-value"));}
}
