package org.wangshu.ime;
public final class SegmentKeyStateTest {
 static int count;static void check(boolean condition,String label){if(!condition)throw new AssertionError(label);count++;System.out.println("PASS "+label);}
 static boolean shown(boolean english,boolean password,boolean math,boolean numeric,String composition){return SegmentKeyState.show(english,password,math,numeric,composition);}
 static boolean valid(String composition){return SegmentKeyState.canAppend(false,false,false,false,composition);}
 public static void main(String[] args){
  check(!shown(false,false,false,false,null),"missing composition keeps Shift");check(!shown(false,false,false,false,""),"empty Chinese composition keeps Shift");
  check(shown(false,false,false,false,"xi"),"Chinese composition presents separator");check(valid("xi"),"letter suffix permits separator");check(valid("XI"),"uppercase Pinyin permits separator without changing case state");
  check(shown(false,false,false,false,"xi'"),"trailing separator keeps separator mode");check(!valid("xi'"),"trailing separator rejects duplicate");check(valid("xi'a"),"next letter allows later separator");
  check(!shown(true,false,false,false,"xi"),"English mode keeps Shift");check(!shown(false,true,false,false,"xi"),"password mode keeps Shift");check(!shown(false,false,true,false,"xi"),"math mode does not expose separator");check(!shown(false,false,false,true,"xi"),"numeric mode does not expose separator");
  check(!shown(false,false,false,false,"\\alpha"),"math command composition keeps Shift");check(!valid(""),"no leading separator");check(!valid("a".repeat(128)),"full composition refuses extra unit");check(valid("a".repeat(127)),"127-unit composition permits final separator");check(!valid("xi-"),"non-letter ending refuses separator");
  check(!SegmentKeyState.canAppend(true,false,false,false,"xi"),"stale Chinese callback refused after language switch");check(!SegmentKeyState.canAppend(false,false,true,false,"xi"),"stale callback refused after math switch");
  System.out.println("RESULT "+count+" PASS; pure visual state only, not key event, editor, Android or semantic segmentation");
 }
}
