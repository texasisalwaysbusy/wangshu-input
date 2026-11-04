package org.wangshu.ime;
final class NativeBridge {
    static {System.loadLibrary("wangshuime");}
    static native long load(String directory);
    static native String[] lookup(String input,int page);
    static native String[] predict(String before,String after,boolean english);
    static native String[] symbols(String category);
    static native String voiceMath(String text);
    static native String punctuation(int ascii,boolean chinese);
}
