package org.wangshu.ime;

/** L04. Unknown or corrupt preferences preserve the existing right-side layout. */
final class LanguageKeyPosition {
    static final int RIGHT=0,LEFT=1;
    interface IntReader {int read();}
    static int normalize(int value){return value==LEFT?LEFT:RIGHT;}
    static int read(IntReader reader){try{return normalize(reader.read());}catch(ClassCastException wrongType){return RIGHT;}}
    static boolean left(int value){return normalize(value)==LEFT;}
    private LanguageKeyPosition(){}
}
