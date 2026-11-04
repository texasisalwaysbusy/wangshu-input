package org.wangshu.ime;
import android.content.Context;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;

/** Executes production ZIP/hash/commit code against public pinned models, in a JVM temp context. */
public final class VoiceModelInstallerTest {
    static int checks=0;
    static void check(boolean ok,String label){if(!ok)throw new AssertionError(label);checks++;System.out.println("PASS "+label);}
    static final class Sandbox extends Context {final File root;Sandbox(File f){root=f;}public File getFilesDir(){return root;}}
    static byte[] invalid(String name)throws Exception{ByteArrayOutputStream out=new ByteArrayOutputStream();try(ZipOutputStream zip=new ZipOutputStream(out)){zip.putNextEntry(new ZipEntry(name));zip.write(1);zip.closeEntry();}return out.toByteArray();}
    static void reject(Context c,InputStream in,String label)throws Exception{boolean rejected=false;try{VoiceModels.install(c,in);}catch(IOException e){rejected=true;}check(rejected,label);}
    static long parts(Context c)throws Exception{File parent=VoiceModels.folder(c).getParentFile();if(!parent.isDirectory())return 0;try(var stream=Files.list(parent.toPath())){return stream.filter(p->p.getFileName().toString().endsWith(".part")).count();}}
    public static void main(String[] args)throws Exception{
        Path testRoot=Path.of(args[0]).toAbsolutePath();if(!testRoot.toString().contains("/build/voice-model-installer-test"))throw new IllegalArgumentException("Explicit generated-test root required");Files.createDirectories(testRoot);Sandbox c=new Sandbox(Files.createTempDirectory(testRoot,"fixture-").toFile());File pack=new File(args[1]);
        check(!VoiceModels.available(c),"fresh context has no native-loadable model");reject(c,new ByteArrayInputStream(invalid("../escape.onnx")),"ZIP traversal/unknown entry rejected");check(parts(c)==0,"failed initial package leaves no import staging directory");
        long start=System.nanoTime();try(InputStream in=new FileInputStream(pack)){VoiceModels.install(c,in);}check(VoiceModels.available(c),"canonical public ZIP installs with all pinned SHA-256 checks");
        File current=VoiceModels.folder(c);File original=new File(current,"encoder.onnx");File retained=new File(current,"encoder.original");check(original.renameTo(retained)&&!VoiceModels.available(c),"test fixture creates an incomplete old model without deleting its bytes");
        try(InputStream in=new FileInputStream(pack)){VoiceModels.install(c,in);}check(VoiceModels.available(c),"valid reimport repairs incomplete target");File[] previous=current.getParentFile().listFiles(f->f.getName().startsWith("previous-"));check(previous!=null&&previous.length==1,"repair preserves old directory as one recovery copy");check(Files.mismatch(new File(current,"encoder.onnx").toPath(),new File(previous[0],"encoder.original").toPath())==-1,"old and new full encoder bytes independently compare identical");
        reject(c,new ByteArrayInputStream(invalid("encoder.onnx")),"truncated pinned entry rejected before replacing installed model");check(VoiceModels.available(c)&&parts(c)==0,"failed repair preserves ready model and cleans only staging files");
        Thread.currentThread().interrupt();try{reject(c,new FileInputStream(pack),"interrupted import reports cancellation");}finally{Thread.interrupted();}check(VoiceModels.available(c)&&parts(c)==0,"cancelled import retains current model and recovery copy");
        try(InputStream raw=new FileInputStream(pack)){InputStream interrupted=new FilterInputStream(raw){int count;public int read(byte[] b,int offset,int length)throws IOException{int n=super.read(b,offset,length);if(n>0&&(count+=n)>16384)Thread.currentThread().interrupt();return n;}};try{reject(c,interrupted,"cancellation while consuming compressed data stops import");}finally{Thread.interrupted();}}
        check(VoiceModels.available(c)&&parts(c)==0,"midstream cancellation leaves pinned target intact");
        reject(c,new FileInputStream(args[2]),"duplicate NOTICE entry rejected with bounded entry policy");check(VoiceModels.available(c)&&parts(c)==0,"duplicate-entry failure cannot replace working models");
        System.out.println("TOTAL "+checks+" PASS; production installer on cloud JVM filesystem adapter, not Android runtime; elapsed ms="+(System.nanoTime()-start)/1000000);
        // Only generated public-model fixture copies under the explicit build test root are removed.
        try(var paths=Files.walk(c.root.toPath())){for(Path p:paths.sorted(Comparator.reverseOrder()).toList())Files.delete(p);}
    }
}
