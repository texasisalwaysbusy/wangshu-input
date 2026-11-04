package org.wangshu.ime;
/** Visual mode only: does not read or modify an editor. */
final class SegmentKeyState {
    static boolean show(boolean english,boolean password,boolean math,boolean numbers,CharSequence composing){return !english&&!password&&!math&&!numbers&&composing!=null&&composing.length()>0&&composing.charAt(0)!='\\';}
    static boolean canAppend(boolean english,boolean password,boolean math,boolean numbers,CharSequence composing){if(!show(english,password,math,numbers,composing)||composing.length()>=128)return false;char last=composing.charAt(composing.length()-1);return (last>='a'&&last<='z')||(last>='A'&&last<='Z');}
    private SegmentKeyState(){}
}
