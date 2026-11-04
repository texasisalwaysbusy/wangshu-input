package org.wangshu.ime;

import android.app.Instrumentation;
import org.json.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/** Valid repeated large imports must deduplicate before the final capacity check. */
final class PersonalCapacityRegressionChecks {
    private static String hash(byte[] bytes)throws Exception {StringBuilder out=new StringBuilder();for(byte b:MessageDigest.getInstance("SHA-256").digest(bytes))out.append(String.format(Locale.ROOT,"%02x",b&255));return out.toString();}
    static String run(Instrumentation test)throws Exception {
        File folder=new File(test.getTargetContext().getFilesDir(),"personal-capacity-checks-"+UUID.randomUUID());if(!folder.mkdir())throw new IOException("Test-owned folder unavailable");
        PersonalStore store=new PersonalStore(new File(folder,"data.json"));JSONObject incoming=PersonalStore.empty();
        for(int i=0;i<1001;i++)incoming.getJSONArray("words").put(new JSONObject().put("pinyin","same").put("text","synthetic-word-"+i));
        store.merge(incoming);byte[] before=store.export();int count=0;boolean complete=false;
        if(store.snapshot().getJSONArray("words").length()!=1001)throw new AssertionError("First valid 1001-word import failed");count++;System.out.println("PERSONAL_CAPACITY_PASS first valid1001-word import saved");
        try{
            if(new PersonalStore(new File(folder,"data.json")).snapshot().getJSONArray("words").length()!=1001)throw new AssertionError("Large import did not persist");count++;
            JSONObject preview=store.previewMerge(incoming);
            if(preview.getJSONArray("words").length()!=1001||!Arrays.equals(before,store.export()))throw new AssertionError("Idempotent preview changed saved data");count++;
            store.merge(incoming);
            if(store.snapshot().getJSONArray("words").length()!=1001||!Arrays.equals(before,store.export()))throw new AssertionError("Repeated valid import changed canonical data");count++;
            complete=true;return "PASS "+count+" large-import capacity regression checks; isolated synthetic app-owned store only\n";
        }finally{
            byte[] after=store.export();JSONObject record=new JSONObject().put("state",complete?"PASS":"FAILED_OR_INCOMPLETE").put("checks_passed",count).put("input_unique_words",1001).put("current_words",store.snapshot().getJSONArray("words").length()).put("before_sha256",hash(before)).put("after_sha256",hash(after)).put("saved_bytes_unchanged",Arrays.equals(before,after)).put("scope","isolated generated dictionary only; no real user data");
            try(FileOutputStream out=new FileOutputStream(new File(folder,"result.json"))){out.write(record.toString(2).getBytes(StandardCharsets.UTF_8));out.getFD().sync();}
            System.out.println("PERSONAL_CAPACITY_EVIDENCE "+folder.getName()+" saved_bytes_unchanged="+Arrays.equals(before,after));
        }
    }
}
