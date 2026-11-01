package org.wangshu.ime;
import android.content.Context;
import java.io.*;
final class DataFiles {
    static volatile boolean ready=false;
    static volatile long entries=0;
    static volatile String failure="";
    static volatile long loadMilliseconds=0;
    static synchronized long initialize(Context context)throws Exception{
        long started=android.os.SystemClock.elapsedRealtime();failure="";
        try{
        File folder=new File(context.getFilesDir(),"ime-data");folder.mkdirs();
        String desired=read(context.getAssets().open("version.json"));File marker=new File(folder,"version.json");String current=marker.isFile()?read(new FileInputStream(marker)):"";
        if(ready&&desired.equals(current))return entries;
        if(!desired.equals(current)||!new File(folder,"pinyin.tsv").isFile()||!new File(folder,"symbols.tsv").isFile()){
            for(String name:new String[]{"pinyin.tsv","symbols.tsv","version.json"}){File temp=new File(folder,name+".part");try(InputStream in=context.getAssets().open(name);FileOutputStream out=new FileOutputStream(temp)){byte[] b=new byte[65536];int n;while((n=in.read(b))!=-1)out.write(b,0,n);out.getFD().sync();}if(!temp.renameTo(new File(folder,name)))throw new IOException("词典准备失败");}
        }
        entries=NativeBridge.load(folder.getPath());ready=entries>0;if(!ready)throw new IOException("内置词典解析失败");
        loadMilliseconds=android.os.SystemClock.elapsedRealtime()-started;android.util.Log.i("WangshuImeData","Loaded "+entries+" built-in entries in "+loadMilliseconds+" ms");return entries;
        }catch(Exception|LinkageError error){failure=error.getClass().getSimpleName();android.util.Log.e("WangshuImeData","Built-in data initialization failed: "+failure);throw error;}
    }
    static String read(InputStream input)throws IOException{try(InputStream in=input;ByteArrayOutputStream out=new ByteArrayOutputStream()){byte[] b=new byte[8192];int n;while((n=in.read(b))!=-1)out.write(b,0,n);return out.toString("UTF-8");}}
}
