package org.wangshu.ime;
import java.util.*;
public final class SymbolBoundaryChecks {
 static int checks;static void check(boolean b,String s){if(!b)throw new AssertionError(s);checks++;}
 static final class H implements EmojiSelection.Host {long s=1,g=1,r=1;Object c=new Object();boolean active=true;int sends;public long session(){return s;}public long generation(){return g;}public long selectionRevision(){return r;}public Object connection(){return c;}public boolean active(){return active;}public boolean send(Object o,String t){sends++;return true;}}
 public static void main(String[] args){
  int count=0;Set<String> exact=new HashSet<>();for(EmojiCatalog.Kind kind:EmojiCatalog.Kind.values())for(String category:EmojiCatalog.categories(kind))for(EmojiCatalog.Item item:EmojiCatalog.category(category)){count++;check(!item.text.isEmpty()&&item.text.length()<=128,"bounded exact glyph");check(exact.add(item.text),"authored glyph unique");check(EmojiCatalog.search(item.text,kind).stream().anyMatch(x->x==item),"exact glyph searchable");}
  check(count>150,"nontrivial authored catalog");check(EmojiCatalog.search("",EmojiCatalog.Kind.EMOJI).isEmpty(),"empty query not all history");check(EmojiCatalog.search("x".repeat(41),EmojiCatalog.Kind.EMOJI).isEmpty(),"bounded query");
  try{EmojiCatalog.category("笑脸").clear();throw new AssertionError("mutable catalog");}catch(UnsupportedOperationException expected){checks++;}
  H h=new H();EmojiSelection.Token t=EmojiSelection.capture(h);check(EmojiSelection.select(h,t,"👩🏽‍💻")==EmojiSelection.Outcome.SUBMITTED&&h.sends==1,"exact ZWJ sequence submitted once");h.r++;check(EmojiSelection.select(h,t,"😀")==EmojiSelection.Outcome.STALE&&h.sends==1,"selection invalidates glyph token");
  h=new H();t=EmojiSelection.capture(h);h.active=false;check(EmojiSelection.select(h,t,"😀")==EmojiSelection.Outcome.STALE&&h.sends==0,"inactive glyph panel cannot send");
  System.out.println("PASS symbol "+checks+" checks across "+count+" authored entries; synthetic editor only");
 }
}
