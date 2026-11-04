package org.wangshu.ime;

/** L01: bounded relative candidate font size; default 0 retains the baseline size values. */
final class CandidateTextSize {
    static final int MIN=-3, MAX=3, DEFAULT=0;
    interface IntReader {int read();}
    static int read(IntReader reader){try{return clamp(reader.read());}catch(ClassCastException wrongType){return DEFAULT;}}
    static int clamp(int delta){return Math.max(MIN,Math.min(MAX,delta));}
    static int toProgress(int delta){return clamp(delta)-MIN;}
    static int fromProgress(int progress){if(progress<=0)return MIN;if(progress>=MAX-MIN)return MAX;return MIN+progress;}
    static int sizeSp(boolean landscape,int delta){return (landscape?14:16)+clamp(delta);}
    static String describe(int delta){int n=clamp(delta);return n==0?"默认":n>0?"放大 "+n+" 级":"缩小 "+(-n)+" 级";}
    private CandidateTextSize(){}
}
