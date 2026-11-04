package org.wangshu.ime;

import android.content.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** Production recovery code, synthetic SharedPreferences + retained real host evidence fixtures. */
public final class SettingsRecoveryChecks {
    static int assertions;
    static final byte[] BROKEN="<map><broken synthetic fault &".getBytes(StandardCharsets.UTF_8);
    static final byte[] BACKUP="older synthetic XML backup".getBytes(StandardCharsets.UTF_8);
    static Path fixtures;
    static void require(boolean value,String message){assertions++;if(!value)throw new AssertionError(message);}
    interface Checked {void run()throws Exception;}
    static void rejected(Checked action,String label)throws Exception {try{action.run();throw new AssertionError("accepted: "+label);}catch(IOException expected){assertions++;}}
    static AppPreferencesChecks.Owner owner(String label)throws Exception {
        Map<String,Map<String,Object>> disk=new HashMap<>();Path folder=Files.createDirectory(fixtures.resolve(label));
        AppPreferencesChecks.Owner.DIRECTORIES.put(disk,folder.toFile());return new AppPreferencesChecks.Owner(disk);
    }
    static AppPreferencesChecks.Memory file(AppPreferencesChecks.Owner owner){return owner.getSharedPreferences(AppPreferences.FILE,0);}
    static Path xml(AppPreferencesChecks.Owner owner){return owner.getDataDir().toPath().resolve("shared_prefs").resolve(AppPreferences.FILE+".xml");}
    static Path marker(AppPreferencesChecks.Owner owner){return owner.getFilesDir().toPath().resolve(AppPreferences.FILE+".migration-attempted");}
    static AppPreferencesChecks.Owner corrupt(String label)throws Exception {
        AppPreferencesChecks.Owner owner=owner(label);Files.createDirectory(xml(owner).getParent());Files.write(xml(owner),BROKEN);
        owner.getSharedPreferences("clipboard",0).edit().putBoolean("enabled",true).commit();
        owner.getSharedPreferences("candidate-learning",0).edit().putBoolean("enabled",true).commit();
        require(!AppPreferences.writable(owner),label+" starts paused");return owner;
    }
    static byte[] incoming()throws Exception {return SettingsBackup.encode(key->key.name.equals("candidateFontDelta")?3:key.name.equals("phraseEnglish")?true:key.fallback);}
    static long count(AppPreferencesChecks.Owner owner,String prefix)throws IOException {try(java.util.stream.Stream<Path> paths=Files.list(owner.getFilesDir().toPath())){return paths.filter(path->path.getFileName().toString().startsWith(prefix)).count();}}
    static void paused(AppPreferencesChecks.Owner owner,String label)throws Exception {
        require(!AppPreferences.writable(owner),label+" not writable");
        require(!AppPreferences.open(owner,"clipboard").getBoolean("enabled",true),label+" capture forced off even with true fallback");
        require(!AppPreferences.open(owner,"candidate-learning").getBoolean("enabled",true),label+" learning forced off even with true fallback");
        rejected(()->AppPreferences.snapshot(owner),label+" cannot export safe defaults as real settings");
    }
    public static void main(String[] args)throws Exception {
        fixtures=Files.createDirectory(Path.of(args[0]));
        AppPreferencesChecks.Owner c=corrupt("corrupt-success");AppPreferencesChecks.Memory f=file(c);
        Files.write(Path.of(xml(c)+".bak"),BACKUP);
        f.values.put("keyboard:future", "retained unknown value");
        SharedPreferences oldView=AppPreferences.open(c,"clipboard");SharedPreferences.Editor staleEditor=oldView.edit().putBoolean("enabled",true);
        int edits=f.editors,commits=f.commits;AppPreferences.RecoveryPreview preview=AppPreferences.previewRecovery(c,incoming());
        require(f.editors==edits&&f.commits==commits,"preview never creates preference editor or commits");
        require(count(c,SettingsRecoveryFiles.PREFIX)==0&&count(c,SettingsRecoveryFiles.FENCE_PREFIX)==0,"preview creates no evidence/fence files");
        rejected(()->AppPreferences.previewRecovery(c,"bad backup".getBytes(StandardCharsets.UTF_8)),"invalid backup");
        require(f.editors==edits,"invalid backup has no editor side effects");
        File evidence=AppPreferences.recover(c,preview);
        require(Arrays.equals(Files.readAllBytes(evidence.toPath().resolve(AppPreferences.FILE+".xml")),BROKEN),"exact XML bytes preserved");
        require(Arrays.equals(Files.readAllBytes(evidence.toPath().resolve(AppPreferences.FILE+".xml.bak")),BACKUP),"exact backup bytes preserved");
        String manifest=Files.readString(evidence.toPath().resolve("manifest.tsv"));
        require(manifest.contains(AppPreferences.FILE+".migration-attempted\tabsent\t0\t"),"manifest records absent marker");
        require(manifest.contains("\tpresent\t"+BROKEN.length+"\t"),"manifest contains size and SHA-256");
        require(f.commits==commits+1&&f.editors==edits+1,"single recovery editor and commit");
        require(Boolean.FALSE.equals(f.disk.get("clipboard:enabled"))&&Boolean.FALSE.equals(f.disk.get("candidate-learning:enabled")),"both consent flags committed false");
        require(f.disk.get("keyboard:future").equals("retained unknown value"),"unknown in-memory keys retained");
        require(f.disk.get(AppPreferences.RECOVERY_FENCE).equals(SettingsRecoveryFiles.currentFence(c.getFilesDir())),"recovery token matches durable host fence set");
        paused(c,"successful repair current process");
        require(!staleEditor.commit(),"pre-recovery staged editor cannot revive capture");staleEditor.apply();
        require(f.commits==commits+1,"blocked reused editor cannot persist after repair");
        rejected(()->AppPreferences.recover(c,preview),"same token reused");
        rejected(()->AppPreferences.previewRecovery(c,incoming()),"new preview after repair attempt requires restart");
        AppPreferencesChecks.Owner restart=new AppPreferencesChecks.Owner(c.disk);
        require(AppPreferences.writable(restart),"matching recovery token unlocks fresh process model");
        require(AppPreferences.open(restart,"keyboard").getInt("candidateFontDelta",0)==3,"requested setting survives simulated restart");
        require(!AppPreferences.open(restart,"clipboard").getBoolean("enabled",true)&&!AppPreferences.open(restart,"candidate-learning").getBoolean("enabled",true),"restart never imports old enabled legacy flags");
        rejected(()->AppPreferences.previewRecovery(restart,incoming()),"healthy settings cannot enter recovery");
        require(AppPreferences.open(restart,"clipboard").edit().putBoolean("enabled",true).commit(),"later explicit consent can be saved through normal settings");
        require(AppPreferences.open(new AppPreferencesChecks.Owner(c.disk),"clipboard").getBoolean("enabled",false),"explicit later consent is not reset by permanent fence");
        require(c.getSharedPreferences("clipboard",0).getBoolean("enabled",false),"legacy files not edited by recovery");

        AppPreferencesChecks.Owner defaults=corrupt("default-recovery");
        AppPreferences.recover(defaults,AppPreferences.previewRecovery(defaults,SettingsBackup.encode(key->key.fallback)));
        AppPreferencesChecks.Owner defaultsRestart=new AppPreferencesChecks.Owner(defaults.disk);
        require(Arrays.equals(AppPreferences.snapshot(defaultsRestart),SettingsBackup.encode(key->key.fallback)),"default recovery available without old backup");

        AppPreferencesChecks.Owner missing=owner("missing-unified");
        Files.writeString(marker(missing),"prior migration interrupted");
        missing.getSharedPreferences("clipboard",0).edit().putBoolean("enabled",true).commit();paused(missing,"missing XML");
        File missingCopy=AppPreferences.recover(missing,AppPreferences.previewRecovery(missing,incoming()));
        require(Files.readString(missingCopy.toPath().resolve("manifest.tsv")).contains(AppPreferences.FILE+".xml\tabsent"),"missing XML recorded without invented bytes");
        require(!AppPreferences.open(new AppPreferencesChecks.Owner(missing.disk),"clipboard").getBoolean("enabled",true),"missing XML repaired without importing legacy consent");

        AppPreferencesChecks.Owner migration=owner("migration-failure");
        migration.getSharedPreferences("clipboard",0).edit().putBoolean("enabled",true).commit();file(migration).fail=true;paused(migration,"failed initial migration");
        require(Boolean.TRUE.equals(file(migration).values.get("clipboard:enabled")),"failed migration fixture models changed process memory");
        file(migration).fail=false;AppPreferences.recover(migration,AppPreferences.previewRecovery(migration,incoming()));
        require(!AppPreferences.open(new AppPreferencesChecks.Owner(migration.disk),"clipboard").getBoolean("enabled",true),"repair replaces failed migration memory consent with false");

        AppPreferencesChecks.Owner failed=corrupt("false-commit");AppPreferencesChecks.Memory failedFile=file(failed);
        failedFile.disk.put("clipboard:enabled",true);failedFile.disk.put("candidate-learning:enabled",true);failedFile.disk.put(AppPreferences.MIGRATED,true);
        Map<String,Object> oldDisk=new HashMap<>(failedFile.disk);failedFile.fail=true;
        AppPreferences.RecoveryPreview failedPreview=AppPreferences.previewRecovery(failed,incoming());
        rejected(()->AppPreferences.recover(failed,failedPreview),"commit false");
        require(failedFile.disk.equals(oldDisk),"failed commit leaves old consent-bearing disk in model");
        require(Boolean.FALSE.equals(failedFile.values.get("clipboard:enabled")),"commit false can still change process memory");
        paused(failed,"failed recovery current process");paused(new AppPreferencesChecks.Owner(failed.disk),"old valid consent file after failed recovery restart");
        rejected(()->AppPreferences.recover(failed,failedPreview),"failed token cannot be retried");
        require(count(failed,SettingsRecoveryFiles.PREFIX)==1&&count(failed,SettingsRecoveryFiles.FENCE_PREFIX)==1,"failure preserves evidence and fence");
        AppPreferencesChecks.Owner retry=new AppPreferencesChecks.Owner(failed.disk);
        File retryCopy=AppPreferences.recover(retry,AppPreferences.previewRecovery(retry,incoming()));
        require(count(retry,SettingsRecoveryFiles.PREFIX)==2&&count(retry,SettingsRecoveryFiles.FENCE_PREFIX)==2,"explicit retry after restart adds evidence and fence without replacing first");
        require(Files.readString(retryCopy.toPath().resolve("manifest.tsv")).contains(SettingsRecoveryFiles.FENCE_PREFIX),"retry preserves earlier recovery fence evidence");
        require(AppPreferences.writable(new AppPreferencesChecks.Owner(retry.disk)),"new confirmed recovery token unlocks restart");

        AppPreferencesChecks.Owner throwing=corrupt("throwing-commit");file(throwing).throwCommit=true;
        rejected(()->AppPreferences.recover(throwing,AppPreferences.previewRecovery(throwing,incoming())),"throwing commit");paused(throwing,"throwing commit current process");
        require(file(throwing).commits==1,"throwing commit not automatically retried");
        require(!AppPreferences.open(new AppPreferencesChecks.Owner(throwing.disk),"clipboard").getBoolean("enabled",true),"possibly saved throwing commit remains consent-off after restart");

        AppPreferencesChecks.Owner editorFailure=corrupt("editor-failure");file(editorFailure).failEdit=true;
        rejected(()->AppPreferences.recover(editorFailure,AppPreferences.previewRecovery(editorFailure,incoming())),"editor failure");
        require(file(editorFailure).commits==0&&count(editorFailure,SettingsRecoveryFiles.FENCE_PREFIX)==1,"editor failure leaves fence before any commit");
        paused(new AppPreferencesChecks.Owner(editorFailure.disk),"editor failure restart");

        AppPreferencesChecks.Owner noDisk=corrupt("true-without-durability");file(noDisk).skipDisk=true;
        AppPreferences.recover(noDisk,AppPreferences.previewRecovery(noDisk,incoming()));
        paused(new AppPreferencesChecks.Owner(noDisk.disk),"in-memory success without matching disk transaction");

        AppPreferencesChecks.Owner readback=corrupt("readback-change");file(readback).afterCommit=()->file(readback).values.put("clipboard:enabled",true);
        rejected(()->AppPreferences.recover(readback,AppPreferences.previewRecovery(readback,incoming())),"readback consent mismatch");paused(readback,"readback mismatch");
        rejected(()->AppPreferences.previewRecovery(readback,incoming()),"readback mismatch no automatic retry");

        AppPreferencesChecks.Owner staleMemory=corrupt("stale-memory");AppPreferences.RecoveryPreview stale=AppPreferences.previewRecovery(staleMemory,incoming());file(staleMemory).values.put("keyboard:new","changed after preview");
        rejected(()->AppPreferences.recover(staleMemory,stale),"stale memory preview");
        require(file(staleMemory).commits==0&&count(staleMemory,SettingsRecoveryFiles.PREFIX)==0,"stale memory rejection before preserve or editor");
        rejected(()->AppPreferences.recover(staleMemory,stale),"rejected token single-use");
        AppPreferences.recover(staleMemory,AppPreferences.previewRecovery(staleMemory,incoming()));require(file(staleMemory).commits==1,"fresh explicit preview can recover after pre-write stale rejection");

        AppPreferencesChecks.Owner staleFile=corrupt("stale-file");AppPreferences.RecoveryPreview staleFilePreview=AppPreferences.previewRecovery(staleFile,incoming());Files.writeString(xml(staleFile),"changed fault bytes");
        rejected(()->AppPreferences.recover(staleFile,staleFilePreview),"stale file preview");
        require(file(staleFile).editors==0&&count(staleFile,SettingsRecoveryFiles.FENCE_PREFIX)==0,"changed file never creates editor or fence");
        AppPreferencesChecks.Owner lateBackup=corrupt("late-backup");AppPreferences.RecoveryPreview late=AppPreferences.previewRecovery(lateBackup,incoming());Files.write(Path.of(xml(lateBackup)+".bak"),BACKUP);
        rejected(()->AppPreferences.recover(lateBackup,late),"backup appears after preview");require(file(lateBackup).commits==0,"late backup no commit");

        AppPreferencesChecks.Owner memoryDuringCopy=corrupt("memory-during-copy");AppPreferences.RecoveryPreview during=AppPreferences.previewRecovery(memoryDuringCopy,incoming());AtomicInteger reads=new AtomicInteger();
        file(memoryDuringCopy).beforeRead=()->{if(reads.incrementAndGet()==2)file(memoryDuringCopy).values.put("synthetic","changed during copy");};
        rejected(()->AppPreferences.recover(memoryDuringCopy,during),"memory changes during preserve");
        require(count(memoryDuringCopy,SettingsRecoveryFiles.PREFIX)==1&&count(memoryDuringCopy,SettingsRecoveryFiles.FENCE_PREFIX)==0&&file(memoryDuringCopy).editors==0,"preserved evidence remains, no editor after memory race");

        AppPreferencesChecks.Owner other=corrupt("different-owner");
        rejected(()->AppPreferences.recover(other,AppPreferences.previewRecovery(corrupt("different-preview-owner"),incoming())),"token from another store");require(file(other).commits==0,"foreign token has no writes");

        AppPreferencesChecks.Owner concurrency=corrupt("concurrent-confirmations");
        AppPreferences.RecoveryPreview one=AppPreferences.previewRecovery(concurrency,incoming()),two=AppPreferences.previewRecovery(concurrency,incoming());
        CountDownLatch start=new CountDownLatch(1);AtomicInteger wins=new AtomicInteger(),rejections=new AtomicInteger();List<Throwable> errors=Collections.synchronizedList(new ArrayList<>());
        Runnable recoverOne=()->{try{start.await();AppPreferences.recover(concurrency,one);wins.incrementAndGet();}catch(IOException expected){rejections.incrementAndGet();}catch(Throwable bad){errors.add(bad);}};
        Runnable recoverTwo=()->{try{start.await();AppPreferences.recover(concurrency,two);wins.incrementAndGet();}catch(IOException expected){rejections.incrementAndGet();}catch(Throwable bad){errors.add(bad);}};
        Thread t1=new Thread(recoverOne),t2=new Thread(recoverTwo),writer=new Thread(()->{try{start.await();for(int i=0;i<30;i++){SharedPreferences.Editor blocked=AppPreferences.open(concurrency,"clipboard").edit().putBoolean("enabled",true);if(blocked.commit())throw new AssertionError("concurrent paused write succeeded");blocked.apply();}}catch(Throwable bad){errors.add(bad);}});
        t1.start();t2.start();writer.start();start.countDown();t1.join();t2.join();writer.join();
        require(errors.isEmpty()&&wins.get()==1&&rejections.get()==1,"two concurrent confirmations admit exactly one transaction");
        require(file(concurrency).commits==1&&count(concurrency,SettingsRecoveryFiles.FENCE_PREFIX)==1,"concurrent blocked/reused editors do not add commits or fences");
        require(Boolean.FALSE.equals(file(concurrency).disk.get("clipboard:enabled")),"concurrent editor cannot revive consent");

        AppPreferencesChecks.Owner unreadable=corrupt("unreadable-memory");file(unreadable).failGetAll=true;
        rejected(()->AppPreferences.previewRecovery(unreadable,incoming()),"unreadable memory");require(file(unreadable).editors==0,"unreadable memory no writes");
        AppPreferencesChecks.Owner symlink=owner("symlink-file");Files.createDirectory(xml(symlink).getParent());Path target=symlink.getFilesDir().toPath().resolve("synthetic-original");Files.write(target,BROKEN);
        boolean symlinkCreated=false;
        try{Files.createSymbolicLink(xml(symlink),target);symlinkCreated=true;}
        catch(FileSystemException unavailable){
            // Windows may require a privilege/developer setting. Do not request it or fake a symlink check.
            if(!System.getProperty("os.name","").toLowerCase(Locale.ROOT).startsWith("windows")||Files.exists(xml(symlink),LinkOption.NOFOLLOW_LINKS))throw unavailable;
            System.out.println("HOST_SYMLINK=UNAVAILABLE; two source-symlink assertions NOT_RUN: "+unavailable);
        }
        if(symlinkCreated){rejected(()->AppPreferences.previewRecovery(symlink,incoming()),"symlink source");require(Arrays.equals(Files.readAllBytes(target),BROKEN),"symlink target never modified");}
        AppPreferencesChecks.Owner directory=owner("nonregular-file");Files.createDirectories(xml(directory));
        rejected(()->AppPreferences.previewRecovery(directory,incoming()),"directory pretending to be XML");require(file(directory).editors==0,"nonregular evidence no editor");
        AppPreferencesChecks.Owner oversized=owner("oversized-file");Files.createDirectory(xml(oversized).getParent());try(RandomAccessFile sparse=new RandomAccessFile(xml(oversized).toFile(),"rw")){sparse.setLength(16L*1024*1024+1);}
        rejected(()->AppPreferences.previewRecovery(oversized,incoming()),"oversized evidence");require(file(oversized).editors==0,"oversized evidence no editor");
        AppPreferencesChecks.Owner aggregate=owner("aggregate-oversized");Files.createDirectory(xml(aggregate).getParent());
        try(RandomAccessFile a=new RandomAccessFile(xml(aggregate).toFile(),"rw");RandomAccessFile b=new RandomAccessFile(Path.of(xml(aggregate)+".bak").toFile(),"rw")){a.setLength(9L*1024*1024);b.setLength(9L*1024*1024);}
        rejected(()->AppPreferences.previewRecovery(aggregate,incoming()),"aggregate evidence size limit");require(file(aggregate).editors==0,"aggregate limit stops before editor");
        AppPreferencesChecks.Owner readFailure=corrupt("postcommit-read-failure");file(readFailure).afterCommit=()->file(readFailure).failGetAll=true;
        try{AppPreferences.recover(readFailure,AppPreferences.previewRecovery(readFailure,incoming()));throw new AssertionError("read failure accepted");}catch(IOException expected){require(expected.getMessage().contains("可能已变化")&&!expected.getMessage().contains("尚未写入"),"postcommit read failure must not claim no writes");}
        System.out.println("PASS SettingsRecovery: "+assertions+" assertions; "+(System.getProperty("wangshu.hostTest.directorySync")==null?"production recovery + real retained host file fixtures":"HOST TEST generated recovery + SYNTHETIC directory-sync acknowledgements, real retained file bytes")+"; synthetic preferences/commit/restart model only. Android storage, UI, lifecycle, process kill, power loss NOT_RUN. Fixtures: "+fixtures);
    }
}
