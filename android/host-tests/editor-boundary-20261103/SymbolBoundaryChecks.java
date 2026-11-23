package org.wangshu.ime;
import java.util.*;
public final class SymbolBoundaryChecks {
 static int checks;static void check(boolean b,String s){if(!b)throw new AssertionError(s);checks++;}
 static final class H implements EmojiSelection.Host {long s=1,g=1,r=1;Object c=new Object();boolean active=true;int sends;public long session(){return s;}public long generation(){return g;}public long selectionRevision(){return r;}public Object connection(){return c;}public boolean active(){return active;}public boolean send(Object o,String t){sends++;return true;}}
 static void pinyinSearch(){
  for(EmojiCatalog.Kind kind:EmojiCatalog.Kind.values())
   for(String category:EmojiCatalog.categories(kind))
    for(EmojiCatalog.Item item:EmojiCatalog.category(category)){
     check(!item.pinyin.isEmpty()&&item.pinyin.length()<=EmojiCatalog.PINYIN_LIMIT,"every item has a bounded alias");
     check(EmojiCatalog.searchPinyin(item.pinyin,kind).contains(item),"full alias finds the authored sequence");
    }
  EmojiSearchQuery query=new EmojiSearchQuery(EmojiCatalog.Kind.EMOJI);
  check(query.results().isEmpty()&&!query.backspace()&&!query.append(' '),"empty query does not select or write");
  for(char letter:"MAO".toCharArray())check(query.append(letter),"physical uppercase is accepted");
  List<EmojiCatalog.Item> displayed=query.results();
  EmojiCatalog.Item cat=displayed.get(0);
  check(cat.text.equals("🐱")&&query.selectable(displayed,cat),"exact pinyin outranks name substring");
  query.append('m');
  check(!query.selectable(displayed,cat),"older result cannot confirm after changing the query");
  query.backspace();
  check(query.results().get(0)==cat&&!query.selectable(displayed,cat),"a restored query gets a new result list");
  List<EmojiCatalog.Item> restored=query.results();
  H editor=new H();
  EmojiSelection.Token beforeHide=EmojiSelection.capture(editor);
  query.reset();
  check(query.text().isEmpty()&&!query.selectable(restored,cat),"picker reset invalidates its old results");
  editor.s++;
  check(EmojiSelection.select(editor,beforeHide,cat.text)==EmojiSelection.Outcome.STALE&&editor.sends==0,
    "reopening the same editor cannot reuse a pre-hide result");
  EmojiSelection.Token afterShow=EmojiSelection.capture(editor);
  check(EmojiSelection.select(editor,afterShow,cat.text)==EmojiSelection.Outcome.SUBMITTED&&editor.sends==1,
    "a rebuilt picker can confirm in the renewed session");
  check(EmojiCatalog.searchPinyin("maomi",EmojiCatalog.Kind.KAOMOJI).get(0).text.equals("( 'ω' )"),"kaomoji alias preserves its exact text");
  check(EmojiCatalog.searchPinyin("maomi",EmojiCatalog.Kind.EMOJI).isEmpty(),"emoji and kaomoji panels stay distinct");
  check(EmojiCatalog.searchPinyin("weixiao",EmojiCatalog.Kind.EMOJI).get(0).text.equals("😄"),"exact alias precedes variants");
  for(int i=0;i<EmojiCatalog.PINYIN_LIMIT;i++)check(query.append('a'),"bounded entry accepts a letter");
  check(!query.append('a')&&!query.append('猫')&&query.text().length()==40,"overlong and unsupported input stay local");
 }
 public static void main(String[] args){
  int count=0;Set<String> exact=new HashSet<>();for(EmojiCatalog.Kind kind:EmojiCatalog.Kind.values())for(String category:EmojiCatalog.categories(kind))for(EmojiCatalog.Item item:EmojiCatalog.category(category)){count++;check(!item.text.isEmpty()&&item.text.length()<=128,"bounded exact glyph");check(exact.add(item.text),"authored glyph unique");check(EmojiCatalog.search(item.text,kind).stream().anyMatch(x->x==item),"exact glyph searchable");}
  check(count>150,"nontrivial authored catalog");check(EmojiCatalog.search("",EmojiCatalog.Kind.EMOJI).isEmpty(),"empty query not all history");check(EmojiCatalog.search("x".repeat(41),EmojiCatalog.Kind.EMOJI).isEmpty(),"bounded query");
  try{EmojiCatalog.category("笑脸").clear();throw new AssertionError("mutable catalog");}catch(UnsupportedOperationException expected){checks++;}
  H h=new H();EmojiSelection.Token t=EmojiSelection.capture(h);check(EmojiSelection.select(h,t,"👩🏽‍💻")==EmojiSelection.Outcome.SUBMITTED&&h.sends==1,"exact ZWJ sequence submitted once");h.r++;check(EmojiSelection.select(h,t,"😀")==EmojiSelection.Outcome.STALE&&h.sends==1,"selection invalidates glyph token");
  h=new H();t=EmojiSelection.capture(h);h.active=false;check(EmojiSelection.select(h,t,"😀")==EmojiSelection.Outcome.STALE&&h.sends==0,"inactive glyph panel cannot send");
  pinyinSearch();
  System.out.println("PASS symbol "+checks+" checks across "+count+" authored entries; synthetic editor only");
 }
}
