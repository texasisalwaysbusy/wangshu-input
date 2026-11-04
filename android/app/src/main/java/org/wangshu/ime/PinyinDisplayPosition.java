package org.wangshu.ime;

/** L02: default preserves the existing composition/candidate vertical order. */
final class PinyinDisplayPosition {
    static final int ABOVE=0,BELOW=1;
    interface IntReader {int read();}
    static int normalize(int value){return value==BELOW?BELOW:ABOVE;}
    static int read(IntReader reader){try{return normalize(reader.read());}catch(ClassCastException wrongType){return ABOVE;}}
    static boolean below(int value){return normalize(value)==BELOW;}
    private PinyinDisplayPosition(){}
}
