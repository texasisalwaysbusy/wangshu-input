package org.wangshu.ime;

import java.util.LinkedHashMap;

/** Tracks outstanding Enter sequences by Android device and key code. */
final class EnterSequenceTracker {
    private static final int MAX_SEQUENCES=32;
    private final LinkedHashMap<Long,Long> active=new LinkedHashMap<>();
    private static long key(int device,int code){return ((long)device<<32)|(code&0xffffffffL);}
    boolean owns(int device,int code,long downTime){Long current=active.get(key(device,code));return current!=null&&current.longValue()==downTime;}
    void own(int device,int code,long downTime){
        long k=key(device,code);active.remove(k);
        if(active.size()>=MAX_SEQUENCES)active.remove(active.keySet().iterator().next());
        active.put(k,downTime);
    }
    void abandon(int device,int code){active.remove(key(device,code));}
    boolean release(int device,int code,long downTime){if(!owns(device,code,downTime))return false;abandon(device,code);return true;}
}
