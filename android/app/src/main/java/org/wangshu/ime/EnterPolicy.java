package org.wangshu.ime;

/** L07: default permits navigation but not send; no-enter-action always wins. */
final class EnterPolicy {
    interface BoolReader {boolean read();}
    static boolean read(BoolReader reader){try{return reader.read();}catch(ClassCastException wrongType){return false;}}
    enum Decision { ACTION, NEWLINE, BLOCK }
    static Decision decide(boolean enabled,boolean offered,boolean navigation,boolean noEnterAction,boolean multiline){
        if(offered&&!noEnterAction&&(enabled||navigation))return Decision.ACTION;
        return multiline?Decision.NEWLINE:Decision.BLOCK;
    }
    private EnterPolicy(){}
}
