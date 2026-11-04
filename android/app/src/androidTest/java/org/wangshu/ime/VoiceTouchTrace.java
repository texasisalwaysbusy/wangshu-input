package org.wangshu.ime;

/** Bounded test-only event journal; records delivered events, never manufactures an UP.
 * Numeric actions use Android's stable DOWN=0/UP=1/MOVE=2/CANCEL=3 constants. */
final class VoiceTouchTrace {
    static final int LIMIT=96;
    final String layer;final StringBuilder history=new StringBuilder();int total,downs,ups;
    long down=-1,up=-1,downDelivered=-1,upDelivered=-1,stream=-1;
    boolean cancelled,mismatchedUp,multiplePointers;
    VoiceTouchTrace(String layer){this.layer=layer;}
    void record(int action,long downTime,long eventTime,long delivered,int pointers,int pointer,float x,float y,String phase,boolean focused){
        if(action==0){downs++;down=eventTime;downDelivered=delivered;stream=downTime;up=upDelivered=-1;multiplePointers|=pointers!=1;}
        else if(action==1){ups++;if(stream<0||downTime!=stream)mismatchedUp=true;else{up=eventTime;upDelivered=delivered;}}
        if(action==3)cancelled=true;
        if(pointers!=1)multiplePointers=true;
        if(total++<LIMIT)history.append("DELIVERED layer=").append(layer).append(" action=").append(action).append(" downTime=").append(downTime).append(" eventTime=").append(eventTime).append(" delivered=").append(delivered).append(" pointers=").append(pointers).append(" pointer=").append(pointer).append(" x=").append(x).append(" y=").append(y).append(" focus=").append(focused).append(" phase=").append(phase).append('\n');
    }
    boolean completeShortTap(long threshold){return downs==1&&ups==1&&!cancelled&&!mismatchedUp&&!multiplePointers&&down>=0&&up>=down&&downDelivered>=0&&upDelivered>=downDelivered&&up-down<threshold&&upDelivered-downDelivered<threshold;}
    String summary(){return "layer="+layer+" events="+total+" recorded="+Math.min(total,LIMIT)+" downs="+downs+" ups="+ups+" down="+down+" up="+up+" eventSpan="+(up<0||down<0?"UNKNOWN":Long.toString(up-down))+" deliverySpan="+(upDelivered<0||downDelivered<0?"UNKNOWN":Long.toString(upDelivered-downDelivered))+" cancel="+cancelled+" mismatchedUp="+mismatchedUp+" multiplePointers="+multiplePointers;}
}
