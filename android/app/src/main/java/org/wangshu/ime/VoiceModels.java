package org.wangshu.ime;

import android.content.Context;
import java.io.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.zip.*;

/** Only the pinned, Apache-2.0 model is accepted. Untrusted ONNX is never loaded. */
final class VoiceModels {
    static final Object MODEL_FILES_LOCK=new Object();
    static final String VERSION="zipformer-zh-en-2023-02-20-v1";
    static final String[] NAMES={"encoder.onnx","decoder.onnx","joiner.onnx","tokens.txt"};
    static final long[] SIZES={181895032L,13876452L,3228404L,56317L};
    static final String[] HASHES={"8fa764187a261844f859d7143ebaa563af5d10adfece4c18a8f414c88cba2a9b","2e3b5ec371f8899ee6acd829fd753ba45772df57a91bdf37cde3136354e7db7d","1ed689c5ed19dbaa725d9d191bb4822b5f4855a39e1ffd28cbc1f340d25b2ee0","a8e0e4ec53810e433789b54a5c0134a7eaa2ffca595a6334d54c00da858841d3"};
    static File folder(Context c){return new File(c.getFilesDir(),"voice/"+VERSION);}
    static boolean available(Context c){if(BuildConfig.BASIC_TYPING_PREVIEW)return false;File f=folder(c);if(!new File(f,"verified").isFile())return false;for(int i=0;i<NAMES.length;i++)if(new File(f,NAMES[i]).length()!=SIZES[i])return false;return true;}
    static synchronized void install(Context c,InputStream input)throws Exception{
        if(BuildConfig.BASIC_TYPING_PREVIEW)throw new UnsupportedOperationException(PreviewFeatures.VOICE_UNAVAILABLE);
        File target=folder(c);
        File parent=target.getParentFile();if(!parent.isDirectory()&&!parent.mkdirs())throw new IOException("无法创建语音目录");
        File temp=new File(parent,"import-"+UUID.randomUUID()+".part");if(!temp.mkdir())throw new IOException("无法准备语音包");
        boolean[] found=new boolean[4];boolean success=false,noticeSeen=false;int entries=0;File previous=null;
        try(ZipInputStream zip=new ZipInputStream(new BufferedInputStream(input))){
            ZipEntry e;byte[] b=new byte[65536];
            while((e=zip.getNextEntry())!=null){
                if(Thread.currentThread().isInterrupted())throw new InterruptedIOException("已取消语音包导入");if(++entries>5)throw new IOException("语音包文件数超出固定模型范围");
                String name=e.getName();int index=Arrays.asList(NAMES).indexOf(name);
                if(index<0){if(!name.equals("NOTICE.txt")||noticeSeen)throw new IOException("语音包含未知或重复文件");noticeSeen=true;long n=0;int k;while((k=zip.read(b))!=-1){if(Thread.currentThread().isInterrupted())throw new InterruptedIOException("已取消语音包导入");n+=k;if(n>65536)throw new IOException("说明文件过大");}continue;}
                if(found[index]||e.isDirectory())throw new IOException("语音包文件重复或无效");found[index]=true;
                MessageDigest hash=MessageDigest.getInstance("SHA-256");long size=0;
                // ZipInputStream can return small inflated chunks. Batch both digest and
                // filesystem calls without changing which bytes are checked or committed.
                try(FileOutputStream file=new FileOutputStream(new File(temp,name));BufferedOutputStream out=new BufferedOutputStream(new java.security.DigestOutputStream(file,hash),65536)){
                    int n;while((n=zip.read(b))!=-1){if(Thread.currentThread().isInterrupted())throw new InterruptedIOException("已取消语音包导入");size+=n;if(size>SIZES[index])throw new IOException("语音模型大小不符");out.write(b,0,n);}out.flush();file.getFD().sync();
                }
                if(size!=SIZES[index]||!hex(hash.digest()).equals(HASHES[index]))throw new IOException("语音模型校验失败，原有输入数据未改变");
            }
            for(boolean value:found)if(!value)throw new IOException("语音包不完整");
            try(FileOutputStream marker=new FileOutputStream(new File(temp,"verified"))){marker.write(VERSION.getBytes(java.nio.charset.StandardCharsets.UTF_8));marker.getFD().sync();}
            if(Thread.currentThread().isInterrupted())throw new InterruptedIOException("已取消语音包导入");
            synchronized(MODEL_FILES_LOCK){
            if(target.exists()){previous=new File(parent,"previous-"+UUID.randomUUID());if(!target.renameTo(previous))throw new IOException("无法保留旧模型副本，现有数据未修改");}
            if(!temp.renameTo(target)){if(previous!=null&&!previous.renameTo(target))throw new IOException("新模型提交失败；旧副本仍保留，可再次导入修复");throw new IOException("无法提交已校验语音包，旧模型已恢复");}success=true;
            }
        }finally{if(!success)removeTemporary(temp);}
    }
    private static void removeTemporary(File f){File[] entries=f.listFiles();if(entries!=null)for(File e:entries)e.delete();f.delete();}
    private static String hex(byte[] b){StringBuilder s=new StringBuilder();for(byte v:b)s.append(String.format(Locale.ROOT,"%02x",v&255));return s.toString();}
}
