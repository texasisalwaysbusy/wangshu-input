package org.wangshu.ime;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Runs unchanged production code on an unsupported host, or a separately labelled injected model. */
public final class SettingsDirectorySyncFailureChecks {
    static int assertions;
    static void require(boolean value,String message){assertions++;if(!value)throw new AssertionError(message);}
    interface Checked {void run()throws Exception;}
    static IOException rejected(Checked action,String label)throws Exception {
        try{action.run();throw new AssertionError("accepted: "+label);}catch(IOException expected){assertions++;return expected;}
    }
    static long count(Path root,String prefix)throws IOException {
        try(java.util.stream.Stream<Path> paths=Files.list(root)){return paths.filter(p->p.getFileName().toString().startsWith(prefix)).count();}
    }
    public static void main(String[] args)throws Exception {
        Path root=Files.createDirectory(Path.of(args[0]));
        String scope=args[1];
        rejected(()->SettingsRecoveryFiles.forceDirectory(root),"directory sync must fail in this lane");
        AppPreferencesChecks.Owner migration=new AppPreferencesChecks.Owner();
        migration.getSharedPreferences("clipboard",0).edit().putBoolean("enabled",true).commit();
        AppPreferencesChecks.Memory unified=migration.getSharedPreferences(AppPreferences.FILE,0);
        require(!AppPreferences.writable(migration),"migration stays paused when marker directory sync fails");
        require(unified.editors==0&&unified.commits==0,"failed marker sync precedes any preference editor/commit");
        require(!AppPreferences.open(migration,"clipboard").getBoolean("enabled",true),"failed migration forces capture off");
        require(!AppPreferences.open(migration,"candidate-learning").getBoolean("enabled",true),"failed migration forces learning off");
        require(!AppPreferences.open(migration,"clipboard").edit().putBoolean("enabled",true).commit(),"failed migration blocks writes");
        require(Files.isRegularFile(migration.getFilesDir().toPath().resolve(AppPreferences.FILE+".migration-attempted")),"partially completed marker is retained");
        require(!AppPreferences.writable(new AppPreferencesChecks.Owner(migration.disk)),"observed marker blocks legacy reimport in restart model");
        require(migration.getSharedPreferences("clipboard",0).getBoolean("enabled",false),"legacy consent bytes/model are untouched");

        SettingsRecoveryChecks.fixtures=root;
        AppPreferencesChecks.Owner owner=SettingsRecoveryChecks.corrupt("recovery-sync-failure");
        Path xml=SettingsRecoveryChecks.xml(owner),files=owner.getFilesDir().toPath();
        Files.write(Path.of(xml+".bak"),SettingsRecoveryChecks.BACKUP);
        AppPreferencesChecks.Memory memory=SettingsRecoveryChecks.file(owner);
        Map<String,Object> beforeMemory=new HashMap<>(memory.values),beforeDisk=new HashMap<>(memory.disk);
        AppPreferences.RecoveryPreview preview=AppPreferences.previewRecovery(owner,SettingsRecoveryChecks.incoming());
        require(count(files,SettingsRecoveryFiles.PREFIX)==0,"preview has no evidence side effects");
        rejected(()->AppPreferences.recover(owner,preview),"preserve cannot claim success without directory sync");
        require(memory.editors==0&&memory.commits==0,"evidence sync failure is before every preference editor/commit");
        require(memory.values.equals(beforeMemory)&&memory.disk.equals(beforeDisk),"evidence failure preserves memory and model disk");
        require(Arrays.equals(Files.readAllBytes(xml),SettingsRecoveryChecks.BROKEN),"source XML bytes retained");
        require(Arrays.equals(Files.readAllBytes(Path.of(xml+".bak")),SettingsRecoveryChecks.BACKUP),"source backup bytes retained");
        require(count(files,SettingsRecoveryFiles.PREFIX)==1,"partial evidence directory retained");
        require(count(files,SettingsRecoveryFiles.FENCE_PREFIX)==0,"failed evidence sync cannot create recovery fence");
        Path evidence;
        try(java.util.stream.Stream<Path> paths=Files.list(files)){evidence=paths.filter(p->p.getFileName().toString().startsWith(SettingsRecoveryFiles.PREFIX)).findFirst().orElseThrow();}
        require(Arrays.equals(Files.readAllBytes(evidence.resolve(AppPreferences.FILE+".xml")),SettingsRecoveryChecks.BROKEN),"partial copy XML bytes retained");
        require(Arrays.equals(Files.readAllBytes(evidence.resolve(AppPreferences.FILE+".xml.bak")),SettingsRecoveryChecks.BACKUP),"partial copy backup bytes retained");
        require(Files.readString(evidence.resolve("manifest.tsv"),StandardCharsets.UTF_8).startsWith("WANGSHU-SETTINGS-FAULT\t1\n"),"partial evidence manifest retained");
        require(!AppPreferences.writable(owner),"current process remains paused");
        require(!AppPreferences.open(owner,"clipboard").getBoolean("enabled",true),"recovery failure keeps capture off");
        require(!AppPreferences.open(owner,"candidate-learning").getBoolean("enabled",true),"recovery failure keeps learning off");
        rejected(()->AppPreferences.recover(owner,preview),"failed preview cannot be reused");
        require(count(files,SettingsRecoveryFiles.PREFIX)==1&&memory.editors==0,"no implicit retry or extra evidence");
        require(!AppPreferences.writable(new AppPreferencesChecks.Owner(owner.disk)),"corrupt observed files remain paused in restart model");

        // Exercise the later fence sync independently. A visible file is not proof it survives power loss.
        SettingsRecoveryFiles.State state=SettingsRecoveryFiles.inspect(owner.getFilesDir(),owner.getDataDir());
        rejected(()->SettingsRecoveryFiles.beginRecovery(state),"fence sync cannot report success");
        require(count(files,SettingsRecoveryFiles.FENCE_PREFIX)==1,"failed fence sync retains attempted fence");
        require(memory.editors==0&&memory.commits==0,"direct fence failure never edits preferences");
        System.out.println("PASS DirectorySyncFailure: "+assertions+" assertions; "+scope+"; retained host-only files; synthetic preferences/restart. Android/power-loss durability NOT_RUN.");
    }
}
