package org.wangshu.ime;

import java.io.*;
import java.nio.*;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.*;
import java.util.*;

/** Byte-preserving fault evidence. No rename, replacement, cleanup, or legacy reads. */
final class SettingsRecoveryFiles {
    static final String PREFIX="settings-fault-",FENCE_PREFIX=AppPreferences.FILE+".recovery-fence-";
    private static final int MAX_FENCES=1024;
    private static final long MAX_EVIDENCE_BYTES=16L*1024*1024;
    private static final LinkOption NOFOLLOW=LinkOption.NOFOLLOW_LINKS;
    static final class State {
        final Path files,data;final List<Entry> entries;
        State(Path files,Path data,List<Entry> entries){this.files=files;this.data=data;this.entries=entries;}
        void checkCurrent()throws IOException {
            State current=inspect(files.toFile(),data.toFile());
            if(!files.equals(current.files)||!data.equals(current.data)||!entries.equals(current.entries))
                throw new IOException("预览后设置文件已变化，请重新选择备份并核对；尚未写入设置");
        }
    }
    private static final class Entry {
        final Path path;final boolean exists;final long length;final byte[] hash;final Object identity;final java.nio.file.attribute.FileTime modified;
        Entry(Path path,boolean exists,long length,byte[] hash,Object identity,java.nio.file.attribute.FileTime modified){this.path=path;this.exists=exists;this.length=length;this.hash=hash;this.identity=identity;this.modified=modified;}
        @Override public boolean equals(Object other){if(!(other instanceof Entry))return false;Entry e=(Entry)other;return path.equals(e.path)&&exists==e.exists&&length==e.length&&Arrays.equals(hash,e.hash)&&Objects.equals(identity,e.identity)&&Objects.equals(modified,e.modified);}
        @Override public int hashCode(){return path.hashCode();}
    }
    static State inspect(File filesDir,File dataDir)throws IOException {
        if(filesDir==null||dataDir==null)throw new IOException("本地设置目录不可用");
        Path files=filesDir.toPath().toRealPath(),data=dataDir.toPath().toRealPath();
        if(!Files.isDirectory(files)||!Files.isDirectory(data))throw new IOException("本地设置目录不可用");
        Path preferences=data.resolve("shared_prefs");
        try{if(!Files.readAttributes(preferences,BasicFileAttributes.class,NOFOLLOW).isDirectory())throw new IOException("设置目录不是普通目录");}catch(NoSuchFileException absent){/* No settings files yet. */}
        List<Path> paths=new ArrayList<>();
        paths.add(preferences.resolve(AppPreferences.FILE+".xml"));
        paths.add(preferences.resolve(AppPreferences.FILE+".xml.bak"));
        paths.add(files.resolve(AppPreferences.FILE+".migration-attempted"));
        paths.addAll(fences(files));
        List<Entry> entries=new ArrayList<>();long total=0;
        for(Path path:paths){Entry entry=inspect(path);total+=entry.length;if(total>MAX_EVIDENCE_BYTES)throw new IOException("设置证据合计超过 16 MiB；尚未写入设置，请保留现场后联系维护者");entries.add(entry);}
        return new State(files,data,Collections.unmodifiableList(entries));
    }
    private static List<Path> fences(Path files)throws IOException {
        List<Path> found=new ArrayList<>();
        try(DirectoryStream<Path> listing=Files.newDirectoryStream(files,FENCE_PREFIX+"*")){
            for(Path path:listing){
                if(found.size()>=MAX_FENCES)throw new IOException("修复记录过多，请保留现场后联系维护者");
                if(!Files.readAttributes(path,BasicFileAttributes.class,NOFOLLOW).isRegularFile())throw new IOException("修复凭据不是普通文件；设置继续暂停");
                found.add(path);
            }
        }
        found.sort(Comparator.comparing(path->path.getFileName().toString()));return found;
    }
    static String currentFence(File filesDir)throws IOException {
        if(filesDir==null)throw new IOException("本地设置目录不可用");
        List<Path> found=fences(filesDir.toPath());if(found.isEmpty())return null;
        MessageDigest hash=digest();
        for(Path file:found){hash.update(file.getFileName().toString().getBytes(StandardCharsets.UTF_8));hash.update((byte)0);}
        return hex(hash.digest());
    }
    static String beginRecovery(State expected)throws IOException {
        expected.checkCurrent();
        if(fences(expected.files).size()>=MAX_FENCES)throw new IOException("修复记录过多，请保留现场后联系维护者");
        writeNew(expected.files.resolve(FENCE_PREFIX+UUID.randomUUID()),new byte[0]);
        forceDirectory(expected.files);
        return currentFence(expected.files.toFile());
    }
    private static MessageDigest digest(){try{return MessageDigest.getInstance("SHA-256");}catch(NoSuchAlgorithmException impossible){throw new AssertionError(impossible);}}
    private static Entry inspect(Path path)throws IOException {
        BasicFileAttributes before;
        try{before=Files.readAttributes(path,BasicFileAttributes.class,NOFOLLOW);}catch(NoSuchFileException absent){return new Entry(path,false,0,new byte[0],null,null);}
        if(before.size()>MAX_EVIDENCE_BYTES)throw new IOException("设置证据过大；尚未写入设置，请保留现场后联系维护者");
        if(!before.isRegularFile())throw new IOException("设置证据不是普通文件；尚未写入设置");
        MessageDigest hash=digest();long length=0;
        try(FileChannel source=FileChannel.open(path,StandardOpenOption.READ,NOFOLLOW)){
            ByteBuffer buffer=ByteBuffer.allocate(8192);int read;
            while((read=source.read(buffer))!=-1){hash.update(buffer.array(),0,read);length+=read;if(length>MAX_EVIDENCE_BYTES)throw new IOException("设置证据读取超过上限；尚未写入设置");buffer.clear();}
        }
        BasicFileAttributes after=Files.readAttributes(path,BasicFileAttributes.class,NOFOLLOW);
        if(!after.isRegularFile()||before.size()!=length||after.size()!=length||!Objects.equals(before.fileKey(),after.fileKey())||!before.lastModifiedTime().equals(after.lastModifiedTime()))throw new IOException("读取时设置文件已变化；尚未写入设置");
        return new Entry(path,true,length,hash.digest(),after.fileKey(),after.lastModifiedTime());
    }
    static File preserve(State expected)throws IOException {
        expected.checkCurrent();
        Path directory=Files.createDirectory(expected.files.resolve(PREFIX+UUID.randomUUID()));
        // Keep even partial evidence on any failure. Never reuse an earlier directory.
        StringBuilder manifest=new StringBuilder("WANGSHU-SETTINGS-FAULT\t1\n");
        for(Entry entry:expected.entries){
            manifest.append(entry.path.getFileName()).append('\t').append(entry.exists?"present":"absent").append('\t').append(entry.length).append('\t').append(hex(entry.hash)).append('\n');
            if(!entry.exists)continue;
            Path target=directory.resolve(entry.path.getFileName());
            try(FileChannel source=FileChannel.open(entry.path,StandardOpenOption.READ,NOFOLLOW);FileChannel copy=FileChannel.open(target,StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE)){
                ByteBuffer buffer=ByteBuffer.allocate(8192);long copiedBytes=0;int read;while((read=source.read(buffer))!=-1){copiedBytes+=read;if(copiedBytes>MAX_EVIDENCE_BYTES||copiedBytes>entry.length)throw new IOException("复制时设置证据已变化；尚未写入设置");buffer.flip();while(buffer.hasRemaining())copy.write(buffer);buffer.clear();}copy.force(true);
            }
            Entry copied=inspect(target);if(copied.length!=entry.length||!Arrays.equals(copied.hash,entry.hash))throw new IOException("故障文件复制校验失败；尚未写入设置，已生成的证据保留");
        }
        writeNew(directory.resolve("manifest.tsv"),manifest.toString().getBytes(StandardCharsets.UTF_8));
        forceDirectory(directory);forceDirectory(expected.files);
        expected.checkCurrent();
        return directory.toFile();
    }
    static void writeNew(Path path,byte[] bytes)throws IOException {
        try(FileChannel channel=FileChannel.open(path,StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE)){ByteBuffer buffer=ByteBuffer.wrap(bytes);while(buffer.hasRemaining())channel.write(buffer);channel.force(true);}
    }
    static void forceDirectory(Path path)throws IOException {try(FileChannel channel=FileChannel.open(path,StandardOpenOption.READ)){channel.force(true);}}
    private static String hex(byte[] bytes){StringBuilder out=new StringBuilder();for(byte value:bytes)out.append(String.format(Locale.ROOT,"%02x",value&255));return out.toString();}
    private SettingsRecoveryFiles(){}
}
