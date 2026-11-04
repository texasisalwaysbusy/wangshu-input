package org.wangshu.ime;
/** Explicit preedit-only ASCII English segment, shared grammar with core/mixed_composition.h. */
final class MixedComposition {
    private MixedComposition(){}
    static boolean present(String text){return text.indexOf('`')>=0;}
    static boolean active(CharSequence text){boolean active=false;for(int i=0;i<text.length();i++)if(text.charAt(i)=='`')active=!active;return active;}
    static String display(String text){return active(text)?text+" · 英段未结束":""+text;}
    static String raw(String text){return text.replace("`","");}
    static boolean literalKey(String key){if(key.isEmpty())return false;for(int i=0;i<key.length();i++)if(key.charAt(i)<32||key.charAt(i)>126)return false;return true;}
}
