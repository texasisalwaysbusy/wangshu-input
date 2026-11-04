package org.wangshu.ime;

/** Persist only implemented right-release actions; unknown/old values use the safe default. */
final class VoiceHoldSettings {
    static final String PREFERENCES="keyboard", RIGHT_ACTION="voiceRightAction";
    static final int NEWLINE=0, KEEP_TEXT=1;
    interface Reader { int read(); }
    private VoiceHoldSettings(){}
    static int normalize(int value){return value==KEEP_TEXT?KEEP_TEXT:NEWLINE;}
    static int read(Reader reader){try{return normalize(reader.read());}catch(ClassCastException invalid){return NEWLINE;}}
    static VoiceHoldController.RightAction action(int value){return normalize(value)==KEEP_TEXT?VoiceHoldController.RightAction.KEEP_TEXT:VoiceHoldController.RightAction.NEWLINE;}
    static String label(int value){return normalize(value)==KEEP_TEXT?"保留文字":"换行";}
    static void apply(VoiceHoldController controller,int value){controller.rightAction(action(value),label(value),"");}
}
