package org.wangshu.ime;

import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;

/** Real JVM/native bridge checks. No Android framework, editor, APK, or UI. */
public final class LocalPredictionJniTest {
    private static int checks;
    private static void check(boolean value,String label) {
        if(!value)throw new AssertionError(label);
        checks++;System.out.println("PASS "+label);
    }
    private static boolean contains(String[] pairs,String value) {
        if(pairs==null||pairs.length%2!=0)throw new AssertionError("candidate pair shape");
        for(int i=0;i<pairs.length;i+=2) {
            if(pairs[i]==null||pairs[i+1]==null)throw new AssertionError("null candidate field");
            if(pairs[i].equals(value))return true;
        }
        return false;
    }
    public static void main(String[] args)throws Exception {
        check(args.length==1&&NativeBridge.load(args[0])>100000,"real JNI loads full source dictionary");
        check(contains(NativeBridge.lookup("nihao`OpenAI`zhongwen",0),"你好OpenAI中文"),"mixed composition crosses real JNI with exact English case");
        check(contains(NativeBridge.lookup("nihao`hello world 123`",0),"你好hello world 123"),"mixed literal retains spaces and digits across JNI");
        check(contains(NativeBridge.lookup("\\X",0),"𝕏"),"existing supplementary symbol survives UTF8 UTF16 JNI roundtrip");
        check(contains(NativeBridge.predict("今天","",false),"天气"),"Chinese next unit crosses actual native bridge");
        check(contains(NativeBridge.predict("今天","",false),"天气很好，适合散步。"),"Chinese sentence suffix remains separate candidate");
        check(contains(NativeBridge.predict("换上太阳能电","",false),"池"),"dictionary completion crosses actual native bridge");
        check(NativeBridge.predict("换上太阳能电","，池",false).length==0,"dictionary completion preserves an existing following suffix");
        check(contains(NativeBridge.predict("I would ","",true),"like"),"English next unit crosses actual native bridge");
        check(contains(NativeBridge.predict("THE WEATH","",true),"ER"),"corpus completion preserves uppercase across JNI");
        check(Arrays.equals(NativeBridge.predict("THE WEATH","",true),
                            NativeBridge.predict("The note ended. THE WEATH",". Thanks.",true)),
              "sentence and right punctuation reach the same ranked uppercase suggestions through JNI");
        check(NativeBridge.predict("https://example.com. I would ","",true).length==0,
              "unsafe text stays rejected before a later sentence period through JNI");
        check(contains(NativeBridge.predict("你想不想","",false),"一起"),"Chinese corpus next word crosses JNI");
        check(contains(NativeBridge.predictInNumbers("日期：2026","，备注",false),"年"),"numeric JNI appends a date suffix at a separated caret");
        check(contains(NativeBridge.predictInNumbers("Time: 08","",true),":"),"English time separator crosses numeric JNI");
        check(NativeBridge.predictInNumbers("2+2=","",false).length==0,"numeric JNI does not generate arithmetic values");
        check(contains(NativeBridge.predict("I would ",", later",true),"like"),"middle-field safe suffix boundary remains eligible");
        check(NativeBridge.predict("I would "," like",true).length==0,"duplicate existing following word is suppressed");
        check(NativeBridge.predict("https://host/Thank you ","",true).length==0,"unsafe line stays rejected through JNI");
        check(NativeBridge.predict(null,null,false).length==0,"null JNI context safely refuses");
        check(contains(NativeBridge.predict("I would ",null,true),"like"),"null trailing context treated as empty");
        check(NativeBridge.predict("x".repeat(257),"",true).length==0,"overlong current line safely refuses");
        check(NativeBridge.predict("\uD800\n今天","",false).length==0,"malformed high surrogate anywhere in context refuses rather than repairs");
        check(NativeBridge.predict("今天","\n\uDC00",false).length==0,"malformed trailing low surrogate refuses");
        String[] expected=NativeBridge.predict("PLEASE CHECK THE ","",true);
        check(contains(expected,"ATTACHED")&&expected.length<=8,"uppercase and four-candidate bound retained");
        CountDownLatch start=new CountDownLatch(1);AtomicReference<Throwable> fault=new AtomicReference<>();
        Thread[] workers=new Thread[4];
        for(int i=0;i<workers.length;i++) {
            workers[i]=new Thread(()->{try {
                start.await();for(int n=0;n<100;n++) {
                    if(!Arrays.equals(NativeBridge.predict("PLEASE CHECK THE ","",true),expected))throw new AssertionError("concurrent prediction changed");
                    if(!contains(NativeBridge.lookup("nihao`OpenAI`zhongwen",0),"你好OpenAI中文"))throw new AssertionError("concurrent mixed lookup changed");
                }
            }catch(Throwable failure){fault.compareAndSet(null,failure);}},"jni-check-"+i);
            workers[i].start();
        }
        Thread reload=new Thread(()->{
            try{
                start.await();
                if(NativeBridge.load(args[0]+"/pinyin.tsv")!=0)
                    throw new AssertionError("a dictionary file is not a data directory");
                if(!contains(NativeBridge.lookup("nihao",0),"你好"))
                    throw new AssertionError("failed reload discarded the working dictionary");
                if(NativeBridge.load(args[0])<=100000)
                    throw new AssertionError("valid dictionary replacement was not published");
            }catch(Throwable failure){fault.compareAndSet(null,failure);}
        },"jni-dictionary-reload");
        reload.start();
        start.countDown();for(Thread worker:workers)worker.join();
        reload.join();
        if(fault.get()!=null)throw new AssertionError("concurrent native calls",fault.get());
        check(true,"400 predictions and 400 mixed lookups remain deterministic during failed and successful reloads");
        System.out.println("TOTAL "+checks+" PASS; host JVM JNI only. Android API/runtime/editor/APK/device NOT_RUN. Finite editorial examples are not language quality evidence.");
    }
}
